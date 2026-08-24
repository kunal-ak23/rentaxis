package com.datagami.rentaxis.core.appversion;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.AppVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Reads and writes the global app-version floor.
 *
 * <p>The read path ({@link #resolve}) is the server half of a fail-open
 * contract: any query it cannot satisfy — a null parameter, an app/platform
 * with no configured row — resolves to {@link AppVersionView#permissive()}
 * ({@code minSupportedBuild = 0}), never an exception. A version check that
 * could throw would let an outage lock every user out of the apps, which is the
 * opposite of what a version gate is for. Note this method never parses the
 * incoming strings into enums: an unknown value simply matches no row and falls
 * through to the permissive default, so it cannot throw.
 *
 * <p>The write path ({@link #upsert}) is the admin "bump the floor without a
 * redeploy" lever and is strict by contrast — it validates against the
 * {@link AppVersion.App}/{@link AppVersion.Platform} enums so a typo cannot
 * silently create an orphan row that no client will ever match.
 */
@Service
public class AppVersionService {

    private final AppVersionRepository repository;

    public AppVersionService(AppVersionRepository repository) {
        this.repository = repository;
    }

    /** The public GET payload: exactly the four fields in the HTTP contract. */
    public record AppVersionView(int minSupportedBuild,
                                 int latestBuild,
                                 String latestVersionName,
                                 String storeUrl) {

        /** The safe default: nothing is ever gated, no store link to show. */
        public static AppVersionView permissive() {
            return new AppVersionView(0, 0, "", "");
        }

        static AppVersionView of(AppVersion v) {
            return new AppVersionView(
                    v.getMinSupportedBuild(),
                    v.getLatestBuild(),
                    v.getLatestVersionName() == null ? "" : v.getLatestVersionName(),
                    v.getStoreUrl() == null ? "" : v.getStoreUrl());
        }
    }

    /**
     * Resolve the floor for one app+platform, case-insensitively. Unknown or
     * missing input yields the permissive fallback — this method must never
     * throw for a lookup miss.
     */
    @Transactional(readOnly = true)
    public AppVersionView resolve(String app, String platform) {
        if (app == null || platform == null) {
            return AppVersionView.permissive();
        }
        String a = app.trim().toUpperCase(Locale.ROOT);
        String p = platform.trim().toUpperCase(Locale.ROOT);
        return repository.findByAppAndPlatform(a, p)
                .map(AppVersionView::of)
                .orElseGet(AppVersionView::permissive);
    }

    /** Admin read: current state of every configured row. */
    @Transactional(readOnly = true)
    public List<AppVersion> listAll() {
        return repository.findAllByOrderByAppAscPlatformAsc();
    }

    /**
     * Admin write: set the floor/latest for one (app, platform). Upserts, so a
     * bump always lands on the single canonical row for that pair even if the
     * seed were ever absent. Rejects unknown app/platform with a 400 rather than
     * minting a row no client can match.
     */
    @Transactional
    public AppVersion upsert(String app,
                             String platform,
                             int minSupportedBuild,
                             int latestBuild,
                             String latestVersionName,
                             String storeUrl) {
        String a = normalizeApp(app);
        String p = normalizePlatform(platform);
        if (minSupportedBuild < 0 || latestBuild < 0) {
            throw new BusinessRuleViolationException(
                    "Build numbers must be non-negative");
        }
        AppVersion row = repository.findByAppAndPlatform(a, p)
                .orElseGet(() -> {
                    AppVersion created = new AppVersion();
                    created.setApp(a);
                    created.setPlatform(p);
                    return created;
                });
        row.setMinSupportedBuild(minSupportedBuild);
        row.setLatestBuild(latestBuild);
        row.setLatestVersionName(latestVersionName == null ? "" : latestVersionName);
        row.setStoreUrl(storeUrl == null ? "" : storeUrl);
        row.setUpdatedAt(Instant.now());
        return repository.save(row);
    }

    private static String normalizeApp(String app) {
        if (app == null) {
            throw new BusinessRuleViolationException("app is required");
        }
        try {
            return AppVersion.App.valueOf(app.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolationException("Unknown app: " + app);
        }
    }

    private static String normalizePlatform(String platform) {
        if (platform == null) {
            throw new BusinessRuleViolationException("platform is required");
        }
        try {
            return AppVersion.Platform.valueOf(platform.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolationException("Unknown platform: " + platform);
        }
    }
}
