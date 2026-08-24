package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.appversion.AppVersionService;
import com.datagami.rentaxis.domain.entity.AppVersion;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SUPER_ADMIN-only surface for reading and bumping the app-version floor.
 *
 * <p>This is the "raise the floor without redeploying" lever: the seed ships
 * everything inert ({@code minSupportedBuild = 0}), and phase 2 of the auth
 * migration flips a real minimum here once telemetry shows the fleet has
 * upgraded. Kept off the {@code /api/v1/public/**} tree deliberately — that
 * prefix is {@code permitAll}; these writes must be authenticated, so they live
 * under {@code /api/v1/admin/**}, which falls through to
 * {@code .anyRequest().authenticated()} and is then narrowed to SUPER_ADMIN by
 * the class-level {@code @PreAuthorize} (method security is enabled in
 * {@code SecurityConfig}). Same RBAC-in-the-controller split as
 * {@code StaffController}/{@code PromotionAdminController}.
 */
@RestController
@RequestMapping("/api/v1/admin/app-versions")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AppVersionAdminController {

    private final AppVersionService service;

    public AppVersionAdminController(AppVersionService service) {
        this.service = service;
    }

    /** Current state of every configured (app, platform) row. */
    @GetMapping
    public ResponseEntity<List<AppVersion>> listAll() {
        return ResponseEntity.ok(service.listAll());
    }

    /** Set the floor/latest for one (app, platform). Upsert on the pair. */
    @PutMapping("/{app}/{platform}")
    public ResponseEntity<AppVersion> bump(@PathVariable String app,
                                           @PathVariable String platform,
                                           @RequestBody BumpRequest body) {
        if (body == null || body.minSupportedBuild() == null || body.latestBuild() == null) {
            throw new BusinessRuleViolationException(
                    "minSupportedBuild and latestBuild are required");
        }
        AppVersion saved = service.upsert(
                app,
                platform,
                body.minSupportedBuild(),
                body.latestBuild(),
                body.latestVersionName(),
                body.storeUrl());
        return ResponseEntity.ok(saved);
    }

    /**
     * Admin bump payload. Build fields are boxed so a missing field is a 400
     * (see {@link #bump}) rather than silently defaulting a floor to 0.
     */
    public record BumpRequest(Integer minSupportedBuild,
                              Integer latestBuild,
                              String latestVersionName,
                              String storeUrl) {
    }
}
