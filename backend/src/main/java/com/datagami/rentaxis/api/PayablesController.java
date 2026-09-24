package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.payables.*;
import com.datagami.rentaxis.api.dto.voucher.AllocationDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.payables.ApOpeningItemService;
import com.datagami.rentaxis.core.service.payables.PayablesService;
import com.datagami.rentaxis.core.service.payables.SupplierStatementPdfRenderer;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.voucher.VoucherAllocationService;
import com.datagami.rentaxis.core.service.voucher.VoucherService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Vendor;
import com.datagami.rentaxis.domain.entity.Voucher;
import com.datagami.rentaxis.domain.entity.VoucherAllocation;
import com.datagami.rentaxis.domain.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Supplier AP (finance-ops spec §2, PR 3a): allocations, cut-over opening items,
 * a vendor's open items and its statement of account. SUPER_ADMIN, TENANT_ADMIN
 * and ACCOUNTANT; a SUPER_ADMIN must have an organisation selected.
 *
 * <p>No entity is bound from a request body: every write arrives as a DTO and is
 * applied by a service that re-reads each id it names inside the tenant.</p>
 */
@RestController
@RequestMapping("/api/v1/finance")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')")
public class PayablesController {

    static final String NO_TENANT = "Select an organisation first";

    private final VoucherAllocationService allocationService;
    private final VoucherService vouchers;
    private final ApOpeningItemService openingItems;
    private final PayablesService payables;
    private final VendorService vendors;
    private final LedgerQueryService ledger;
    private final SupplierStatementPdfRenderer pdf;
    private final UserRepository users;

    public PayablesController(VoucherAllocationService allocationService, VoucherService vouchers,
                              ApOpeningItemService openingItems, PayablesService payables, VendorService vendors,
                              LedgerQueryService ledger, SupplierStatementPdfRenderer pdf, UserRepository users) {
        this.allocationService = allocationService;
        this.vouchers = vouchers;
        this.openingItems = openingItems;
        this.payables = payables;
        this.vendors = vendors;
        this.ledger = ledger;
        this.pdf = pdf;
        this.users = users;
    }

    // ---- allocations

    /** Apply (part of) a posted payment to an invoice or opening item. No journal. */
    @PostMapping("/voucher-allocations")
    public ResponseEntity<AllocationDTO> allocate(@Valid @RequestBody AllocateRequestDTO body) {
        requireTenantSelected();
        VoucherAllocation a = allocationService.allocate(body.paymentId(), body.invoiceId(), body.openingItemId(),
                body.amount(), body.allocatedOn());
        return ResponseEntity.status(HttpStatus.CREATED).body(dto(a));
    }

    /** Release, with a reason (body or {@code ?reason=}). Refused for an allocation dated in a locked period. */
    @DeleteMapping("/voucher-allocations/{id}")
    public ResponseEntity<AllocationDTO> release(@PathVariable UUID id,
                                                 @RequestParam(required = false) String reason,
                                                 @RequestBody(required = false) ReleaseAllocationDTO body) {
        requireTenantSelected();
        String why = body != null && body.reason() != null ? body.reason() : reason;
        return ResponseEntity.ok(dto(allocationService.release(id, why)));
    }

    // ---- opening items

    @GetMapping("/ap-opening-items")
    public ResponseEntity<ApOpeningSummaryDTO> openingItems(@RequestParam(required = false) UUID vendorId) {
        requireTenantSelected();
        return ResponseEntity.ok(openingItems.summary(vendorId));
    }

    @PostMapping("/ap-opening-items")
    public ResponseEntity<ApOpeningItemDTO> createOpeningItem(@Valid @RequestBody ApOpeningItemInputDTO body) {
        requireTenantSelected();
        return ResponseEntity.status(HttpStatus.CREATED).body(openingItems.create(body));
    }

    @PutMapping("/ap-opening-items/{id}")
    public ResponseEntity<ApOpeningItemDTO> updateOpeningItem(@PathVariable UUID id,
                                                              @Valid @RequestBody ApOpeningItemInputDTO body) {
        requireTenantSelected();
        return ResponseEntity.ok(openingItems.update(id, body));
    }

    @DeleteMapping("/ap-opening-items/{id}")
    public ResponseEntity<Void> deleteOpeningItem(@PathVariable UUID id) {
        requireTenantSelected();
        openingItems.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---- one vendor

    /** Every invoice of the vendor, settled or not, with what is left on each — the vendor's Open items tab. */
    @GetMapping("/vendors/{vendorId}/open-items")
    public ResponseEntity<List<OpenItemDTO>> vendorItems(@PathVariable UUID vendorId) {
        requireTenantSelected();
        return ResponseEntity.ok(payables.vendorItems(vendorId));
    }

    /** Statement of account (spec §2, S11): the vendor ledger for the period and open items as of {@code to}. */
    @GetMapping("/vendors/{vendorId}/statement.pdf")
    public ResponseEntity<byte[]> statement(@PathVariable UUID vendorId,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(defaultValue = "en") String lang) {
        requireTenantSelected();
        if (to.isBefore(from)) throw new BusinessRuleViolationException("'to' must not be before 'from'");
        if (from.plusYears(5).isBefore(to)) throw new BusinessRuleViolationException("A statement covers at most five years");
        Vendor v = vendors.getVendorById(vendorId);
        PayablesService.VendorPosition position = payables.vendorPosition(vendorId, to);
        SupplierStatementPdfRenderer.Statement s = new SupplierStatementPdfRenderer.Statement(
                v.getNameEn(), v.getNameAr(), v.getTrn(), from, to, ledger.vendorLedger(vendorId, from, to),
                payables.ledgerBalance(vendorId, to), position.items(), position.advances(), Instant.now(), callerName());
        String l = "ar".equals(lang) ? "ar" : "en";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"supplier-statement-" + from + "-" + to + "-" + l + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf.render(s, l));
    }

    // ---- plumbing

    private AllocationDTO dto(VoucherAllocation a) {
        Voucher pay = vouchers.get(a.getPaymentVoucherId());
        Voucher inv = a.getInvoiceVoucherId() == null ? null : vouchers.get(a.getInvoiceVoucherId());
        return AllocationDTO.of(a, pay.getVoucherNumber(),
                inv != null ? inv.getInvoiceNumber() : payables.openingItemNumber(a.getOpeningItemId()),
                inv == null ? null : inv.getVoucherNumber());
    }

    private String callerName() {
        try {
            return users.findById(CallerIdentity.callerId())
                    .map(u -> u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getEmail())
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void requireTenantSelected() {
        if (TenantContextHolder.getTenantId() == null) throw new BusinessRuleViolationException(NO_TENANT);
    }
}
