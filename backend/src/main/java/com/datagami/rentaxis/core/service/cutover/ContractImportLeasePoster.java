package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.VatTiming;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One imported contract, put on the books: its {@code TCO} at its own contract
 * date, its cheques replayed to the statuses and days PACT recorded, and its rent
 * recognised up to the day before the client's books open.
 *
 * <p><b>Why this is a bean of its own and not a method on
 * {@code ContractImportPostService}.</b> A cut-over is six hundred contracts, and
 * contract 87 having an unmapped income account must cost contract 87 and nothing
 * else — an all-or-nothing failure tells the accountant that something, somewhere,
 * is wrong. {@code REQUIRES_NEW} is what gives each lease its own commit boundary,
 * and Spring's transaction advice lives on the proxy: a {@code REQUIRES_NEW}
 * method called from a sibling method of the same class runs in the caller's
 * transaction, so the first failure would roll the whole run back. Separating the
 * bean is the cheapest way to go through the proxy —
 * {@code RecognitionPoster} exists for exactly the same reason.</p>
 *
 * <p><b>And why the whole lease is one transaction.</b> Within a contract the
 * opposite guarantee is wanted: a lease whose fourth cheque cannot be replayed
 * must leave no journals at all, not a {@code TCO} and three {@code PDR}s with a
 * grid that disagrees with them. That is also why the cheque loop does not collect
 * per-row failures and carry on — every transition joins this transaction, so an
 * exception out of one has already marked it rollback-only and continuing would
 * only turn a clear failure into an {@code UnexpectedRollbackException} at commit.</p>
 */
@Component
public class ContractImportLeasePoster {

    private static final Logger log = LoggerFactory.getLogger(ContractImportLeasePoster.class);

    /** What a lease looks like before it has been posted — the two states a post accepts. */
    private static final Set<LeaseStatus> UNPOSTED =
            EnumSet.of(LeaseStatus.DRAFT, LeaseStatus.PENDING_SIGNATURE);

    private final LeaseRepository leases;
    private final ChequeRepository cheques;
    private final LeasePostingService leasePosting;
    private final ChequeService chequeService;
    private final RecognitionService recognition;
    private final Clock clock;

    public ContractImportLeasePoster(LeaseRepository leases, ChequeRepository cheques,
                                     LeasePostingService leasePosting, ChequeService chequeService,
                                     RecognitionService recognition, Clock clock) {
        this.leases = leases;
        this.cheques = cheques;
        this.leasePosting = leasePosting;
        this.chequeService = chequeService;
        this.recognition = recognition;
        this.clock = clock;
    }

    /**
     * Why a portfolio row with a cheque dated before today is left as a draft (PR
     * #344 review C1). Shown to the landlord in the import result.
     */
    public static final String RUNNING_TENANCY_REFUSAL = "Running tenancy with past-dated cheques — import it"
            + " through the cut-over import (Contracts sheet), which records each cheque's status";

    /** What one lease's post did, for the caller's result row. */
    public record Posted(int chequesDeposited, int chequesCleared, int chequesBounced,
                         int recognitionEntriesPosted) {
    }

    /**
     * Post one lease of the batch, or say why it is being skipped.
     *
     * @return what was written, or {@code null} when the lease is already posted —
     *         which is not a failure but the answer a retry needs. A lease that
     *         cannot post throws, and the caller records it against this contract
     *         and moves on to the next.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Posted postOne(UUID batchId, UUID leaseId, LocalDate recogniseThrough) {
        return postAndReplay(leaseId, batchId, recogniseThrough, () -> {
            onContractVatTiming(leaseId);
            leasePosting.post(leaseId, batchId);
        });
    }

    /**
     * A cut-over contract is posted on the CONTRACT VAT model, whatever its draft
     * says: PACT declared its VAT on the contract date, so the {@code TCO} credits
     * Output VAT in full and there are no tax points, catch-up or later, and no tax
     * invoices of ours (spec 2026-09-24 §1, cut-over ruling). The import writes its
     * drafts that way; this also covers a draft persisted before the column existed,
     * which changeset 108 defaulted to INSTALMENT.
     */
    private void onContractVatTiming(UUID leaseId) {
        Lease lease = leases.findByIdScopedToTenant(leaseId)
                .orElseThrow(() -> new BusinessRuleViolationException("This lease no longer exists"));
        if (lease.getVatTiming() != VatTiming.CONTRACT) {
            lease.setVatTiming(VatTiming.CONTRACT);
            leases.saveAndFlush(lease);
        }
    }

    /**
     * One lease of a <b>portfolio import</b> (the v1 Properties → Import Portfolio
     * workbook) that the sheet marked ACTIVE, put on the books (gap #83).
     *
     * <p>The same door and the same commit boundary as {@link #postOne}, for the
     * same reason: the fourth lease of the workbook failing to post must cost that
     * lease and nothing else, and it must leave no journals behind at all. What
     * differs is only what the post is: no batch id (a portfolio import is not a
     * reversible cut-over batch, so its journals obey the period lock like any
     * interactive post), and no recognition catch-up — the nightly recognition run
     * treats this lease exactly as it treats one posted by hand. The cheque replay
     * is skipped, since a portfolio import never records an imported status.</p>
     *
     * <p><b>So a lease with any cheque dated before today is not posted</b> (PR #344
     * review C1). Posting registers every row as an outstanding PDC, and the v1
     * sheet has no column saying which of a running tenancy's cheques have already
     * been banked: the renter would be e-mailed as overdue and offered "pay now"
     * for rent they paid months ago, and the dashboard would count that money twice.
     * The truth is unknown here, so the lease stays DRAFT with
     * {@link #RUNNING_TENANCY_REFUSAL}; the cut-over import is the door that
     * records each instrument's status. Today is the app clock's (Asia/Dubai).</p>
     *
     * <p>Its VAT stays on the lease's own model (INSTALMENT for a new draft): unlike
     * a cut-over contract, this is a new tenancy whose cheques are all still to come,
     * so no other system has declared its VAT.</p>
     *
     * @param importGeneratedRows the rows the import generated rather than read off
     *        the sheet — the only ones that may be registered without a number.
     * @return what was written, or {@code null} when the lease is not DRAFT any more.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Posted postPortfolioLease(UUID leaseId, Set<UUID> importGeneratedRows) {
        return postAndReplay(leaseId, null, null, () -> {
            requireNoPastDatedCheques(leaseId);
            leasePosting.postForPortfolioImport(leaseId, importGeneratedRows);
        });
    }

    private void requireNoPastDatedCheques(UUID leaseId) {
        LocalDate today = LocalDate.now(clock);
        boolean anyPast = cheques.findByLease_IdOrderBySeqNoAsc(leaseId).stream()
                .anyMatch(c -> c.getChequeDate() != null && c.getChequeDate().isBefore(today));
        if (anyPast) {
            throw new BusinessRuleViolationException(RUNNING_TENANCY_REFUSAL);
        }
    }

    private Posted postAndReplay(UUID leaseId, UUID batchId, LocalDate recogniseThrough, Runnable post) {
        Lease lease = leases.findByIdScopedToTenant(leaseId).orElse(null);
        if (lease == null) {
            // The link has no foreign key on lease_id by design (changeset 88), so a
            // lease deleted between the import and the post leaves one behind.
            throw new BusinessRuleViolationException("This lease no longer exists");
        }
        if (!UNPOSTED.contains(lease.getStatus())) {
            // Idempotence, and the reason a second Post retries only what failed: a
            // contract already on the books is left exactly as it is. Posting it again
            // would raise the whole contract value a second time.
            return null;
        }

        post.run();

        int deposited = 0;
        int cleared = 0;
        int bounced = 0;
        // A portfolio import (no batch) records no imported cheque statuses: the
        // post has registered every row and that is all its sheet said.
        List<Cheque> toReplay = batchId == null ? List.of() : cheques.findByLease_IdOrderBySeqNoAsc(leaseId);
        ChequeService.Replay replay = batchId == null ? null : new ChequeService.Replay(batchId);
        for (Cheque c : toReplay) {
            switch (replayTarget(c)) {
                case REGISTERED -> {
                    // The lease post already registered it and wrote its PDR; the
                    // spreadsheet says nothing else happened to this instrument.
                }
                case DEPOSITED -> {
                    chequeService.deposit(c.getId(), on(c.getImportedDepositedOn()), replay);
                    deposited++;
                }
                case CLEARED -> {
                    if (c.getMode() == ChequeMode.PDC) {
                        // Paper goes to the bank before the bank confirms it. The date
                        // is the sheet's, or the day it cleared when PACT exported only
                        // that — ContractImportValidator.depositedOnFor owns that rule
                        // and wrote this column with it.
                        chequeService.deposit(c.getId(), on(c.getImportedDepositedOn()), replay);
                        chequeService.clear(c.getId(), on(c.getImportedClearedOn()), replay);
                    } else {
                        // Cash and transfers are received straight to CLEARED and never
                        // go near a bank; ChequeService.requireDepositable refuses to
                        // deposit one.
                        chequeService.receive(c.getId(), on(c.getImportedClearedOn()), replay);
                    }
                    cleared++;
                }
                case BOUNCED -> {
                    chequeService.deposit(c.getId(), on(c.getImportedDepositedOn()), replay);
                    if (c.getImportedClearedOn() != null) {
                        // A cheque that cleared and was returned weeks later. The
                        // difference is not cosmetic: bouncing after clearing credits
                        // the bank the money actually reached, while bouncing before it
                        // credits the PDC receivable.
                        chequeService.clear(c.getId(), on(c.getImportedClearedOn()), replay);
                    }
                    chequeService.bounce(c.getId(), on(c.getImportedBouncedOn()), replay);
                    bounced++;
                }
                default -> throw new BusinessRuleViolationException(
                        "Cheque " + label(c) + " asks to be imported as " + c.getImportedStatus()
                                + ", which the replay cannot reach from a new contract");
            }
        }

        int recognised = recogniseThrough == null ? 0
                : recognition.catchUpLease(leaseId, recogniseThrough, batchId).posted();

        log.debug("Imported lease {} posted in batch {}: {} deposited, {} cleared, {} bounced, {} recognised",
                leaseId, batchId, deposited, cleared, bounced, recognised);
        return new Posted(deposited, cleared, bounced, recognised);
    }

    /**
     * The status the spreadsheet asked this row to end up in.
     *
     * <p>Null means the row came from somewhere other than a cut-over import — it
     * has just been registered by the lease post and that is all the sheet claimed.</p>
     */
    private static ChequeStatus replayTarget(Cheque c) {
        return c.getImportedStatus() == null ? ChequeStatus.REGISTERED : c.getImportedStatus();
    }

    /**
     * The transition, dated the day the money really moved.
     *
     * <p>No notes: a replay must leave the row's {@code notes} column as the
     * importer wrote it, because reverting a batch puts the lease back to a clean
     * DRAFT and a note the replay invented would be one more thing to erase.</p>
     *
     * <p>No debit-account override either: the row already carries the account the
     * sheet named, or the property's BANK mapping the import resolved for it.</p>
     */
    private static ChequeActionRequest on(LocalDate date) {
        return new ChequeActionRequest(date, null, null, null);
    }

    private static String label(Cheque c) {
        return c.getChequeNumber() != null && !c.getChequeNumber().isBlank()
                ? c.getChequeNumber() : "row " + c.getSeqNo();
    }
}
