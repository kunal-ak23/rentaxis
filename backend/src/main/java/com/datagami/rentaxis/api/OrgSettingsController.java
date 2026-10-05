package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.OrgSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/settings/org")
@RequiredArgsConstructor
public class OrgSettingsController {

    private final OrgSettingsService orgSettings;

    /** Accessible to all roles — renter portal reads penalty_payment_instructions. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> get() {
        return ResponseEntity.ok(Map.of(
                "penaltyPaymentInstructions", orgSettings.getPenaltyPaymentInstructions()));
    }

    /**
     * Update org-level settings. Body: {@code {"penaltyPaymentInstructions": string}};
     * a blank value clears the instructions.
     *
     * <p>API-only for now — no web or mobile admin screen writes this yet, so the
     * value is set via curl/ops tooling. The read surface is the renter portal's
     * penalties page ("How to pay" block). If an admin settings UI grows a field
     * for this, remove this note.
     *
     * <p>A caller with no organisation selected gets a 400 rather than a write:
     * see {@link OrgSettingsService#updatePenaltyPaymentInstructions(String)}.
     */
    @PutMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<Map<String, Object>> update(
            @RequestBody Map<String, String> body) {
        String saved = orgSettings.updatePenaltyPaymentInstructions(
                body.getOrDefault("penaltyPaymentInstructions", ""));
        return ResponseEntity.ok(Map.of("penaltyPaymentInstructions", saved));
    }

    /**
     * Settings › Organisation › "Check the payee name on scanned cheques": the
     * switch and the valid payee names, for the caller's organisation only.
     * Company Admin (or a system admin with the organisation selected).
     */
    @GetMapping("/payee-check")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<OrgSettingsService.PayeeCheckSettings> getPayeeCheck() {
        return ResponseEntity.ok(orgSettings.getPayeeCheck());
    }

    /**
     * Settings › Organisation › Office hours: when office-visit meetings can be
     * booked. Read by staff booking a meeting too, so a manager sees the same hours.
     */
    @GetMapping("/office-hours")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<OrgSettingsService.OfficeHours> getOfficeHours() {
        return ResponseEntity.ok(orgSettings.getOfficeHours());
    }

    /** Body: {@code {"start": "09:00", "end": "18:00"}}. */
    @PutMapping("/office-hours")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<OrgSettingsService.OfficeHours> updateOfficeHours(
            @RequestBody OrgSettingsService.OfficeHours body) {
        return ResponseEntity.ok(orgSettings.updateOfficeHours(body.start(), body.end()));
    }

    /** Body: {@code {"enabled": boolean, "validNames": [string]}}. */
    @PutMapping("/payee-check")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<OrgSettingsService.PayeeCheckSettings> updatePayeeCheck(
            @RequestBody OrgSettingsService.PayeeCheckSettings body) {
        return ResponseEntity.ok(orgSettings.updatePayeeCheck(body.enabled(), body.validNames()));
    }
}
