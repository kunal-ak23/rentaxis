package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.FineConfigDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrgFineSettings;
import com.datagami.rentaxis.domain.repository.LandlordOrgFineSettingsRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/settings/fines")
@RequiredArgsConstructor
public class FineSettingsController {

    private final LandlordOrgFineSettingsRepository repo;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<FineConfigDTO> get() {
        UUID tenantId = TenantContextHolder.getTenantId();
        LandlordOrgFineSettings s = repo.findByLandlordOrgId(tenantId)
                .orElseGet(() -> createDefault(tenantId));
        return ResponseEntity.ok(toDto(s));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<FineConfigDTO> update(@Valid @RequestBody FineConfigDTO body) {
        validate(body);

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

    private LandlordOrgFineSettings createDefault(UUID tenantId) {
        LandlordOrgFineSettings defaults = new LandlordOrgFineSettings();
        defaults.setLandlordOrgId(tenantId);
        defaults.setFineBounceAmount(new BigDecimal("500"));
        defaults.setFineSignatureMismatchAmount(new BigDecimal("500"));
        defaults.setFineAccountClosedAmount(new BigDecimal("1000"));
        defaults.setFineGraceDays(7);
        defaults.setFinePerDayRate(new BigDecimal("25"));
        return repo.save(defaults);
    }

    private void validate(FineConfigDTO body) {
        if (body.bounceAmount() != null && body.bounceAmount().signum() < 0) {
            throw new BusinessRuleViolationException("bounceAmount must be >= 0");
        }
        if (body.signatureMismatchAmount() != null && body.signatureMismatchAmount().signum() < 0) {
            throw new BusinessRuleViolationException("signatureMismatchAmount must be >= 0");
        }
        if (body.accountClosedAmount() != null && body.accountClosedAmount().signum() < 0) {
            throw new BusinessRuleViolationException("accountClosedAmount must be >= 0");
        }
        if (body.graceDays() != null && body.graceDays() < 0) {
            throw new BusinessRuleViolationException("graceDays must be >= 0");
        }
        if (body.perDayRate() != null && body.perDayRate().signum() < 0) {
            throw new BusinessRuleViolationException("perDayRate must be >= 0");
        }
    }

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
