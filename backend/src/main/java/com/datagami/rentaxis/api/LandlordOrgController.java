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
    private final com.datagami.rentaxis.domain.repository.PropertyRepository propertyRepository;

    public LandlordOrgController(LandlordOrgService service, TenantFeatureService tenantFeatureService,
            com.datagami.rentaxis.domain.repository.PropertyRepository propertyRepository) {
        this.service = service;
        this.tenantFeatureService = tenantFeatureService;
        this.propertyRepository = propertyRepository;
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
        // Under the row lock like every other write of an existing row (review r3B I1).
        if (applyOptionalFields(new LandlordOrg(), payload)) {
            UUID id = org.getId();
            try {
                org = service.updateLocked(id, o -> applyOptionalFields(o, payload));
            } catch (BrandingNotSavedException e) {
                // R3 minor 2: the organisation exists now; answering 400 would invite a
                // retry that creates a second one. Save everything else and say which
                // part is missing (the dialog asks for the logo/stamp to be uploaded again).
                Map<String, Object> rest = new java.util.HashMap<>(payload);
                rest.remove("logoUrl");
                rest.remove("stampImageUrl");
                LandlordOrg saved = service.updateLocked(id, o -> applyOptionalFields(o, rest));
                return ResponseEntity.ok().header(BRANDING_HEADER, "not-saved").body(saved);
            }
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
        if (service.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Review r3B I1: read, check and write under the row lock in one transaction —
        // an entity read before a concurrent deactivation, merged afterwards, wrote the
        // old status back.
        return ResponseEntity.ok(service.updateLocked(id, org -> {
            // Break-it R3 ops3 F6: a stale dialog refused. "expected" carries the values
            // the dialog loaded for the fields it changes; if any moved meanwhile the
            // edit is refused with org.changed rather than overwriting it.
            requireUnchanged(org, payload.get("expected"));
            String name = stringValue(payload.get("name"));
            if (name != null && !name.isBlank()) {
                service.requireNameFreeForRename(id, name);
                org.setName(name);
            }
            // Never the status: an edit dialog opened before another tab deactivated the
            // organisation sent its old "ACTIVE" back and re-activated it. Status moves
            // only through PUT /{id}/status. Older clients still send the key; it is ignored.
            applyOptionalFields(org, payload, false);
        }));
    }

    public static final String ORG_CHANGED = "org.changed";
    static final String ORG_CHANGED_MESSAGE =
            "This organisation was changed by someone else since you opened it. Reload and try again.";
    static final java.util.Set<String> STATUSES = java.util.Set.of("ACTIVE", "INACTIVE");

    /**
     * Break-it R3 ops3 F6: the one way to activate or deactivate an organisation.
     * Body {@code {"status": "ACTIVE"|"INACTIVE", "expectedStatus": "..."}};
     * with {@code expectedStatus} (the status the screen showed) a different current
     * status is a 409 {@code org.changed}.
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<LandlordOrg> setStatus(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        if (service.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String status = stringValue(body.get("status"));
        if (status == null || !STATUSES.contains(status)) {
            throw new com.datagami.rentaxis.api.exception.BusinessRuleViolationException(
                    "Status must be ACTIVE or INACTIVE");
        }
        // Checked and written under the same row lock as an edit (review r3B I1).
        return ResponseEntity.ok(service.updateLocked(id, org -> {
            if (body.containsKey("expectedStatus")
                    && !java.util.Objects.equals(stringValue(body.get("expectedStatus")), org.getStatus())) {
                throw new com.datagami.rentaxis.api.exception.FiguresChangedException(ORG_CHANGED, ORG_CHANGED_MESSAGE);
            }
            org.setStatus(status);
        }));
    }

    /** The fields an edit may name in {@code expected}, read from the organisation as they are now. */
    private static Object currentValue(LandlordOrg org, String field) {
        return switch (field) {
            case "name" -> org.getName();
            case "address" -> org.getAddress();
            case "trn" -> org.getTrn();
            case "phone" -> org.getPhone();
            case "logoUrl" -> org.getLogoUrl();
            case "stampImageUrl" -> org.getStampImageUrl();
            case "ticketOtpRequired" -> org.getTicketOtpRequired() == null || org.getTicketOtpRequired();
            case "status" -> org.getStatus();
            default -> throw new com.datagami.rentaxis.api.exception.BusinessRuleViolationException(
                    "Unknown field in expected: " + field);
        };
    }

    private static void requireUnchanged(LandlordOrg org, Object expected) {
        if (!(expected instanceof Map<?, ?> fields)) {
            return;
        }
        for (Map.Entry<?, ?> e : fields.entrySet()) {
            String field = String.valueOf(e.getKey());
            Object now = currentValue(org, field);
            Object then = e.getValue();
            boolean same = "ticketOtpRequired".equals(field)
                    ? now.equals(then == null || booleanValue(then))
                    : blankToNull(stringValue(now)) == null
                            ? blankToNull(stringValue(then)) == null
                            : stringValue(now).equals(stringValue(then));
            if (!same) {
                throw new com.datagami.rentaxis.api.exception.FiguresChangedException(ORG_CHANGED, ORG_CHANGED_MESSAGE);
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * Applies the optional tenant fields shared by create and update. Only keys
     * present in the payload are written (partial-update semantics). Returns
     * true when at least one field was applied.
     */
    private boolean applyOptionalFields(LandlordOrg org, Map<String, Object> payload) {
        return applyOptionalFields(org, payload, true);
    }

    private boolean applyOptionalFields(LandlordOrg org, Map<String, Object> payload, boolean allowStatus) {
        boolean changed = false;
        if (payload.containsKey("address")) {
            org.setAddress(stringValue(payload.get("address")));
            changed = true;
        }
        if (payload.containsKey("trn")) {
            org.setTrn(stringValue(payload.get("trn")));
            changed = true;
        }
        if (allowStatus && payload.containsKey("status")) {
            String status = stringValue(payload.get("status"));
            // status is a NOT NULL column — never null/blank it out.
            if (status != null && !status.isBlank()) {
                org.setStatus(status);
                changed = true;
            }
        }
        if (payload.containsKey("logoUrl")) {
            org.setLogoUrl(adoptStaged(org, stringValue(payload.get("logoUrl"))));
            changed = true;
        }
        // The stamp printed beside the landlord signature on the contract: saved
        // exactly like the logo (same upload, same role, this organisation only).
        // The contract renderer only ever inlines it from our own storage.
        if (payload.containsKey("stampImageUrl")) {
            String stamp = adoptStaged(org, stringValue(payload.get("stampImageUrl")));
            if (!java.util.Objects.equals(blankToNull(stamp), blankToNull(org.getStampImageUrl()))) {
                // A new (or removed) stamp restarts the executed-copy sweep's clock.
                org.setStampSetAt(blankToNull(stamp) == null ? null : java.time.Instant.now());
            }
            org.setStampImageUrl(stamp);
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

    private com.datagami.rentaxis.core.service.BlobStorageService blobs;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBlobStorageService(com.datagami.rentaxis.core.service.BlobStorageService blobs) {
        this.blobs = blobs;
    }

    /**
     * A logo or stamp uploaded in the "new organisation" dialog was staged in
     * private storage (the organisation had no container yet); saving it on the
     * organisation moves it into that organisation's own container, where its PDFs
     * and header read it and its purge removes it. Anything else is kept as sent.
     */
    private String adoptStaged(LandlordOrg target, String url) {
        if (blobs == null || target.getId() == null || url == null || url.isBlank()) {
            return url;
        }
        String moved;
        try {
            moved = blobs.copyStagedBranding(target.getId(), url);
        } catch (RuntimeException e) {
            throw new BrandingNotSavedException();
        }
        if (moved.equals(url)) {
            return url;
        }
        // R3 minor 5: the staged source goes only once the save has committed; a
        // rolled-back save removes the copy instead, so neither side is orphaned.
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            blobs.deleteBrandingQuietly(status == STATUS_COMMITTED ? url : moved);
                        }
                    });
        } else {
            blobs.deleteBrandingQuietly(url);
        }
        return moved;
    }

    /** A staged logo or stamp could not be moved into the organisation's storage. */
    static final class BrandingNotSavedException extends com.datagami.rentaxis.api.exception.BusinessRuleViolationException {
        BrandingNotSavedException() {
            super("The uploaded image could not be stored for this organisation. Upload it again.");
        }
    }

    /** Response header on a create whose organisation exists but whose logo/stamp could not be kept. */
    public static final String BRANDING_HEADER = "X-Org-Branding";

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

    /**
     * The properties of one organisation, named in the path, for the user screen's
     * property-manager assignment picker (break round 1, F7). That picker used the
     * org-scoped {@code GET /api/v1/properties}, which in Global View (no
     * organisation selected) listed every organisation's properties and now answers
     * 400; the organisation being assigned into is the one that matters, whatever
     * is selected. Same {@code [{property: {id, nameEn, nameAr}}]} shape.
     */
    @GetMapping("/{id}/properties")
    public ResponseEntity<List<Map<String, Object>>> getProperties(@PathVariable UUID id) {
        if (service.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Act in that organisation for the read, so the tenant filter (which follows
        // the context, possibly another organisation the SUPER_ADMIN has selected)
        // agrees with the explicit tenant predicate.
        UUID previous = com.datagami.rentaxis.core.tenant.TenantContextHolder.getTenantId();
        com.datagami.rentaxis.core.tenant.TenantContextHolder.setTenantId(id);
        try {
            List<Map<String, Object>> out = propertyRepository.findByTenantIdOrderByNameEnAsc(id).stream()
                    .map(p -> {
                        Map<String, Object> property = new java.util.LinkedHashMap<>();
                        property.put("id", p.getId());
                        property.put("nameEn", p.getNameEn());
                        property.put("nameAr", p.getNameAr());
                        return Map.<String, Object>of("property", property);
                    })
                    .toList();
            return ResponseEntity.ok(out);
        } finally {
            if (previous == null) {
                com.datagami.rentaxis.core.tenant.TenantContextHolder.clear();
            } else {
                com.datagami.rentaxis.core.tenant.TenantContextHolder.setTenantId(previous);
            }
        }
    }
}
