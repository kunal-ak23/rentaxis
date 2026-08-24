package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.appversion.AppVersionService;
import com.datagami.rentaxis.core.appversion.AppVersionService.AppVersionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated app-version check. Every mobile app calls this on
 * startup with its own {@code app}+{@code platform} and compares its installed
 * integer build against the returned floor.
 *
 * <p>Sits under {@code /api/v1/public/**}, which is already {@code permitAll} in
 * {@code SecurityConfig} AND in {@code ApiSecurityFilter}'s skip list (verified
 * by grep — neither is touched here), so no auth context is required or read.
 *
 * <p><b>Fail-open by construction.</b> This endpoint never answers 404 and never
 * answers 500. An unknown/missing app or platform resolves to the permissive
 * fallback ({@code minSupportedBuild = 0} => nothing gated), and any unexpected
 * error (e.g. the database being unreachable) is caught here and also returns
 * the permissive fallback with 200. The reasoning: the client hard-blocks below
 * {@code minSupportedBuild}, so a version check that could fail closed would be
 * able to lock every user out of the app during an incident. The safe failure
 * mode of a gate is "let them in".
 */
@RestController
@RequestMapping("/api/v1/public/app-version")
public class AppVersionController {

    private static final Logger log = LoggerFactory.getLogger(AppVersionController.class);

    private final AppVersionService service;

    public AppVersionController(AppVersionService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AppVersionView> get(@RequestParam(required = false) String app,
                                              @RequestParam(required = false) String platform) {
        try {
            return ResponseEntity.ok(service.resolve(app, platform));
        } catch (Exception e) {
            // Fail open: a version check must not be able to lock users out by
            // erroring. Return the permissive default (min 0 => nothing gated)
            // rather than letting this bubble to a 500.
            log.warn("app-version lookup failed for app={} platform={}; failing open",
                    app, platform, e);
            return ResponseEntity.ok(AppVersionView.permissive());
        }
    }
}
