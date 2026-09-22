package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.LandlordOrgService;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/tenant")
public class TenantFeatureController {

    private final TenantFeatureService tenantFeatureService;
    private final LandlordOrgService landlordOrgService;

    public TenantFeatureController(TenantFeatureService tenantFeatureService, LandlordOrgService landlordOrgService) {
        this.tenantFeatureService = tenantFeatureService;
        this.landlordOrgService = landlordOrgService;
    }

    /**
     * Returns a simple feature-name → enabled map for the current tenant.
     * Accessible to any authenticated user so the frontend can gate navigation items.
     */
    @GetMapping("/features")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Boolean>> getEnabledFeatures() {
        UUID tenantId = TenantContextHolder.getTenantId();
        // A SUPER_ADMIN with no organisation selected has no tenant to look up features for; answer with defaults.
        if (tenantId == null) {
            Map<String, Boolean> defaults = Arrays.stream(TenantFeature.values())
                    .collect(Collectors.toMap(TenantFeature::name, TenantFeature::isDefaultEnabled));
            return ResponseEntity.ok(defaults);
        }
        Map<String, Boolean> result = Arrays.stream(TenantFeature.values())
                .collect(Collectors.toMap(
                        TenantFeature::name,
                        f -> tenantFeatureService.isEnabled(tenantId, f)
                ));
        return ResponseEntity.ok(result);
    }

    /**
     * Returns basic tenant info (slug, name) for the current tenant.
     * Used by the frontend to build marketplace links for RENTER users.
     */
    @GetMapping("/info")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, String>> getTenantInfo() {
        UUID tenantId = TenantContextHolder.getTenantId();
        // A SUPER_ADMIN with no organisation selected has no tenant to look up; answer with an empty slug/name.
        if (tenantId == null) {
            return ResponseEntity.ok(Map.of("slug", "", "name", ""));
        }
        return landlordOrgService.findById(tenantId)
                .map(org -> ResponseEntity.ok(Map.of(
                        "slug", org.getSlug() != null ? org.getSlug() : "",
                        "name", org.getName() != null ? org.getName() : ""
                )))
                .orElse(ResponseEntity.ok(Map.of("slug", "", "name", "")));
    }
}
