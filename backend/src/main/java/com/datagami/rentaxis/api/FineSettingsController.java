package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.FineConfigDTO;
import com.datagami.rentaxis.core.service.FineSettingsInitializer;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrgFineSettings;
import com.datagami.rentaxis.domain.repository.LandlordOrgFineSettingsRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/settings/fines")
@RequiredArgsConstructor
public class FineSettingsController {

    private final LandlordOrgFineSettingsRepository repo;
    private final FineSettingsInitializer initializer;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<FineConfigDTO> get() {
        UUID tenantId = TenantContextHolder.getTenantId();
        LandlordOrgFineSettings s = repo.findByLandlordOrgId(tenantId)
                .orElseGet(() -> {
                    try {
                        return initializer.upsertDefault(tenantId);
                    } catch (DataIntegrityViolationException e) {
                        return repo.findByLandlordOrgId(tenantId)
                                .orElseThrow(() -> new IllegalStateException(
                                        "Org fine settings missing after concurrent create for tenant " + tenantId, e));
                    }
                });
        return ResponseEntity.ok(toDto(s));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<FineConfigDTO> update(@Valid @RequestBody FineConfigDTO body) {

        UUID tenantId = TenantContextHolder.getTenantId();
        LandlordOrgFineSettings s = repo.findByLandlordOrgId(tenantId)
                .orElseGet(() -> {
                    LandlordOrgFineSettings n = new LandlordOrgFineSettings();
                    n.setLandlordOrgId(tenantId);
                    return n;
                });

        s.setFineBounceAmount(body.bounceAmount());
        s.setFineSignatureMismatchAmount(body.signatureMismatchAmount());
        s.setFineAccountClosedAmount(body.accountClosedAmount());
        s.setFineGraceDays(body.graceDays());
        s.setFinePerDayRate(body.perDayRate());

        LandlordOrgFineSettings saved = repo.save(s);
        return ResponseEntity.ok(toDto(saved));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private FineConfigDTO toDto(LandlordOrgFineSettings s) {
        return new FineConfigDTO(
                s.getFineBounceAmount(),
                s.getFineSignatureMismatchAmount(),
                s.getFineAccountClosedAmount(),
                s.getFineGraceDays(),
                s.getFinePerDayRate()
        );
    }
}
