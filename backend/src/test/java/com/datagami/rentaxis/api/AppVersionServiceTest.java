package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.appversion.AppVersionRepository;
import com.datagami.rentaxis.core.appversion.AppVersionService;
import com.datagami.rentaxis.core.appversion.AppVersionService.AppVersionView;
import com.datagami.rentaxis.domain.entity.AppVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level cover for {@link AppVersionService}, the server half of the
 * fail-open version contract. The read path must resolve every miss to the
 * permissive default rather than throwing; the write path must reject garbage.
 */
@ExtendWith(MockitoExtension.class)
class AppVersionServiceTest {

    @Mock
    AppVersionRepository repository;

    @InjectMocks
    AppVersionService service;

    private static AppVersion row(String app, String platform, int min, int latest,
                                  String name, String storeUrl) {
        AppVersion v = new AppVersion();
        v.setApp(app);
        v.setPlatform(platform);
        v.setMinSupportedBuild(min);
        v.setLatestBuild(latest);
        v.setLatestVersionName(name);
        v.setStoreUrl(storeUrl);
        return v;
    }

    // ── resolve: known row ───────────────────────────────────────────────────

    @Test
    void resolve_knownRow_mapsAllFields() {
        when(repository.findByAppAndPlatform("RENTER", "ANDROID"))
                .thenReturn(Optional.of(row("RENTER", "ANDROID", 0, 3, "1.2.0", "")));

        AppVersionView view = service.resolve("RENTER", "ANDROID");

        assertThat(view.minSupportedBuild()).isEqualTo(0);
        assertThat(view.latestBuild()).isEqualTo(3);
        assertThat(view.latestVersionName()).isEqualTo("1.2.0");
        assertThat(view.storeUrl()).isEqualTo("");
    }

    @Test
    void resolve_isCaseInsensitive_normalisesToUpper() {
        when(repository.findByAppAndPlatform("RENTER", "ANDROID"))
                .thenReturn(Optional.of(row("RENTER", "ANDROID", 5, 7, "9.9.9", "")));

        AppVersionView view = service.resolve("  renter ", "Android");

        // Proves the query key was upper-cased/trimmed, not passed through raw.
        verify(repository).findByAppAndPlatform("RENTER", "ANDROID");
        assertThat(view.latestBuild()).isEqualTo(7);
    }

    // ── resolve: fail-open on every miss ─────────────────────────────────────

    @Test
    void resolve_unknownApp_returnsPermissiveFallback_notThrow() {
        when(repository.findByAppAndPlatform(any(), any())).thenReturn(Optional.empty());

        AppVersionView view = service.resolve("NOPE", "ANDROID");

        // The heart of the server-side fail-open: a miss is min 0 (nothing
        // gated), never an exception, never a null.
        assertThat(view.minSupportedBuild()).isEqualTo(0);
        assertThat(view.latestBuild()).isEqualTo(0);
        assertThat(view.latestVersionName()).isEqualTo("");
        assertThat(view.storeUrl()).isEqualTo("");
    }

    @Test
    void resolve_nullApp_returnsPermissive_withoutHittingDb() {
        AppVersionView view = service.resolve(null, "ANDROID");

        assertThat(view.minSupportedBuild()).isEqualTo(0);
        verify(repository, never()).findByAppAndPlatform(any(), any());
    }

    @Test
    void resolve_nullPlatform_returnsPermissive_withoutHittingDb() {
        AppVersionView view = service.resolve("RENTER", null);

        assertThat(view.minSupportedBuild()).isEqualTo(0);
        verify(repository, never()).findByAppAndPlatform(any(), any());
    }

    @Test
    void resolve_nullStoreUrlAndName_coalesceToEmptyString() {
        when(repository.findByAppAndPlatform("MANAGER", "IOS"))
                .thenReturn(Optional.of(row("MANAGER", "IOS", 2, 2, null, null)));

        AppVersionView view = service.resolve("MANAGER", "IOS");

        assertThat(view.latestVersionName()).isEqualTo("");
        assertThat(view.storeUrl()).isEqualTo("");
    }

    // ── upsert: strict on write ──────────────────────────────────────────────

    @Test
    void upsert_unknownApp_rejectedWith400() {
        assertThatThrownBy(() -> service.upsert("BOGUS", "ANDROID", 1, 2, "1.0.0", ""))
                .isInstanceOf(BusinessRuleViolationException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void upsert_unknownPlatform_rejectedWith400() {
        assertThatThrownBy(() -> service.upsert("RENTER", "WINDOWS", 1, 2, "1.0.0", ""))
                .isInstanceOf(BusinessRuleViolationException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void upsert_negativeBuild_rejectedWith400() {
        assertThatThrownBy(() -> service.upsert("RENTER", "ANDROID", -1, 2, "1.0.0", ""))
                .isInstanceOf(BusinessRuleViolationException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void upsert_existingRow_updatesInPlace() {
        AppVersion existing = row("RENTER", "ANDROID", 0, 3, "1.2.0", "");
        when(repository.findByAppAndPlatform("RENTER", "ANDROID"))
                .thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppVersion saved = service.upsert("renter", "android", 3, 4, "1.3.0", "https://x");

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getMinSupportedBuild()).isEqualTo(3);
        assertThat(saved.getLatestBuild()).isEqualTo(4);
        assertThat(saved.getLatestVersionName()).isEqualTo("1.3.0");
        assertThat(saved.getStoreUrl()).isEqualTo("https://x");
        assertThat(saved.getUpdatedAt()).isNotNull();
    }
}
