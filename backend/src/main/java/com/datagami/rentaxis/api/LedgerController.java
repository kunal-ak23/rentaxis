package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.ledger.AccountLedgerDTO;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService.LedgerFilter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read-only ledger reporting (spec §6). The write side is PostingService alone. */
@RestController
@RequestMapping("/api/v1/finance")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
public class LedgerController {

    private final LedgerQueryService service;

    public LedgerController(LedgerQueryService service) { this.service = service; }

    @GetMapping("/ledger")
    public ResponseEntity<List<AccountLedgerDTO>> generalLedger(
            @RequestParam(required = false) List<UUID> accountIds,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID propertyId, @RequestParam(required = false) UUID unitId,
            @RequestParam(required = false) UUID leaseId, @RequestParam(required = false) UUID renterId,
            @RequestParam(defaultValue = "false") boolean effectiveProperty) {
        return ResponseEntity.ok(service.generalLedger(accountIds,
                new LedgerFilter(from, to, propertyId, unitId, leaseId, renterId, effectiveProperty)));
    }

    @GetMapping("/ledger/account/{accountId}")
    public ResponseEntity<AccountLedgerDTO> accountLedger(@PathVariable UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID propertyId, @RequestParam(required = false) UUID unitId,
            @RequestParam(required = false) UUID leaseId, @RequestParam(required = false) UUID renterId) {
        return ResponseEntity.ok(service.accountLedger(accountId, new LedgerFilter(from, to, propertyId, unitId, leaseId, renterId)));
    }

    @GetMapping("/ledger/renter/{renterId}")
    public ResponseEntity<List<AccountLedgerDTO>> renterLedger(@PathVariable UUID renterId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.renterLedger(renterId, from, to));
    }

    @GetMapping("/ledger/vendor/{vendorId}")
    public ResponseEntity<AccountLedgerDTO> vendorLedger(@PathVariable UUID vendorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.vendorLedger(vendorId, from, to));
    }

    @GetMapping("/trial-balance")
    public ResponseEntity<List<TrialBalanceRowDTO>> trialBalance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) UUID propertyId) {
        return ResponseEntity.ok(service.trialBalance(asOf, propertyId));
    }
}
