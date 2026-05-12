package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.renewal.LeaseRenewalScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

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
}
