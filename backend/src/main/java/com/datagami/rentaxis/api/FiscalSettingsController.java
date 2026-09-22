package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.ledger.FiscalSettingsDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The tenant's accounting calendar (spec §4.4): fiscal year start, books start
 * date and the period lock. The lock only moves forward — see
 * {@link TenantFiscalSettingsService#lockThrough}.
 */
@RestController
@RequestMapping("/api/v1/finance/fiscal-settings")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
public class FiscalSettingsController {

    private final TenantFiscalSettingsService service;

    public FiscalSettingsController(TenantFiscalSettingsService service) { this.service = service; }

    /** Both fields are optional: a PUT that names one leaves the other alone. */
    public record UpdateBody(Integer fiscalYearStartMonth, LocalDate booksStartDate) {}

    public record LockBody(LocalDate through) {}

    private static FiscalSettingsDTO dto(TenantFiscalSettings s) {
        return new FiscalSettingsDTO(s.getFiscalYearStartMonth(), s.getBooksStartDate(), s.getBooksLockedThrough());
    }

    @GetMapping
    public ResponseEntity<FiscalSettingsDTO> get() {
        return ResponseEntity.ok(dto(service.get()));
    }

    @PutMapping
    public ResponseEntity<FiscalSettingsDTO> update(@RequestBody UpdateBody body) {
        TenantFiscalSettings s = service.get();
        if (body.fiscalYearStartMonth() != null) s = service.setFiscalYearStartMonth(body.fiscalYearStartMonth());
        if (body.booksStartDate() != null) s = service.setBooksStartDate(body.booksStartDate());
        return ResponseEntity.ok(dto(s));
    }

    @PostMapping("/lock")
    public ResponseEntity<FiscalSettingsDTO> lock(@RequestBody LockBody body) {
        if (body == null || body.through() == null) throw new BusinessRuleViolationException("'through' is required");
        return ResponseEntity.ok(dto(service.lockThrough(body.through())));
    }
}
