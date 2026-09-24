package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.cutover.LeaseReverter;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PenaltyAssessment;
import com.datagami.rentaxis.domain.entity.RecognitionEntry;
import com.datagami.rentaxis.domain.entity.RentSegment;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyAssessmentStatus;
import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;
import com.datagami.rentaxis.domain.entity.enums.SegmentStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.LeaseSettlementRepository;
import com.datagami.rentaxis.domain.repository.PenaltyAssessmentRepository;
import com.datagami.rentaxis.domain.repository.RecognitionEntryRepository;
import com.datagami.rentaxis.domain.repository.RentSegmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The lease side of undoing a cut-over import batch (spec §10.3, controller ruling
 * R12) — the implementation of {@link LeaseReverter}, whose Javadoc is the
 * contract this class is written to.
 *
 * <p><b>A bean of its own rather than five more constructor arguments on
 * {@code LeaseService}.</b> The undo needs the recognition schedule, the
 * settlement, the penalty worklist and the journal it all hangs off — four modules
 * {@code LeaseService} has no other reason to know about, on a class that already
 * takes sixteen collaborators. What it does borrow is the one rule it must not
 * restate: {@code LeaseService.releaseUnitIfNoOtherLiveLease}, because a second
 * copy of "is anybody else living here" is how a flat comes to read VACANT with a
 * renter in it.</p>
 *
 * <p><b>It posts nothing and it opens no transaction.</b> Every journal of the
 * batch has already been reversed by {@code ImportBatchService.reverse} when this
 * runs, and it runs inside that method's single transaction —
 * {@code Propagation.MANDATORY} is the enforcement, not the intention: a
 * {@code REQUIRES_NEW} here would let the leases come back while the journals
 * stayed, which is the one state the books cannot explain.</p>
 */
@Component
public class ImportedLeaseReverter implements LeaseReverter {

    private static final Logger log = LoggerFactory.getLogger(ImportedLeaseReverter.class);

    /**
     * The register states a cut-over replay can produce, and therefore the only
     * ones an undo of that replay knows how to take back.
     *
     * <p>A REPLACED, CANCELLED, RETURNED or ONLINE_PENDING row is something a person
     * did afterwards — {@link #blockersAgainstRevert} says so by name rather than
     * quietly resetting it to DRAFT and orphaning whatever it produced.</p>
     */
    private static final Set<ChequeStatus> REPLAYABLE = EnumSet.of(
            ChequeStatus.DRAFT, ChequeStatus.REGISTERED, ChequeStatus.DEPOSITED,
            ChequeStatus.CLEARED, ChequeStatus.BOUNCED);

    /** A penalty that is still somebody's business: proposed, or charged and uncollected. */
    private static final Set<PenaltyAssessmentStatus> LIVE_PENALTIES = EnumSet.of(
            PenaltyAssessmentStatus.PROPOSED, PenaltyAssessmentStatus.APPROVED);

    private final LeaseRepository leases;
    private final ChequeRepository cheques;
    private final RecognitionEntryRepository recognitionEntries;
    private final RentSegmentRepository segments;
    private final LeaseSettlementRepository settlements;
    private final PenaltyAssessmentRepository penalties;
    private final JournalEntryRepository journals;
    private final LeaseService leaseService;
    private final com.datagami.rentaxis.core.service.vat.VatTaxPointService vatTaxPoints;

    public ImportedLeaseReverter(LeaseRepository leases, ChequeRepository cheques,
                                 RecognitionEntryRepository recognitionEntries, RentSegmentRepository segments,
                                 LeaseSettlementRepository settlements, PenaltyAssessmentRepository penalties,
                                 JournalEntryRepository journals, LeaseService leaseService,
                                 com.datagami.rentaxis.core.service.vat.VatTaxPointService vatTaxPoints) {
        this.leases = leases;
        this.cheques = cheques;
        this.recognitionEntries = recognitionEntries;
        this.segments = segments;
        this.settlements = settlements;
        this.penalties = penalties;
        this.journals = journals;
        this.leaseService = leaseService;
        this.vatTaxPoints = vatTaxPoints;
    }

    // ------------------------------------------------------------------
    // the undo
    // ------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revertToDraft(UUID leaseId) {
        Lease lease = ours(leaseId);
        if (lease == null) {
            // import_batch_leases has no foreign key on lease_id (changeset 88), so a
            // link can outlive its lease. The rest of the batch still has to come off.
            log.debug("Import batch reverse: lease {} no longer resolves; skipped", leaseId);
            return;
        }
        // Defence in depth, not the real gate: blockersAgainstRevert refuses the whole
        // batch up front. Reaching this means the two disagree, and the safe answer is
        // to stop rather than to erase a statement somebody drew a refund from.
        if (settlements.findByLeaseId(leaseId).isPresent()) {
            throw new BusinessRuleViolationException(
                    "Lease " + name(lease) + " has a settlement; it cannot be returned to draft");
        }

        for (Cheque c : cheques.findByLease_IdOrderBySeqNoAsc(leaseId)) {
            // Every trace of what the replay did. The journal ids in particular: the
            // entries they name have just been reversed, and a live row citing a
            // reversed journal is invisible until somebody drills into the ledger.
            c.setStatus(ChequeStatus.DRAFT);
            c.setPdrJournalId(null);
            c.setCrtJournalId(null);
            c.setCbrJournalId(null);
            c.setDepositedAt(null);
            c.setClearedAt(null);
            c.setBouncedAt(null);
            c.setReturnedAt(null);
            c.setFailureReason(null);
            c.setStatusChangedAt(null);
            c.setReplaces(null);
            c.setReplacedBy(null);
            // importedStatus and the three imported dates deliberately STAY: they are
            // what the spreadsheet asked for, not what the register did, and a re-post
            // has to replay them onto the same days. That is the whole reason Task 10
            // gave them columns of their own.
            cheques.save(c);
        }

        for (RecognitionEntry entry : recognitionEntries.findByLease_IdOrderByPeriodStartAsc(leaseId)) {
            if (entry.getStatus() == RecognitionStatus.CANCELLED) continue;
            // Cancelled, never re-reversed: the POSTED rows' CILs were reversed by
            // ImportBatchService.reverse a moment ago, and reversing them again would
            // be refused ("already reversed") — or, worse, would succeed against some
            // other entry. journalId is left pointing at the reversed CIL on purpose:
            // it is the audit trail of what was recognised and then taken back.
            entry.setStatus(RecognitionStatus.CANCELLED);
            recognitionEntries.save(entry);
        }
        for (RentSegment segment : segments.findByLease_IdOrderByFromDateAsc(leaseId)) {
            if (segment.getStatus() == SegmentStatus.CANCELLED) continue;
            segment.setStatus(SegmentStatus.CANCELLED);
            segments.save(segment);
        }
        // The VAT schedule goes the same way: the batch's VTPs were reversed with its
        // other journals, and a re-post rebuilds the schedule from the rows.
        vatTaxPoints.cancelAllForRevert(leaseId);

        LeaseStatus previous = lease.getStatus();
        lease.setStatus(LeaseStatus.DRAFT);
        lease.setPostingJournalId(null);
        lease.setPostedAt(null);
        lease.setPostedBy(null);
        lease.setTerminatedOn(null);
        lease.setTerminationJournalId(null);
        lease.setTerminationNotes(null);
        // Flushed before the unit is asked who lives in it: that query auto-flushes
        // anyway, and doing it here means the answer cannot depend on ordering.
        leases.saveAndFlush(lease);

        leaseService.releaseUnitIfNoOtherLiveLease(lease);
        leaseService.recordLeaseEvent(lease, previous, LeaseStatus.DRAFT,
                "Import batch reversed; contract returned to draft");
        log.debug("Import batch reverse: lease {} back to DRAFT from {}", leaseId, previous);
    }

    // ------------------------------------------------------------------
    // the pre-conditions
    // ------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> blockersAgainstRevert(UUID leaseId, UUID batchId) {
        Lease lease = ours(leaseId);
        if (lease == null) return List.of();

        List<String> blockers = new ArrayList<>();
        String who = "Contract " + name(lease);

        if (settlements.findByLeaseId(leaseId).isPresent()) {
            blockers.add(who + " has been settled since the cut-over; the settlement was drawn against"
                    + " these journals and a refund may already have been paid on it.");
        }
        if (lease.getStatus() != LeaseStatus.ACTIVE) {
            // TERMINATED, RENEWED, EXPIRED, CLOSED, NOTICE_GIVEN — each of them is
            // something a person did to this tenancy after it was imported.
            blockers.add(who + " is " + lease.getStatus()
                    + "; only a contract still exactly as the cut-over left it can be reversed with its batch.");
        }
        for (JournalEntry e : journals.findBySourceTypeAndSourceIdOrderByEntryDateAscCreatedAtAsc(
                JournalSourceType.LEASE, leaseId)) {
            if (e.getStatus() == JournalStatus.POSTED && !batchId.equals(e.getImportBatchId())) {
                // An amendment's fresh TCO, an extension's second TCO, a deposit
                // carry-forward JV. Reversing the batch would not touch it, and
                // returning the lease to DRAFT would leave it with nothing behind it.
                blockers.add(who + " has been amended or extended since the cut-over (" + e.getEntryNumber()
                        + " on " + e.getEntryDate() + ").");
            }
        }
        for (Cheque c : cheques.findByLease_IdOrderBySeqNoAsc(leaseId)) {
            if (!REPLAYABLE.contains(c.getStatus())) {
                blockers.add(who + ": cheque " + label(c) + " is " + c.getStatus()
                        + ", which is not a state the cut-over put it in.");
                continue;
            }
            outsideTheBatch(c.getPdrJournalId(), batchId)
                    .ifPresent(number -> blockers.add(who + ": cheque " + label(c)
                            + " was registered after the cut-over (" + number + ")."));
            outsideTheBatch(c.getCrtJournalId(), batchId)
                    .ifPresent(number -> blockers.add(who + ": cheque " + label(c)
                            + " has cleared since the cut-over (" + number + ")."));
            outsideTheBatch(c.getCbrJournalId(), batchId)
                    .ifPresent(number -> blockers.add(who + ": cheque " + label(c)
                            + " has bounced since the cut-over (" + number + ")."));
        }
        for (RecognitionEntry entry : recognitionEntries.findByLease_IdOrderByPeriodStartAsc(leaseId)) {
            if (entry.getStatus() != RecognitionStatus.POSTED || entry.getJournalId() == null) continue;
            // The remedy this used to name — "Reverse that period's recognition
            // first" — does not exist (review I3, ruling R20): no endpoint reverses a
            // CIL, and the only path that touches a posted one is a termination or an
            // amendment, each of which is itself a blocker here. So the sentence says
            // the product fact instead: the correction window for a whole cut-over
            // batch closes at the first month-end close, and after it a contract is
            // corrected one at a time.
            if (outsideTheBatch(entry.getJournalId(), batchId).isPresent()) {
                blockers.add(who + ": rent for " + entry.getPeriodStart() + " – " + entry.getPeriodEnd()
                        + " was recognised by the month-end close on " + entry.getPeriodEnd()
                        + "; a cut-over batch cannot be reversed once its contracts have been through a close."
                        + " Correct individual contracts by amendment instead.");
            }
        }
        blockers.addAll(vatTaxPoints.blockersAgainstRevert(leaseId, batchId, who));
        for (PenaltyAssessment p : penalties.findByLease_Id(leaseId)) {
            if (LIVE_PENALTIES.contains(p.getStatus())) {
                // A cut-over proposes none of its own — the replay's penalty hooks are
                // suppressed precisely because PACT already dealt with those fines — so
                // one here was raised afterwards, about a cheque this reverse is about
                // to erase. Waive or reverse it first; deleting finance's decision
                // silently is not this operation's to do.
                blockers.add(who + " has a " + p.getStatus() + " penalty (" + p.getReason()
                        + "); waive or reverse it before reversing the batch.");
            }
        }
        return blockers;
    }

    /**
     * The entry number of a journal that is not this batch's, when there is one.
     *
     * <p>A journal id that no longer resolves counts as outside: it is either
     * another tenant's (the repository is filtered) or gone, and either way this
     * batch cannot claim it.</p>
     */
    private java.util.Optional<String> outsideTheBatch(UUID journalId, UUID batchId) {
        if (journalId == null) return java.util.Optional.empty();
        JournalEntry e = journals.findById(journalId).orElse(null);
        if (e == null) return java.util.Optional.of(journalId.toString());
        return batchId.equals(e.getImportBatchId()) ? java.util.Optional.empty()
                : java.util.Optional.of(e.getEntryNumber());
    }

    /**
     * The lease, if it is this tenant's and still exists.
     *
     * <p>Loaded through the filtered repository and checked again explicitly: the id
     * arrives from {@code import_batch_leases}, which carries no tenant column of
     * its own.</p>
     */
    private Lease ours(UUID leaseId) {
        Lease lease = leases.findById(leaseId).orElse(null);
        if (lease == null) return null;
        UUID tenantId = TenantContextHolder.getTenantId();
        return tenantId == null || tenantId.equals(lease.getTenantId()) ? lease : null;
    }

    private static String name(Lease lease) {
        return lease.getExternalContractRef() != null && !lease.getExternalContractRef().isBlank()
                ? lease.getExternalContractRef()
                : String.valueOf(lease.getContractNumber() == null ? lease.getId() : lease.getContractNumber());
    }

    private static String label(Cheque c) {
        return c.getChequeNumber() != null && !c.getChequeNumber().isBlank()
                ? c.getChequeNumber() : "row " + c.getSeqNo();
    }
}
