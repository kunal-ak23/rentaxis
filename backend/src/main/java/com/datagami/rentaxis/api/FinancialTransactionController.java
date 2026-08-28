package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.CreateSplitTransactionDTO;
import com.datagami.rentaxis.api.dto.PortfolioProfitLossDTO;
import com.datagami.rentaxis.api.dto.ReportDTO;
import com.datagami.rentaxis.api.dto.TrialBalanceDTO;
import com.datagami.rentaxis.api.dto.VatReturnDTO;
import com.datagami.rentaxis.core.service.FinancialTransactionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.FinancialTransaction;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance")
public class FinancialTransactionController {

    private final FinancialTransactionService service;
    private final UserPropertyAssignmentRepository assignmentRepository;

    public FinancialTransactionController(FinancialTransactionService service,
            UserPropertyAssignmentRepository assignmentRepository) {
        this.service = service;
        this.assignmentRepository = assignmentRepository;
    }

    @GetMapping("/transactions")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<List<FinancialTransaction>> getTransactions(
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) UUID unitId,
            @RequestParam(required = false) AccountType accountType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(service.getTransactions(propertyId, unitId, accountType, startDate, endDate));
    }

    @PostMapping("/transactions")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<FinancialTransaction> createTransaction(@RequestBody FinancialTransaction txn) {
        return ResponseEntity.ok(service.createTransaction(txn));
    }

    @PostMapping("/transactions/split")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<FinancialTransaction> createSplitTransaction(@RequestBody @Valid CreateSplitTransactionDTO dto) {
        return ResponseEntity.ok(service.createSplitTransaction(dto));
    }

    @GetMapping("/transactions/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<FinancialTransaction> getTransactionById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getTransactionWithChildren(id));
    }

    @GetMapping("/reports/property/{propertyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<ReportDTO> getPropertyReport(
            @PathVariable UUID propertyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        requireAssignedProperty(propertyId);
        return ResponseEntity.ok(service.getPropertyReport(propertyId, startDate, endDate));
    }

    @GetMapping("/reports/unit/{unitId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<ReportDTO> getUnitReport(
            @PathVariable UUID unitId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(service.getUnitReport(unitId, startDate, endDate));
    }

    @GetMapping("/reports/trial-balance")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<TrialBalanceDTO> getTrialBalance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(service.getTrialBalance(startDate, endDate));
    }

    @GetMapping("/reports/vat-return")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<VatReturnDTO> getVatReturn(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(service.getVatReturn(startDate, endDate));
    }

    @GetMapping("/ledger/vendor/{vendorId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<List<FinancialTransaction>> getVendorLedger(
            @PathVariable UUID vendorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(service.getVendorLedger(vendorId, startDate, endDate));
    }

    @GetMapping("/reports/organisation")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<ReportDTO> getOrganisationReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(service.getOrganisationReport(startDate, endDate));
    }

    @GetMapping("/reports/portfolio-profit-loss")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<PortfolioProfitLossDTO> getPortfolioProfitLoss(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context on this request");
        }
        List<UUID> authorizedPropertyIds = isPropertyManager()
                ? assignmentRepository.findByUserId(currentUserId()).stream()
                        .map(assignment -> assignment.getPropertyId()).distinct().toList()
                : null;
        return ResponseEntity.ok(service.getPortfolioProfitLoss(
                tenantId, authorizedPropertyIds, startDate, endDate));
    }

    private void requireAssignedProperty(UUID propertyId) {
        if (isPropertyManager()
                && !assignmentRepository.existsByUserIdAndPropertyId(currentUserId(), propertyId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "You are not assigned to this property");
        }
    }

    private boolean isPropertyManager() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PROPERTY_MANAGER".equals(a.getAuthority()));
    }

    private UUID currentUserId() {
        return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
