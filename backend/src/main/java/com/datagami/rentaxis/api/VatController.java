package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.vat.TaxInvoiceDTO;
import com.datagami.rentaxis.api.dto.vat.VatTaxPointDTO;
import com.datagami.rentaxis.api.dto.vat.VatTaxPointRunResult;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.vat.TaxInvoiceService;
import com.datagami.rentaxis.core.service.vat.VatTaxPointService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * VAT per instalment (spec 2026-09-24 §1): a lease's VAT schedule, the manual
 * "run tax points to date", and the tax invoices each tax point issues.
 *
 * <p>Not {@code @Transactional}, for {@code RecognitionController}'s reason: the
 * run posts each point in a transaction of its own. Every service method it calls
 * opens its own transaction, so the Hibernate tenant filter is on for every read.</p>
 *
 * <p><b>Who reads what.</b> The schedule is part of the contract a property manager
 * runs, so they read it for their own buildings ({@code LeaseAccessPolicy}); running
 * tax points is an act on the organisation's VAT and is TENANT_ADMIN / ACCOUNTANT
 * only. A tax invoice is the renter's document too: RENTER may list and download
 * their <em>own</em> — the service checks the lease is theirs <em>and</em> that the
 * invoice is addressed to them, and answers "not found" otherwise (renter isolation
 * is P0).</p>
 */
@RestController
@RequestMapping("/api/v1")
public class VatController {

    static final String FUTURE_DATE = "Cannot post VAT tax points dated in the future";
    private static final String STAFF_READ = "hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')";
    /**
     * RENTER, not TENANT_USER (PR #348 review P3-7). {@code LeaseAccessPolicy}
     * scopes a TENANT_USER like a renter, but a tax invoice is a renter document and
     * every renter-facing document endpoint in the API ({@code /mine} here, gate
     * passes, penalties, renewals) is RENTER-only; a TENANT_USER gets 403 on all of
     * them, which {@code TaxInvoiceIT} pins.
     */
    private static final String DOCUMENT_READ =
            "hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER', 'RENTER')";

    private final VatTaxPointService vatTaxPoints;
    private final TaxInvoiceService taxInvoices;
    private final Clock clock;

    public VatController(VatTaxPointService vatTaxPoints, TaxInvoiceService taxInvoices, Clock clock) {
        this.vatTaxPoints = vatTaxPoints;
        this.taxInvoices = taxInvoices;
        this.clock = clock;
    }

    /** Tax points with status, journal number and invoice number, oldest first. */
    @GetMapping("/leases/{id}/vat-schedule")
    @PreAuthorize(STAFF_READ)
    public ResponseEntity<List<VatTaxPointDTO>> schedule(@PathVariable UUID id) {
        return ResponseEntity.ok(vatTaxPoints.scheduleFor(id));
    }

    /** The lease's tax invoices and credit notes; a renter sees only their own lease's. */
    @GetMapping("/leases/{id}/tax-invoices")
    @PreAuthorize(DOCUMENT_READ)
    public ResponseEntity<List<TaxInvoiceDTO>> leaseInvoices(@PathVariable UUID id) {
        return ResponseEntity.ok(taxInvoices.forLease(id));
    }

    /** The calling renter's tax invoices, newest first. */
    @GetMapping("/tax-invoices/mine")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<List<TaxInvoiceDTO>> mine() {
        return ResponseEntity.ok(taxInvoices.mine());
    }

    /** The PDF. Staff for the leases they may read; a renter for their own invoices only. */
    @GetMapping("/tax-invoices/{id}/pdf")
    @PreAuthorize(DOCUMENT_READ)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        TaxInvoiceService.Pdf pdf = taxInvoices.pdf(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + pdf.fileName())
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf.bytes());
    }

    /**
     * Post every PLANNED tax point dated on or before {@code to} (default today).
     * {@code dryRun=true} writes nothing and says what would post.
     */
    @PostMapping("/finance/vat/tax-points/run")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<VatTaxPointRunResult> run(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        if (TenantContextHolder.getTenantId() == null) {
            throw new BusinessRuleViolationException(RecognitionController.NO_TENANT);
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate through = to == null ? today : to;
        if (through.isAfter(today)) {
            // A tax point is an event that has happened: a due date that has arrived
            // or money that has been received. Posting one ahead of its date would
            // declare VAT in a return it does not belong to.
            throw new BusinessRuleViolationException(FUTURE_DATE);
        }
        return ResponseEntity.ok(vatTaxPoints.runTo(through, dryRun));
    }
}
