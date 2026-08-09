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
    public ResponseEntity<LandlordOrg> createTenant(@RequestBody Map<String, Object> payload) {
        String name = stringValue(payload.get("name"));
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        LandlordOrg org = service.provisionTenant(name);
        // The provisioning form also collects address/TRN/phone/logo/OTP toggle —
        // persist them too instead of silently dropping everything but the name.
        if (applyOptionalFields(org, payload)) {
            org = service.save(org);
        }
        return ResponseEntity.ok(org);
    }

    @GetMapping
    public ResponseEntity<List<LandlordOrg>> getTenants() {
        return ResponseEntity.ok(service.listAllTenants());
    }

    @PutMapping("/{id}")
    public ResponseEntity<LandlordOrg> updateTenant(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> payload) {
        java.util.Optional<LandlordOrg> orgOpt = service.findById(id);
        if (orgOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        LandlordOrg org = orgOpt.get();
        String name = stringValue(payload.get("name"));
        if (name != null && !name.isBlank()) {
            org.setName(name);
        }
        applyOptionalFields(org, payload);
        return ResponseEntity.ok(service.save(org));
    }

    /**
     * Applies the optional tenant fields shared by create and update. Only keys
     * present in the payload are written (partial-update semantics). Returns
     * true when at least one field was applied.
     */
    private boolean applyOptionalFields(LandlordOrg org, Map<String, Object> payload) {
        boolean changed = false;
        if (payload.containsKey("address")) {
            org.setAddress(stringValue(payload.get("address")));
            changed = true;
        }
        if (payload.containsKey("trn")) {
            org.setTrn(stringValue(payload.get("trn")));
            changed = true;
        }
        if (payload.containsKey("status")) {
            String status = stringValue(payload.get("status"));
            // status is a NOT NULL column — never null/blank it out.
            if (status != null && !status.isBlank()) {
                org.setStatus(status);
                changed = true;
            }
        }
        if (payload.containsKey("logoUrl")) {
            org.setLogoUrl(stringValue(payload.get("logoUrl")));
            changed = true;
        }
        if (payload.containsKey("phone")) {
            org.setPhone(stringValue(payload.get("phone")));
            changed = true;
        }
        if (payload.containsKey("ticketOtpRequired")) {
            org.setTicketOtpRequired(booleanValue(payload.get("ticketOtpRequired")));
            changed = true;
        }
        return changed;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        // Back-compat: older clients sent the toggle as the string "true"/"false".
        return Boolean.parseBoolean(stringValue(value));
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
