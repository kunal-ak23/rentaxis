package com.datagami.rentaxis.core.service.vat;

import com.datagami.rentaxis.api.dto.vat.VatTaxPointDTO;
import com.datagami.rentaxis.api.dto.vat.VatTaxPointRunResult;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.TaxInvoice;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.VatTaxPoint;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.VatTaxPointKind;
import com.datagami.rentaxis.domain.entity.enums.VatTaxPointStatus;
import com.datagami.rentaxis.domain.entity.enums.VatTiming;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import com.datagami.rentaxis.domain.repository.VatTaxPointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The VAT tax point schedule of every INSTALMENT lease (spec 2026-09-24 §1).
 *
 * <p><b>The rule.</b> UAE VAT Decree-Law Art. 26 (periodic supplies): an
 * instalment's date of supply is the earliest of the tax invoice, the payment due
 * date and receipt of payment. The contract is not a tax invoice, so
 * <b>tax point = min(cheque date, cleared date)</b>. A PDC handed over at signing is
 * not payment (product decision 2026-09-24), and a bounce does not move the tax point
 * — the due date passed, so the VAT is due.</p>
 *
 * <p><b>Shape.</b> The schedule is built when rows join the books (post, extension,
 * addendum, amendment) — one PLANNED point per VAT-bearing row. {@link
 * VatTaxPointJob} posts every PLANNED point whose date has come, each in its own
 * transaction through {@link VatTaxPointPoster}; an early receipt and a
 * termination post theirs in the caller's transaction. A cut-over contract has no
 * schedule at all: PACT declared its VAT on the contract date, so the import posts it
 * on the CONTRACT model. Posting is the
 * only way a point's VAT reaches {@code OUTPUT_VAT}, and {@code PostingService} is
 * the only way anything reaches the ledger.</p>
 *
 * <p><b>Tenant isolation.</b> Every read is inside a transaction, so {@code
 * TenantAspect} has the Hibernate filter on; the job's candidate query also names
 * the tenant explicitly, and the poster re-checks the point's tenant under the lock.
 * A lease id arriving from a request is resolved through
 * {@code findByIdScopedToTenant} and the lease access policy.</p>
 */
@Service
public class VatTaxPointService {

    private static final Logger log = LoggerFactory.getLogger(VatTaxPointService.class);

    /** Rows that are, or were, a real instalment of the contract — what gets a tax point. */
    private static final Set<ChequeStatus> SCHEDULABLE = EnumSet.of(ChequeStatus.REGISTERED, ChequeStatus.DEPOSITED,
            ChequeStatus.CLEARED, ChequeStatus.ONLINE_PENDING, ChequeStatus.BOUNCED);

    /** Rows a PLANNED VAT share may be moved onto: still to be collected. */
    private static final Set<ChequeStatus> PENDING = EnumSet.of(ChequeStatus.REGISTERED, ChequeStatus.DEPOSITED,
            ChequeStatus.ONLINE_PENDING);

    private final VatTaxPointRepository points;
    private final ChequeRepository cheques;
    private final LeaseRepository leases;
    private final JournalEntryRepository journals;
    private final TenantFiscalSettingsRepository fiscalSettings;
    private final VatTaxPointPoster poster;
    private final TaxInvoiceService taxInvoices;
    private final LeaseAccessPolicy leaseAccessPolicy;
    private final TransactionTemplate readTx;
    private final jakarta.persistence.EntityManager entityManager;
    private final com.datagami.rentaxis.domain.repository.LeaseLineRepository leaseLines;

    public VatTaxPointService(VatTaxPointRepository points, ChequeRepository cheques, LeaseRepository leases,
                              JournalEntryRepository journals, TenantFiscalSettingsRepository fiscalSettings,
                              VatTaxPointPoster poster, TaxInvoiceService taxInvoices,
                              LeaseAccessPolicy leaseAccessPolicy, PlatformTransactionManager transactionManager,
                              jakarta.persistence.EntityManager entityManager,
                              com.datagami.rentaxis.domain.repository.LeaseLineRepository leaseLines) {
        this.entityManager = entityManager;
        this.leaseLines = leaseLines;
        this.points = points;
        this.cheques = cheques;
        this.leases = leases;
        this.journals = journals;
        this.fiscalSettings = fiscalSettings;
        this.poster = poster;
        this.taxInvoices = taxInvoices;
        this.leaseAccessPolicy = leaseAccessPolicy;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    /** min(cheque date, cleared date) — the rule in the class note. */
    public static LocalDate taxPointDate(Cheque c) {
        LocalDate due = c.getChequeDate();
        LocalDate cleared = c.getClearedAt();
        if (due == null) return cleared;
        if (cleared == null) return due;
        return cleared.isBefore(due) ? cleared : due;
    }

    /**
     * The point claimed {@code FOR UPDATE} and re-read (review P2-1). Every writer
     * goes through this before it looks at the status, so a point the job has just
     * posted is seen as POSTED, never overwritten from a stale PLANNED read; the
     * {@code @Version} column is the backstop.
     */
    private VatTaxPoint lock(VatTaxPoint p) {
        entityManager.flush();
        entityManager.refresh(p, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return p;
    }

    /** Claims every point in id order (see {@link #lock}) and returns them in the order given. */
    private List<VatTaxPoint> lockAllInIdOrder(List<VatTaxPoint> ps) {
        ps.stream().sorted(java.util.Comparator.comparing(VatTaxPoint::getId)).forEach(this::lock);
        return ps;
    }

    /**
     * {@code books_locked_through}, read {@code FOR SHARE}: a writer about to create
     * or move a tax point holds the settings row against {@code lockThrough}, which
     * takes it {@code FOR UPDATE} — so the lock and the point cannot pass each other
     * (review P3-3). Null when the tenant has no settings row or no lock.
     */
    private LocalDate lockedThroughShared(UUID tenantId) {
        if (tenantId == null) return null;
        // A tenant's first lockThrough creates the row in its own transaction; with
        // no row here there would be nothing to hold (re-review N6). The insert waits
        // on an uncommitted one, which is the ordering wanted.
        fiscalSettings.insertDefaultIfAbsent(tenantId);
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager.createNativeQuery(
                        "select books_locked_through from tenant_fiscal_settings where tenant_id = ?1 for share")
                .setParameter(1, tenantId)
                .getResultList();
        if (rows.isEmpty() || rows.get(0) == null) return null;
        Object v = rows.get(0);
        return v instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) v;
    }

    // ------------------------------------------------------------------
    // building
    // ------------------------------------------------------------------

    /**
     * One PLANNED point for every VAT-bearing row of the lease that has none.
     * Idempotent — a row with a live point is skipped — which is what lets every
     * event that adds rows (post, extension, addendum, amendment) call the same
     * method. A legacy CONTRACT lease has no schedule.
     */
    @Transactional
    public void buildForLease(UUID leaseId) {
        Lease lease = lease(leaseId);
        if (lease.getVatTiming() != VatTiming.INSTALMENT) return;
        // A lease whose lines charge no VAT has nothing to declare, whatever its rows
        // say (review P2-4): the post refuses such a grid, and this is the backstop.
        BigDecimal charged = BigDecimal.ZERO;
        for (var line : leaseLines.findByLease_IdOrderBySeqNoAsc(leaseId)) {
            charged = charged.add(com.datagami.rentaxis.core.service.lease.LeaseVat.vatOf(line));
        }
        if (charged.signum() == 0) return;
        // Shared lock on the settings row: lockThrough takes it exclusively, so a
        // lock cannot move over a point this is creating (review P3-3).
        LocalDate locked = lockedThroughShared(lease.getTenantId());
        boolean created = false;
        for (Cheque c : cheques.findByLease_IdOrderBySeqNoAsc(leaseId)) {
            if (c.getVatAmount() == null || c.getVatAmount().signum() <= 0) continue;
            if (!SCHEDULABLE.contains(c.getStatus())) continue;
            if (points.findLiveByChequeId(c.getId()).isPresent()) continue;
            VatTaxPoint p = plannedFor(lease, c);
            if (locked != null && !p.getTaxPointDate().isAfter(locked)) {
                throw new BusinessRuleViolationException("Instalment " + label(c) + "'s VAT tax point ("
                        + p.getTaxPointDate() + ") falls in a locked period: books are locked through " + locked + ".");
            }
            points.save(p);
            created = true;
        }
        if (created && (lease.getVatTrn() == null || lease.getVatTrn().isBlank())) {
            // The first tax point this lease gets, whichever door made it (a post, or
            // an addendum on a lease that posted without VAT): its invoices fall back
            // to this TRN if the organisation's is cleared later (re-review N4).
            String trn = taxInvoices.currentTrn(lease.getTenantId());
            if (trn != null) {
                lease.setVatTrn(trn);
                leases.save(lease);
            }
        }
    }

    private VatTaxPoint plannedFor(Lease lease, Cheque c) {
        VatTaxPoint p = new VatTaxPoint();
        p.setTenantId(lease.getTenantId());
        p.setLeaseId(lease.getId());
        p.setChequeId(c.getId());
        stampWhere(p, lease);
        p.setKind(VatTaxPointKind.INSTALMENT);
        p.setTaxPointDate(taxPointDate(c));
        p.setTaxableAmount(nz(c.getVatTaxableAmount()));
        p.setVatAmount(c.getVatAmount());
        p.setStatus(VatTaxPointStatus.PLANNED);
        return p;
    }

    private static void stampWhere(VatTaxPoint p, Lease lease) {
        Unit unit = lease.getUnit();
        Property property = unit == null ? null : unit.getProperty();
        p.setPropertyId(property == null ? null : property.getId());
        p.setUnitId(unit == null ? null : unit.getId());
        p.setRenterId(lease.getRenter() == null ? null : lease.getRenter().getId());
        p.setEmirate(property == null || property.getEmirate() == null ? null : property.getEmirate().name());
    }

    // ------------------------------------------------------------------
    // the cheque lifecycle
    // ------------------------------------------------------------------

    /**
     * The row was received on {@code clearedOn}. Received before it fell due, the
     * receipt is the tax point: the point moves to that date and posts now, inside
     * the clearing's transaction (spec 2026-09-24 §1, "Row received early").
     * Received on or after its due date, nothing changes — the due date already was
     * the tax point, and the job posts (or has posted) it there.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onCleared(Cheque cheque, LocalDate clearedOn) {
        if (clearedOn == null) return;
        VatTaxPoint point = points.findLiveByChequeId(cheque.getId()).map(this::lock).orElse(null);
        if (point == null || point.getStatus() != VatTaxPointStatus.PLANNED) return;
        if (!clearedOn.isBefore(point.getTaxPointDate())) return;
        point.setTaxPointDate(clearedOn);
        points.saveAndFlush(point);
        poster.postJoining(point.getId());
    }

    /**
     * The date written on a REGISTERED row changed. A PLANNED point follows it; a
     * POSTED one stays where it was declared (spec: "a date change is allowed and
     * leaves the posted VTP alone"). A new date inside the period lock is refused,
     * because the job would never post it there.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onChequeDateChanged(Cheque cheque) {
        VatTaxPoint point = points.findLiveByChequeId(cheque.getId()).map(this::lock).orElse(null);
        if (point == null || point.getStatus() != VatTaxPointStatus.PLANNED) return;
        LocalDate date = taxPointDate(cheque);
        LocalDate locked = lockedThroughShared(cheque.getTenantId());
        if (locked != null && date != null && !date.isAfter(locked)) {
            throw new BusinessRuleViolationException("Instalment " + label(cheque) + "'s VAT tax point would move to "
                    + date + ", inside the locked period (books are locked through " + locked + ").");
        }
        point.setTaxPointDate(date);
        points.save(point);
    }

    /**
     * A REGISTERED row is being cancelled. Its PLANNED VAT has to go somewhere, or
     * it is stranded in the deferred account for ever: onto another pending row of
     * the same lease named by {@code moveToChequeId}, in this same request — or the
     * cancel is refused (spec 2026-09-24 §1). A row whose VAT is already declared, or
     * that carries none, cancels freely.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void beforeCancel(Cheque cancelled, UUID moveToChequeId) {
        VatTaxPoint point = points.findLiveByChequeId(cancelled.getId()).map(this::lock).orElse(null);
        if (point == null || point.getStatus() != VatTaxPointStatus.PLANNED || point.getVatAmount().signum() <= 0) {
            return;
        }
        if (moveToChequeId == null) {
            throw new BusinessRuleViolationException("Instalment " + label(cancelled) + " carries VAT of "
                    + point.getVatAmount().setScale(2, RoundingMode.HALF_UP)
                    + " that has not been declared yet. Choose another pending instalment of this lease to move"
                    + " it to, and cancel again.");
        }
        Cheque target;
        try {
            target = cheques.findByIdForUpdate(moveToChequeId).orElse(null);
        } catch (org.springframework.dao.PessimisticLockingFailureException e) {
            throw new com.datagami.rentaxis.api.exception.RowLockedException(
                    "The instalment to move the VAT to is being updated by another request. Please try again.");
        }
        target = java.util.Optional.ofNullable(target)
                .filter(c -> c.getLease() != null && c.getLease().getId().equals(cancelled.getLease().getId()))
                .filter(c -> !c.getId().equals(cancelled.getId()))
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "The instalment to move the VAT to must be another row of the same lease."));
        if (target.getRowKind() == com.datagami.rentaxis.domain.entity.enums.ChequeRowKind.DEPOSIT) {
            // A deposit is not a supply; VAT moved onto it would be a tax invoice on a
            // refundable deposit (re-review N5).
            throw new BusinessRuleViolationException("Instalment " + label(target) + " is a deposit, which carries"
                    + " no VAT; move the VAT onto a rent or fee instalment.");
        }
        if (!PENDING.contains(target.getStatus())) {
            throw new BusinessRuleViolationException("Instalment " + label(target) + " is " + target.getStatus()
                    + "; VAT can only move onto an instalment that is still to be collected.");
        }
        // The VAT would be declared on the target's tax point: one inside the locked
        // period would never post (review P2-2).
        LocalDate targetDate = taxPointDate(target);
        LocalDate locked = lockedThroughShared(cancelled.getTenantId());
        if (locked != null && targetDate != null && !targetDate.isAfter(locked)) {
            throw new BusinessRuleViolationException("Instalment " + label(target) + "'s VAT tax point (" + targetDate
                    + ") falls in a locked period: books are locked through " + locked
                    + ". Move the VAT onto an instalment dated after it.");
        }
        VatTaxPoint targetPoint = points.findLiveByChequeId(target.getId()).map(this::lock).orElse(null);
        if (targetPoint != null && targetPoint.getStatus() != VatTaxPointStatus.PLANNED) {
            throw new BusinessRuleViolationException("VAT on instalment " + label(target)
                    + " is already declared; move the VAT onto an instalment whose tax point is still to come.");
        }
        target.setVatAmount(nz(target.getVatAmount()).add(point.getVatAmount()));
        target.setVatTaxableAmount(nz(target.getVatTaxableAmount()).add(point.getTaxableAmount()));
        cheques.save(target);
        cancelled.setVatAmount(BigDecimal.ZERO);
        cancelled.setVatTaxableAmount(BigDecimal.ZERO);
        point.setStatus(VatTaxPointStatus.CANCELLED);
        points.saveAndFlush(point);
        if (targetPoint == null) {
            points.save(plannedFor(lease(cancelled.getLease().getId()), target));
        } else {
            targetPoint.setVatAmount(target.getVatAmount());
            targetPoint.setTaxableAmount(target.getVatTaxableAmount());
            points.save(targetPoint);
        }
    }

    // ------------------------------------------------------------------
    // amendment
    // ------------------------------------------------------------------

    /**
     * Refused when any instalment's VAT has been declared: "VAT already declared on
     * instalment N; use an addendum" (spec 2026-09-24 §1). Anything settled is never
     * touched and is corrected by a delta.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireNothingDeclared(UUID leaseId) {
        for (VatTaxPoint p : points.findByLeaseIdAndStatusOrderByTaxPointDateAsc(leaseId, VatTaxPointStatus.POSTED)) {
            String which = p.getChequeId() == null ? "this lease"
                    : cheques.findById(p.getChequeId()).map(VatTaxPointService::label).orElse("an instalment");
            throw new BusinessRuleViolationException("VAT already declared on instalment " + which
                    + "; use an addendum.");
        }
    }

    /** Every PLANNED point of the lease, CANCELLED — the amendment's re-post rebuilds the schedule. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelPlanned(UUID leaseId) {
        for (VatTaxPoint p : lockAllInIdOrder(
                points.findByLeaseIdAndStatusOrderByTaxPointDateAsc(leaseId, VatTaxPointStatus.PLANNED))) {
            if (p.getStatus() == VatTaxPointStatus.POSTED) {
                // The job declared it between the amendment's check and now.
                throw new BusinessRuleViolationException("VAT on " + p.getTaxPointDate()
                        + " was declared while this amendment was being made; use an addendum.");
            }
            if (p.getStatus() != VatTaxPointStatus.PLANNED) continue;
            p.setStatus(VatTaxPointStatus.CANCELLED);
            points.save(p);
        }
        points.flush();
    }

    // ------------------------------------------------------------------
    // termination
    // ------------------------------------------------------------------

    /**
     * What a termination at {@code t} does to the lease's VAT (spec 2026-09-24 §1,
     * "Termination at T"):
     *
     * @param dueByT     Σ VAT of PLANNED points dated ≤ T — posted before the TCR
     * @param pending    P: Σ VAT of PLANNED points dated > T — cancelled
     * @param pendingTaxable the net P is charged on
     * @param unearnedVat U: VAT on the unearned rent
     * @param reversedFromDeferred min(P, U): {@code Dr OUTPUT_VAT_DEFERRED / Cr RENT_RECEIVABLE}
     * @param declaredAtT P − U when P > U: {@code Dr OUTPUT_VAT_DEFERRED / Cr OUTPUT_VAT}
     * @param creditedBack U − P when U > P: {@code Dr OUTPUT_VAT / Cr RENT_RECEIVABLE}
     */
    public record TerminationVat(BigDecimal dueByT, BigDecimal pending, BigDecimal pendingTaxable,
                                 BigDecimal unearnedVat, BigDecimal reversedFromDeferred,
                                 BigDecimal declaredAtT, BigDecimal creditedBack) {
        public static TerminationVat none() {
            BigDecimal z = BigDecimal.ZERO.setScale(2);
            return new TerminationVat(z, z, z, z, z, z, z);
        }
    }

    /** The same arithmetic {@link #settleForTermination} performs, with nothing written. */
    @Transactional(readOnly = true)
    public TerminationVat previewTermination(UUID leaseId, LocalDate t, BigDecimal unearnedVat) {
        Lease lease = lease(leaseId);
        if (lease.getVatTiming() != VatTiming.INSTALMENT) return TerminationVat.none();
        BigDecimal dueByT = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        BigDecimal pendingTaxable = BigDecimal.ZERO;
        for (VatTaxPoint p : points.findByLeaseIdAndStatusOrderByTaxPointDateAsc(leaseId, VatTaxPointStatus.PLANNED)) {
            if (p.getTaxPointDate().isAfter(t)) {
                pending = pending.add(p.getVatAmount());
                pendingTaxable = pendingTaxable.add(p.getTaxableAmount());
            } else {
                dueByT = dueByT.add(p.getVatAmount());
            }
        }
        return settlement(dueByT, pending, pendingTaxable, unearnedVat);
    }

    private static TerminationVat settlement(BigDecimal dueByT, BigDecimal p, BigDecimal pTaxable, BigDecimal u) {
        BigDecimal uu = nz(u);
        BigDecimal min = p.min(uu);
        BigDecimal declared = p.compareTo(uu) > 0 ? p.subtract(uu) : BigDecimal.ZERO;
        BigDecimal credited = uu.compareTo(p) > 0 ? uu.subtract(p) : BigDecimal.ZERO;
        return new TerminationVat(s2(dueByT), s2(p), s2(pTaxable), s2(uu), s2(min), s2(declared), s2(credited));
    }

    /**
     * Steps 1 and 2 of the termination: post every PLANNED point dated ≤ T (in this
     * transaction), then cancel the rest and report their total. The caller adds the
     * settling pairs to its TCR and then records the adjustment with
     * {@link #recordTerminationAdjustment}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TerminationVat settleForTermination(Lease lease, LocalDate t, BigDecimal unearnedVat) {
        if (lease.getVatTiming() != VatTiming.INSTALMENT) return TerminationVat.none();
        BigDecimal dueByT = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        BigDecimal pendingTaxable = BigDecimal.ZERO;
        LocalDate locked = lockedThroughShared(lease.getTenantId());
        // Every PLANNED point is claimed before anything is posted, in id order —
        // the order every multi-point writer uses — so a concurrent early receipt
        // (one point, then the document counter) cannot close a cycle with this
        // (re-review N3).
        List<VatTaxPoint> planned = lockAllInIdOrder(
                points.findByLeaseIdAndStatusOrderByTaxPointDateAsc(lease.getId(), VatTaxPointStatus.PLANNED));
        for (VatTaxPoint p : planned) {
            // Posted by the job since the list was read: declared already, so it is
            // neither due at T nor pending (review P2-1).
            if (p.getStatus() != VatTaxPointStatus.PLANNED) continue;
            if (!p.getTaxPointDate().isAfter(t)) {
                if (locked != null && !p.getTaxPointDate().isAfter(locked)) {
                    // Unreachable when every door checks the lock (review P2-2); said
                    // plainly rather than as the ledger's generic refusal if not.
                    throw new BusinessRuleViolationException("VAT of " + s2(p.getVatAmount()) + " on "
                            + p.getTaxPointDate() + " has not been declared and falls in the locked period (books are"
                            + " locked through " + locked + "). It has to be declared before this lease can be"
                            + " terminated; reopen that period, run the tax points, and terminate again.");
                }
                poster.postJoining(p.getId());
                dueByT = dueByT.add(p.getVatAmount());
            } else {
                pending = pending.add(p.getVatAmount());
                pendingTaxable = pendingTaxable.add(p.getTaxableAmount());
                p.setStatus(VatTaxPointStatus.CANCELLED);
                points.save(p);
            }
        }
        points.flush();
        return settlement(dueByT, pending, pendingTaxable, unearnedVat);
    }

    /**
     * The POSTED {@code TERMINATION_ADJUSTMENT} point for the pair the TCR carried,
     * and its document: a tax invoice for VAT declared at T (P > U), a tax credit
     * note for VAT handed back (U > P). Nothing when P = U.
     *
     * @param vat     signed: + declared at T, − credited back
     * @param taxable signed like {@code vat}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordTerminationAdjustment(Lease lease, LocalDate t, BigDecimal vat, BigDecimal taxable,
                                            UUID tcrJournalId) {
        if (vat == null || vat.signum() == 0) return;
        VatTaxPoint p = new VatTaxPoint();
        p.setTenantId(lease.getTenantId());
        p.setLeaseId(lease.getId());
        stampWhere(p, lease);
        p.setKind(VatTaxPointKind.TERMINATION_ADJUSTMENT);
        p.setTaxPointDate(t);
        p.setVatAmount(vat);
        p.setTaxableAmount(taxable);
        p.setStatus(VatTaxPointStatus.POSTED);
        p.setJournalId(tcrJournalId);
        p.setPostedAt(Instant.now());
        p = points.saveAndFlush(p);
        taxInvoices.issueFor(p, lease, null);
    }

    // ------------------------------------------------------------------
    // running
    // ------------------------------------------------------------------

    /**
     * Post every PLANNED point dated ≤ {@code to} (spec 2026-09-24 §1). Points on a
     * locked date are skipped, not failed; each post has its own transaction; no
     * transaction is held across the loop — {@code RecognitionService.runTo}'s shape
     * and reasons exactly.
     */
    public VatTaxPointRunResult runTo(LocalDate to, boolean preview) {
        UUID tenantId = requireTenant();
        record Candidates(List<VatTaxPointDTO> rows, LocalDate locked) {
        }
        Candidates plan = readTx.execute(status -> new Candidates(
                toDtos(points.findByTenantIdAndStatusAndTaxPointDateLessThanEqualOrderByTaxPointDateAscCreatedAtAsc(
                        tenantId, VatTaxPointStatus.PLANNED, to)),
                booksLockedThrough(tenantId)));
        List<VatTaxPointDTO> done = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int skipped = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (VatTaxPointDTO row : plan.rows()) {
            if (plan.locked() != null && !row.taxPointDate().isAfter(plan.locked())) {
                skipped++;
                continue;
            }
            if (preview) {
                done.add(row);
                total = total.add(row.vatAmount());
                continue;
            }
            try {
                // Re-checked under the point's lock: a clerk may have moved its date
                // or cancelled it since the list was read, or a receipt posted it.
                if (poster.post(row.id(), to) == null) continue;
                done.add(row);
                total = total.add(row.vatAmount());
            } catch (RuntimeException e) {
                log.warn("VAT tax point {} ({}) could not be posted: {}", row.id(), row.taxPointDate(), e.getMessage());
                errors.add("Tax point " + row.taxPointDate() + (row.chequeNumber() == null ? "" : " (" + row.chequeNumber() + ")")
                        + ": " + e.getMessage());
            }
        }
        return new VatTaxPointRunResult(preview, preview ? 0 : done.size(), done.size(), s2(total), done, skipped,
                plan.locked(), errors);
    }

    // ------------------------------------------------------------------
    // reading
    // ------------------------------------------------------------------

    /** The lease's whole VAT schedule, every status, with journal and invoice numbers. */
    @Transactional(readOnly = true)
    public List<VatTaxPointDTO> scheduleFor(UUID leaseId) {
        Lease lease = lease(leaseId);
        leaseAccessPolicy.requireReadable(lease);
        return toDtos(points.findByLeaseIdOrderByTaxPointDateAscCreatedAtAsc(leaseId));
    }

    private List<VatTaxPointDTO> toDtos(List<VatTaxPoint> rows) {
        if (rows.isEmpty()) return List.of();
        List<UUID> journalIds = rows.stream().map(VatTaxPoint::getJournalId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> numbers = new HashMap<>();
        journals.findAllById(journalIds).forEach(j -> numbers.put(j.getId(), j.getEntryNumber()));
        Map<UUID, TaxInvoice> invoiceByPoint = new HashMap<>();
        taxInvoices.forPoints(rows.stream().map(VatTaxPoint::getId).toList())
                .forEach(i -> invoiceByPoint.put(i.getTaxPointId(), i));
        List<UUID> chequeIds = rows.stream().map(VatTaxPoint::getChequeId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, Cheque> byCheque = new HashMap<>();
        cheques.findAllById(chequeIds).forEach(c -> byCheque.put(c.getId(), c));
        List<UUID> leaseIds = rows.stream().map(VatTaxPoint::getLeaseId).distinct().toList();
        Map<UUID, Lease> byLease = new HashMap<>();
        leases.findAllWithUnitAndPropertyByIdIn(leaseIds).forEach(l -> byLease.put(l.getId(), l));
        return rows.stream().map(p -> {
            Cheque c = p.getChequeId() == null ? null : byCheque.get(p.getChequeId());
            Lease l = byLease.get(p.getLeaseId());
            Unit unit = l == null ? null : l.getUnit();
            Property property = unit == null ? null : unit.getProperty();
            TaxInvoice inv = invoiceByPoint.get(p.getId());
            return new VatTaxPointDTO(p.getId(), p.getLeaseId(), p.getChequeId(),
                    c == null ? null : c.getSeqNo(), c == null ? null : c.getChequeNumber(),
                    p.getPropertyId(), property == null ? null : property.getNameEn(),
                    unit == null ? null : unit.getUnitNumber(),
                    p.getKind(), p.getTaxPointDate(), p.getTaxableAmount(), p.getVatAmount(), p.getStatus(),
                    p.getJournalId(), p.getJournalId() == null ? null : numbers.get(p.getJournalId()),
                    inv == null ? null : inv.getId(), inv == null ? null : inv.getInvoiceNumber());
        }).toList();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Lease lease(UUID leaseId) {
        return leases.findByIdScopedToTenant(leaseId).orElseThrow(() -> new NotFoundException("Lease not found"));
    }

    private LocalDate booksLockedThrough(UUID tenantId) {
        return tenantId == null ? null
                : fiscalSettings.findById(tenantId).map(TenantFiscalSettings::getBooksLockedThrough).orElse(null);
    }

    private static UUID requireTenant() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) throw new IllegalStateException("No tenant in context");
        return tenantId;
    }

    static String label(Cheque c) {
        return c.getChequeNumber() != null && !c.getChequeNumber().isBlank()
                ? c.getChequeNumber() : "row " + c.getSeqNo();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal s2(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }
}
