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
                    // The penalty columns are NOT NULL, so a row created here has to
                    // carry the same defaults the initializer seeds.
                    n.setBouncesBeforePenalty(FineSettingsInitializer.DEFAULT_BOUNCES_BEFORE_PENALTY);
                    n.setAutoProposeChequeReturn(FineSettingsInitializer.DEFAULT_AUTO_PROPOSE_CHEQUE_RETURN);
                    n.setAutoProposeLatePayment(FineSettingsInitializer.DEFAULT_AUTO_PROPOSE_LATE_PAYMENT);
                    return n;
                });

        s.setFineBounceAmount(body.bounceAmount());
        s.setFineSignatureMismatchAmount(body.signatureMismatchAmount());
        s.setFineAccountClosedAmount(body.accountClosedAmount());
        s.setFineGraceDays(body.graceDays());
        s.setFinePerDayRate(body.perDayRate());
        // Only when named: the settings page shipping today sends the five amounts
        // and nothing else, and reading a missing field as "off" would silently turn
        // cheque-return proposals off for every landlord on the first save.
        if (body.bouncesBeforePenalty() != null)    s.setBouncesBeforePenalty(body.bouncesBeforePenalty());
        if (body.autoProposeChequeReturn() != null) s.setAutoProposeChequeReturn(body.autoProposeChequeReturn());
        if (body.autoProposeLatePayment() != null)  s.setAutoProposeLatePayment(body.autoProposeLatePayment());

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
                s.getFinePerDayRate(),
                s.getBouncesBeforePenalty(),
                s.getAutoProposeChequeReturn(),
                s.getAutoProposeLatePayment()
        );
    }
}
