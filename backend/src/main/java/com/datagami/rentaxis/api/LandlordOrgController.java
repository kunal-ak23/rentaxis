package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.FeatureToggleDTO;
import com.datagami.rentaxis.core.service.LandlordOrgService;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/tenants")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class LandlordOrgController {

    private final LandlordOrgService service;
    private final TenantFeatureService tenantFeatureService;

    public LandlordOrgController(LandlordOrgService service, TenantFeatureService tenantFeatureService) {
        this.service = service;
        this.tenantFeatureService = tenantFeatureService;
    }

    @PostMapping
    public ResponseEntity<LandlordOrg> createTenant(@RequestBody Map<String, String> payload) {
        String name = payload.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        LandlordOrg org = service.provisionTenant(name);
        return ResponseEntity.ok(org);
    }

    @GetMapping
    public ResponseEntity<List<LandlordOrg>> getTenants() {
        return ResponseEntity.ok(service.listAllTenants());
    }

    @PutMapping("/{id}")
    public ResponseEntity<LandlordOrg> updateTenant(
            @PathVariable UUID id,
            @RequestBody Map<String, String> payload) {
        java.util.Optional<LandlordOrg> orgOpt = service.findById(id);
        if (orgOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        LandlordOrg org = orgOpt.get();
        if (payload.containsKey("name") && !payload.get("name").isBlank()) {
            org.setName(payload.get("name"));
        }
        if (payload.containsKey("address")) {
            org.setAddress(payload.get("address"));
        }
        if (payload.containsKey("trn")) {
            org.setTrn(payload.get("trn"));
        }
        if (payload.containsKey("status")) {
            org.setStatus(payload.get("status"));
        }
        if (payload.containsKey("logoUrl")) {
            org.setLogoUrl(payload.get("logoUrl"));
        }
        if (payload.containsKey("phone")) {
            org.setPhone(payload.get("phone"));
        }
        if (payload.containsKey("ticketOtpRequired")) {
            org.setTicketOtpRequired(Boolean.parseBoolean(payload.get("ticketOtpRequired")));
        }
        return ResponseEntity.ok(service.save(org));
    }

    /**
     * Hard-delete a tenant and all of its data (every row in every table that
     * has a {@code tenant_id} column matching this id). Irreversible.
     *
     * <p>Safety: caller must supply {@code confirmName} matching the tenant's
     * current name exactly. SUPER_ADMIN role is already enforced at the class
     * level. Intended for E2E test cleanup and rare ops operations — there is
     * no UI surface for this and there should not be one.
     *
     * <p>Returns 204 on success, 400 if confirmName doesn't match, 404 if the
     * tenant doesn't exist.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTenant(
            @PathVariable UUID id,
            @RequestParam("confirmName") String confirmName) {
        if (service.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            service.deleteTenant(id, confirmName);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/features")
    public ResponseEntity<List<FeatureToggleDTO>> getFeatures(@PathVariable UUID id) {
        if (service.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(tenantFeatureService.getAll(id));
    }

    @PutMapping("/{id}/features/{feature}")
    public ResponseEntity<Void> setFeature(
            @PathVariable UUID id,
            @PathVariable TenantFeature feature,
            @RequestBody Map<String, Boolean> body) {
        if (service.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            return ResponseEntity.badRequest().build();
        }
        tenantFeatureService.setEnabled(id, feature, enabled);
        return ResponseEntity.ok().build();
    }
}
