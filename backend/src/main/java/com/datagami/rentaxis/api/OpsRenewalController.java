package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.renewal.LeaseRenewalScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/renewals")
@RequiredArgsConstructor
public class OpsRenewalController {

    private final LeaseRenewalScheduler scheduler;

    @PostMapping("/run-now")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<Void> runNow() {
        scheduler.runNow(LocalDate.now());
        return ResponseEntity.accepted().build();
    }

    /**
     * Run the renewal pipeline for one explicitly selected tenant.
     *
     * <p>The tenant scope is important for support and verification work: the
     * unscoped endpoint intentionally scans every opted-in organization and can
     * send due reminders. Callers that are investigating one organization must
     * not have to trigger reminder processing for unrelated customers.
     */
    @PostMapping("/run-now/{tenantId}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<Void> runNowForTenant(@PathVariable UUID tenantId) {
        scheduler.runNowForTenant(tenantId, LocalDate.now());
        return ResponseEntity.accepted().build();
    }
}
