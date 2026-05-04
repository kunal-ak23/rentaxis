package com.datagami.rentaxis.api;

import com.datagami.rentaxis.domain.entity.OrgSettings;
import com.datagami.rentaxis.domain.repository.OrgSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/settings/org")
@RequiredArgsConstructor
public class OrgSettingsController {

    private final OrgSettingsRepository repo;

    /** Accessible to all roles — renter portal reads penalty_payment_instructions. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> get() {
        List<OrgSettings> rows = repo.findAll();
        String instructions = rows.isEmpty() ? null : rows.get(0).getPenaltyPaymentInstructions();
        return ResponseEntity.ok(Map.of("penaltyPaymentInstructions", instructions == null ? "" : instructions));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<Map<String, Object>> update(
            @RequestBody Map<String, String> body) {
        String instructions = body.getOrDefault("penaltyPaymentInstructions", "");
        List<OrgSettings> rows = repo.findAll();
        OrgSettings s = rows.isEmpty() ? new OrgSettings() : rows.get(0);
        s.setPenaltyPaymentInstructions(instructions.isBlank() ? null : instructions);
        repo.save(s);
        return ResponseEntity.ok(Map.of("penaltyPaymentInstructions", instructions));
    }
}
