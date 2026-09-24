package com.datagami.rentaxis.core.service.vat;

import com.datagami.rentaxis.api.dto.vat.TaxInvoiceDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.lease.LeaseVat;
import com.datagami.rentaxis.core.service.ledger.EntryNumberService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.TaxInvoice;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.VatTaxPoint;
import com.datagami.rentaxis.domain.entity.enums.TaxInvoiceKind;
import com.datagami.rentaxis.domain.entity.enums.VatTaxPointKind;
import com.datagami.rentaxis.domain.entity.enums.VatTaxPointStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.TaxInvoiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Issuing and reading the tax invoice each VAT tax point produces (product
 * decision 2026-09-24; spec §1).
 *
 * <p><b>Issued inside the posting transaction.</b> {@link #issueFor} is
 * {@code MANDATORY}: it runs in the very transaction that posts the {@code VTP} (or
 * the termination's {@code TCR}), so a document never exists for a tax point that
 * rolled back and a posted tax point never lacks its document. The number comes
 * from the same row-locked per-tenant, per-series, per-fiscal-year counter the
 * journal uses ({@code EntryNumberService}), and a rolled-back post releases it —
 * so {@code TI-26/1, TI-26/2, …} has no gaps and no duplicates even when two tax
 * points post at the same moment (the second waits on the counter's row lock).</p>
 *
 * <p><b>Series.</b> {@code TI} for a tax invoice, {@code TCN} for a tax credit
 * note (a termination that hands back VAT already declared). Each runs its own
 * sequence, numbered by the fiscal year of the issue date, which is the tax point
 * date.</p>
 *
 * <p><b>Reads are scoped twice.</b> The Hibernate tenant filter (every read runs in
 * a transaction) keeps another organisation's invoice out; {@link LeaseAccessPolicy}
 * keeps a property manager to their buildings and a renter to their own lease; and a
 * renter must additionally be the invoice's addressee. A caller who fails any of the
 * three gets "not found", never "forbidden" — an invoice id must not confirm that the
 * invoice exists.</p>
 */
@Service
public class TaxInvoiceService {

    static final String INVOICE_SERIES = "TI";
    static final String CREDIT_NOTE_SERIES = "TCN";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);

    private final TaxInvoiceRepository invoices;
    private final LeaseRepository leases;
    private final ChequeRepository cheques;
    private final LandlordOrgRepository orgs;
    private final EntryNumberService numbers;
    private final LeaseAccessPolicy leaseAccessPolicy;
    private final TaxInvoicePdfRenderer renderer;

    public TaxInvoiceService(TaxInvoiceRepository invoices, LeaseRepository leases,
                             ChequeRepository cheques, LandlordOrgRepository orgs, EntryNumberService numbers,
                             LeaseAccessPolicy leaseAccessPolicy, TaxInvoicePdfRenderer renderer) {
        this.invoices = invoices;
        this.leases = leases;
        this.cheques = cheques;
        this.orgs = orgs;
        this.numbers = numbers;
        this.leaseAccessPolicy = leaseAccessPolicy;
        this.renderer = renderer;
    }

    // ------------------------------------------------------------------
    // issuing
    // ------------------------------------------------------------------

    /**
     * The tax invoice (VAT ≥ 0) or credit note (VAT < 0) for a POSTED tax point.
     * Idempotent per tax point — {@code uq_tax_invoices_tax_point} says so in the
     * database too.
     *
     * @param cheque the instalment, or null for a termination adjustment
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TaxInvoice issueFor(VatTaxPoint point, Lease lease, Cheque cheque) {
        if (point.getStatus() != VatTaxPointStatus.POSTED) {
            throw new IllegalStateException("Tax point " + point.getId() + " is " + point.getStatus()
                    + "; only a posted tax point issues a tax invoice");
        }
        TaxInvoice existing = invoices.findByTaxPointId(point.getId()).orElse(null);
        if (existing != null) return existing;

        LandlordOrg org = orgs.findById(lease.getTenantId()).orElse(null);
        String trn = org == null ? null : org.getTrn();
        if (trn == null || trn.isBlank()) {
            // Cleared after the lease posted: the TRN it posted under still identifies
            // the supplier, and a receipt or a termination must not fail on it.
            trn = lease.getVatTrn();
        }
        if (trn == null || trn.isBlank()) {
            throw new BusinessRuleViolationException("A tax invoice needs the organisation's TRN, and none is set."
                    + " Add the TRN to the organisation's details, then post the VAT tax points again.");
        }
        boolean credit = point.getVatAmount().signum() < 0;
        TaxInvoice inv = new TaxInvoice();
        inv.setTenantId(lease.getTenantId());
        inv.setKind(credit ? TaxInvoiceKind.CREDIT_NOTE : TaxInvoiceKind.TAX_INVOICE);
        inv.setInvoiceNumber(numbers.nextDocumentNumber(credit ? CREDIT_NOTE_SERIES : INVOICE_SERIES,
                point.getTaxPointDate()));
        inv.setTaxPointId(point.getId());
        inv.setJournalId(point.getJournalId());
        inv.setLeaseId(lease.getId());
        Renter renter = lease.getRenter();
        Unit unit = lease.getUnit();
        Property property = unit == null ? null : unit.getProperty();
        inv.setRenterId(renter == null ? null : renter.getId());
        inv.setUnitId(unit == null ? null : unit.getId());
        inv.setPropertyId(property == null ? null : property.getId());
        inv.setChequeId(point.getChequeId());
        inv.setIssueDate(point.getTaxPointDate());
        inv.setSupplierName(org == null ? "" : org.getName());
        inv.setSupplierTrn(trn.trim());
        inv.setSupplierAddress(org == null ? null : org.getAddress());
        inv.setCustomerName(renter == null ? null : renter.getNameEn());
        inv.setCustomerNameAr(renter == null ? null : renter.getNameAr());
        // The renter record carries no TRN today; the column is there for when it does.
        inv.setCustomerTrn(null);
        inv.setPropertyName(property == null ? null : property.getNameEn());
        inv.setUnitNumber(unit == null ? null : unit.getUnitNumber());

        LocalDate[] period = periodOf(point, lease, cheque);
        inv.setPeriodStart(period[0]);
        inv.setPeriodEnd(period[1]);
        inv.setDescription(describe(point, cheque, period));
        if (credit) inv.setReferenceNote(referencesFor(point, lease));

        BigDecimal taxable = point.getTaxableAmount().abs();
        BigDecimal vat = point.getVatAmount().abs();
        inv.setTaxableAmount(taxable);
        inv.setVatRate(LeaseVat.RATE);
        inv.setVatAmount(vat);
        inv.setTotalAmount(taxable.add(vat));
        return invoices.save(inv);
    }

    /**
     * The period an instalment pays for: from its due date (never before the
     * tenancy starts) to the day before the next VAT-bearing instalment falls due,
     * the last one running to the end of the term. A termination adjustment covers
     * the earned days (start → T) when it declares VAT and the days no longer
     * supplied (T+1 → the old end) when it credits VAT back.
     */
    private LocalDate[] periodOf(VatTaxPoint point, Lease lease, Cheque cheque) {
        LocalDate start = lease.getStartDate();
        LocalDate end = lease.getEndDate();
        if (point.getKind() == VatTaxPointKind.TERMINATION_ADJUSTMENT) {
            LocalDate t = point.getTaxPointDate();
            return point.getVatAmount().signum() >= 0
                    ? new LocalDate[]{start, t}
                    : new LocalDate[]{t.plusDays(1), end};
        }
        if (cheque == null || cheque.getChequeDate() == null) return new LocalDate[]{start, end};
        LocalDate from = start != null && cheque.getChequeDate().isBefore(start) ? start : cheque.getChequeDate();
        LocalDate next = cheques.findByLease_IdOrderBySeqNoAsc(lease.getId()).stream()
                .filter(c -> c.getVatAmount() != null && c.getVatAmount().signum() > 0)
                .map(Cheque::getChequeDate)
                .filter(d -> d != null && d.isAfter(cheque.getChequeDate()))
                .min(Comparator.naturalOrder())
                .orElse(null);
        LocalDate to = next == null ? end : next.minusDays(1);
        if (end != null && to != null && to.isAfter(end)) to = end;
        if (to != null && to.isBefore(from)) to = from;
        return new LocalDate[]{from, to};
    }

    /**
     * What a credit note adjusts (Executive Regulation Art. 60; review P3-6): the
     * tax invoices already issued on this lease's instalments whose period runs past
     * the termination date — the rent the credit note says was not supplied. Every
     * instalment invoice of the lease when none does (a credit note must still name
     * what it corrects).
     */
    private String referencesFor(VatTaxPoint point, Lease lease) {
        LocalDate t = point.getTaxPointDate();
        List<TaxInvoice> issued = invoices.findByLeaseIdOrderByIssueDateAscCreatedAtAsc(lease.getId()).stream()
                .filter(i -> i.getKind() == TaxInvoiceKind.TAX_INVOICE && i.getChequeId() != null)
                .toList();
        List<TaxInvoice> covering = issued.stream()
                .filter(i -> i.getPeriodEnd() == null || i.getPeriodEnd().isAfter(t))
                .toList();
        List<TaxInvoice> named = covering.isEmpty() ? issued : covering;
        if (named.isEmpty()) return null;
        return named.stream()
                .map(i -> i.getInvoiceNumber() + " (" + DAY.format(i.getIssueDate()) + ")")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String describe(VatTaxPoint point, Cheque cheque, LocalDate[] period) {
        String span = period[0] == null || period[1] == null ? ""
                : " (" + DAY.format(period[0]) + " – " + DAY.format(period[1]) + ")";
        if (point.getKind() == VatTaxPointKind.TERMINATION_ADJUSTMENT) {
            return (point.getVatAmount().signum() >= 0
                    ? "VAT on rent earned to termination not declared by an instalment"
                    : "VAT credited back on rent not supplied after termination") + span;
        }
        String what = cheque == null ? "Instalment"
                : (cheque.getNarration() != null && !cheque.getNarration().isBlank()
                        ? cheque.getNarration() : "Instalment " + cheque.getSeqNo());
        return what + span;
    }

    // ------------------------------------------------------------------
    // reading
    // ------------------------------------------------------------------

    /** Every invoice and credit note on a lease, oldest first. Readable by whoever may read the lease. */
    @Transactional(readOnly = true)
    public List<TaxInvoiceDTO> forLease(UUID leaseId) {
        Lease lease = leases.findByIdScopedToTenant(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        leaseAccessPolicy.requireReadable(lease);
        UUID renterId = leaseAccessPolicy.callerRenterId();
        return invoices.findByLeaseIdOrderByIssueDateAscCreatedAtAsc(leaseId).stream()
                .filter(i -> !leaseAccessPolicy.callerIsRenter() || (renterId != null && renterId.equals(i.getRenterId())))
                .map(TaxInvoiceService::toDto).toList();
    }

    /** The calling renter's own invoices, newest first. Empty for a caller who is not a renter. */
    @Transactional(readOnly = true)
    public List<TaxInvoiceDTO> mine() {
        UUID renterId = leaseAccessPolicy.callerRenterId();
        if (renterId == null) return List.of();
        requireTenant();
        return invoices.findByRenterIdOrderByIssueDateDescCreatedAtDesc(renterId).stream()
                .map(TaxInvoiceService::toDto).toList();
    }

    /** The document itself, after the three scoping checks in the class note. */
    @Transactional(readOnly = true)
    public Pdf pdf(UUID invoiceId) {
        TaxInvoice inv = invoices.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("Tax invoice not found"));
        UUID tenantId = requireTenant();
        if (!tenantId.equals(inv.getTenantId())) throw new NotFoundException("Tax invoice not found");
        Lease lease = leases.findByIdScopedToTenant(inv.getLeaseId())
                .orElseThrow(() -> new NotFoundException("Tax invoice not found"));
        if (!leaseAccessPolicy.canRead(lease)) throw new NotFoundException("Tax invoice not found");
        if (leaseAccessPolicy.callerIsRenter()) {
            UUID renterId = leaseAccessPolicy.callerRenterId();
            if (renterId == null || !renterId.equals(inv.getRenterId())) {
                throw new NotFoundException("Tax invoice not found");
            }
        }
        LandlordOrg org = orgs.findById(tenantId).orElse(null);
        return new Pdf(inv.getInvoiceNumber().replace('/', '-') + ".pdf", renderer.render(inv, org, tenantId));
    }

    /** A rendered document and the file name to offer it under. */
    public record Pdf(String fileName, byte[] bytes) {
    }

    /** The invoice issued on a tax point, if any — for the lease's VAT schedule. */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<TaxInvoice> forPoints(List<UUID> pointIds) {
        if (pointIds.isEmpty()) return List.of();
        return invoices.findByTaxPointIdIn(pointIds);
    }

    static TaxInvoiceDTO toDto(TaxInvoice i) {
        return new TaxInvoiceDTO(i.getId(), i.getInvoiceNumber(), i.getKind(), i.getIssueDate(),
                i.getPeriodStart(), i.getPeriodEnd(), i.getLeaseId(), i.getChequeId(), i.getPropertyName(),
                i.getUnitNumber(), i.getCustomerName(), i.getTaxableAmount(), i.getVatRate(), i.getVatAmount(),
                i.getTotalAmount());
    }

    private static UUID requireTenant() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) throw new NotFoundException("Tax invoice not found");
        return tenantId;
    }
}
