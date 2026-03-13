package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.ReportDTO;
import com.datagami.rentaxis.api.dto.TrialBalanceDTO;
import com.datagami.rentaxis.api.dto.VatReturnDTO;
import com.datagami.rentaxis.core.service.FinancialTransactionService;
import com.datagami.rentaxis.domain.entity.FinancialTransaction;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
public class FinancialTransactionController {

    private final FinancialTransactionService service;

    public FinancialTransactionController(FinancialTransactionService service) {
        this.service = service;
    }

    @GetMapping("/transactions")
    public ResponseEntity<List<FinancialTransaction>> getTransactions(
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) UUID unitId,
            @RequestParam(required = false) AccountType accountType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(service.getTransactions(propertyId, unitId, accountType, startDate, endDate));
    }

    @PostMapping("/transactions")
    public ResponseEntity<FinancialTransaction> createTransaction(@RequestBody FinancialTransaction txn) {
        return ResponseEntity.ok(service.createTransaction(txn));
    }

    @GetMapping("/transactions/{id}")
    public ResponseEntity<FinancialTransaction> getTransactionById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getTransactionById(id));
    }

    @GetMapping("/reports/property/{propertyId}")
    public ResponseEntity<ReportDTO> getPropertyReport(
            @PathVariable UUID propertyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(service.getPropertyReport(propertyId, startDate, endDate));
    }

    @GetMapping("/reports/unit/{unitId}")
    public ResponseEntity<ReportDTO> getUnitReport(
            @PathVariable UUID unitId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(service.getUnitReport(unitId, startDate, endDate));
    }

    @GetMapping("/reports/trial-balance")
    public ResponseEntity<TrialBalanceDTO> getTrialBalance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(service.getTrialBalance(startDate, endDate));
    }

    @GetMapping("/reports/vat-return")
    public ResponseEntity<VatReturnDTO> getVatReturn(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(service.getVatReturn(startDate, endDate));
    }

    @GetMapping("/ledger/vendor/{vendorId}")
    public ResponseEntity<List<FinancialTransaction>> getVendorLedger(
            @PathVariable UUID vendorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(service.getVendorLedger(vendorId, startDate, endDate));
    }

    @GetMapping("/reports/organisation")
    public ResponseEntity<ReportDTO> getOrganisationReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(service.getOrganisationReport(startDate, endDate));
    }
}
