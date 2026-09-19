package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.cheque.DepositBatchRequest;
import com.datagami.rentaxis.api.dto.cheque.ReplaceChequeRequest;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.ChequePayload;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.NotificationService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Line;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.lease.LeaseChequeRegistrar;
import com.datagami.rentaxis.core.service.penalty.PenaltyRuleEngine;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseEvent;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AccountSubType;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PenaltyAssessmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The cheque register's write side: every way a registered instrument changes
 * hands, and the one journal each of those ways is allowed to write (spec §7.2).
 *
 * <p><b>At most one journal per transition.</b> That is the whole shape of this
 * class. Banking a cheque moves paper and posts nothing; the bank confirming it
 * posts one {@code CRT}; the bank returning it posts one {@code CBR}; cancelling
 * or handing a cheque back reverses the {@code PDR} that registered it and never
 * hand-builds the mirror. A transition the table does not list is refused
 * outright, which is what keeps the register and the ledger describing the same
 * money: if the only way to CLEARED runs through here, there is no CLEARED
 * cheque without a CRT behind it.</p>
 *
 * <p><b>The row is locked before it is read.</b> Two clerks clearing and bouncing
 * the same cheque under {@code READ_COMMITTED} would both see {@code DEPOSITED},
 * both pass their guard, and the ledger would hold a clearing entry and a bounce
 * for one cheque. {@code findByIdForUpdate} is {@code NOWAIT}, so the loser fails
 * immediately with "try again" rather than parking a connection behind a
 * transaction that may be a user's open tab.</p>
 *
 * <p><b>Where the money lands is remembered, not recomputed.</b> A cheque clears
 * into whatever bank the clerk names; if the bank reverses it a week later, the
 * credit has to go back to that same account and not to whatever the property's
 * BANK role resolves to today. So the account actually debited is written onto
 * the cheque at clear time, and the bounce reads it back.</p>
 *
 * <p><b>Every public method is {@code @Transactional}</b>: {@code TenantAspect}
 * only enables the Hibernate tenant filter inside a transaction, so a read
 * outside one would cross tenants.</p>
 */
@Service
public class ChequeService {

    private static final Logger log = LoggerFactory.getLogger(ChequeService.class);

    /**
     * A lease whose contract is on the books. Its cheques are instruments against
     * a real debt; a DRAFT lease's rows are a proposal, and moving one through the
     * register would clear money against a receivable nobody has raised.
     */
    private static final Set<LeaseStatus> POSTED = EnumSet.of(
            LeaseStatus.ACTIVE, LeaseStatus.NOTICE_GIVEN, LeaseStatus.EXPIRED, LeaseStatus.RENEWED);

    private final ChequeRepository chequeRepository;
    private final LeaseRepository leaseRepository;
    private final LeaseEventRepository leaseEventRepository;
    private final AccountRepository accountRepository;
    private final PostingService postingService;
    private final LeaseChequeRegistrar registrar;
    private final LeaseAccessPolicy leaseAccessPolicy;
    private final NotificationService notificationService;
    private final PenaltyRuleEngine penaltyRules;
    private final PenaltyAssessmentRepository penaltyAssessments;
    private final ApplicationEventPublisher events;

    /**
     * {@code @Lazy} on the rule engine breaks a genuine cycle rather than papering
     * over a layering mistake: the register tells the penalty module when a cheque
     * bounced, and the penalty module asks the register to create the collection
     * row an approval is paid through ({@code PenaltyAssessmentService.approve} →
     * {@link #addRowToPostedLease}). Both directions are real, so one of them has
     * to be resolved on first use.
     */
    public ChequeService(ChequeRepository chequeRepository,
                         LeaseRepository leaseRepository,
                         LeaseEventRepository leaseEventRepository,
                         AccountRepository accountRepository,
                         PostingService postingService,
                         LeaseChequeRegistrar registrar,
                         LeaseAccessPolicy leaseAccessPolicy,
                         NotificationService notificationService,
                         @Lazy PenaltyRuleEngine penaltyRules,
                         PenaltyAssessmentRepository penaltyAssessments,
                         ApplicationEventPublisher events) {
        this.chequeRepository = chequeRepository;
        this.leaseRepository = leaseRepository;
        this.leaseEventRepository = leaseEventRepository;
        this.accountRepository = accountRepository;
        this.postingService = postingService;
        this.registrar = registrar;
        this.leaseAccessPolicy = leaseAccessPolicy;
        this.notificationService = notificationService;
        this.penaltyRules = penaltyRules;
        this.penaltyAssessments = penaltyAssessments;
        this.events = events;
    }

    // ------------------------------------------------------------------
    // REGISTERED -> DEPOSITED  (no journal)
    // ------------------------------------------------------------------

    /**
     * The paper went to the bank. Nothing posts: the landlord still holds a claim
     * of exactly the same size against exactly the same renter, it has merely
     * moved from a drawer to a counter (spec §7.2, "none (operational)").
     */
    @Transactional
    public ChequeDTO deposit(UUID chequeId, ChequeActionRequest request) {
        ChequeActionRequest r = request == null ? ChequeActionRequest.empty() : request;
        Cheque cheque = lock(chequeId);
        Lease lease = managedLeaseOf(cheque);
        requireStatus(cheque, "deposit", ChequeStatus.REGISTERED);
        requireDepositable(cheque);

        applyDeposit(cheque, r.dateOrToday(), r.debitAccountId(), r.notes());
        chequeRepository.save(cheque);
        publishDeposited(cheque);
        return dto(cheque, lease);
    }

    /**
     * The day's run, banked together (spec §7.4).
     *
     * <p>All or nothing, and deliberately so: the rows are ticked off a screen
     * against a physical pile, and a partial run would leave the register claiming
     * a deposit slip that the bank never saw. Every id is locked up front by
     * {@code findAllByIdForUpdate}, so a contended row fails the call before any
     * row is touched.</p>
     */
    @Transactional
    public List<ChequeDTO> depositBatch(DepositBatchRequest request) {
        if (request == null || request.chequeIds() == null || request.chequeIds().isEmpty()) {
            throw new BusinessRuleViolationException("Select at least one cheque to deposit");
        }
        // Distinct, because the same row ticked twice would otherwise be reported
        // as its own duplicate and would be saved twice.
        List<UUID> ids = request.chequeIds().stream().distinct().toList();

        List<Cheque> cheques;
        try {
            cheques = chequeRepository.findAllByIdForUpdate(ids);
        } catch (PessimisticLockingFailureException e) {
            throw new BusinessRuleViolationException(BEING_UPDATED);
        }
        Map<UUID, Cheque> byId = new LinkedHashMap<>();
        for (Cheque c : cheques) {
            requireSameTenant(c);
            byId.put(c.getId(), c);
        }
        List<String> problems = new ArrayList<>();
        for (UUID id : ids) {
            Cheque c = byId.get(id);
            if (c == null) {
                problems.add(id + " does not exist");
                continue;
            }
            Lease lease = c.getLease();
            if (lease == null || !POSTED.contains(lease.getStatus())) {
                problems.add(label(c) + " belongs to a lease that is not posted");
            } else if (c.getStatus() != ChequeStatus.REGISTERED) {
                problems.add(label(c) + " is " + c.getStatus());
            } else if (c.getMode() != ChequeMode.PDC) {
                problems.add(label(c) + " is a " + c.getMode() + " receipt, not a cheque");
            }
        }
        if (!problems.isEmpty()) {
            // Named, and nothing changed: the clerk is holding the pile and needs to
            // know which piece of paper to pull out of it.
            throw new BusinessRuleViolationException(
                    "These cheques cannot be deposited: " + String.join("; ", problems)
                            + ". Only REGISTERED post-dated cheques can be banked, and nothing was deposited.");
        }

        LocalDate date = request.dateOrToday();
        List<ChequeDTO> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Cheque c = byId.get(id);
            Lease lease = managedLeaseOf(c);
            applyDeposit(c, date, request.debitAccountId(), null);
            chequeRepository.save(c);
            publishDeposited(c);
            out.add(dto(c, lease));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // -> CLEARED  (CRT)
    // ------------------------------------------------------------------

    /**
     * The bank confirmed it: {@code CRT} Dr the bank / Cr PDC receivable. The
     * instrument stops being a claim and becomes money.
     */
    @Transactional
    public ChequeDTO clear(UUID chequeId, ChequeActionRequest request) {
        ChequeActionRequest r = request == null ? ChequeActionRequest.empty() : request;
        Cheque cheque = lock(chequeId);
        Lease lease = managedLeaseOf(cheque);
        requireStatus(cheque, "clear", ChequeStatus.DEPOSITED);

        applyClearing(lease, cheque, r.dateOrToday(), r.debitAccountId(), r.notes());
        chequeRepository.save(cheque);
        // A cheque cleared after its grace period is a late payment. Inside this
        // transaction on purpose: "the money arrived late" and "finance should look
        // at a late fee" are one fact, and a proposal that failed to write while the
        // clearing committed would lose it silently.
        penaltyRules.onLateClear(cheque, r.dateOrToday());
        publishCleared(cheque);
        return dto(cheque, lease);
    }

    /**
     * Cash or a transfer arrived, with no trip to the bank in between
     * (spec §7.2, REGISTERED → CLEARED). Same {@code CRT}: a transfer that landed
     * settles the receivable exactly as a cheque that cleared does, and giving it
     * its own treatment is how the register and the ledger came to disagree about
     * what was collected.
     */
    @Transactional
    public ChequeDTO receive(UUID chequeId, ChequeActionRequest request) {
        ChequeActionRequest r = request == null ? ChequeActionRequest.empty() : request;
        Cheque cheque = lock(chequeId);
        Lease lease = managedLeaseOf(cheque);
        requireStatus(cheque, "receive", ChequeStatus.REGISTERED);
        if (cheque.getMode() != ChequeMode.CASH && cheque.getMode() != ChequeMode.TRANSFER) {
            throw new BusinessRuleViolationException(
                    "Only a CASH or TRANSFER receipt can be received directly; " + label(cheque)
                            + " is a " + cheque.getMode() + " row. Deposit it and clear it instead.");
        }

        applyClearing(lease, cheque, r.dateOrToday(), r.debitAccountId(), r.notes());
        chequeRepository.save(cheque);
        // Cash over the counter reaches CLEARED by a different door, but it is the
        // same fact: the money arrived, and it may have arrived late. Leaving the
        // hook on clear() alone made the late fee depend on which door the renter
        // happened to pay through.
        penaltyRules.onLateClear(cheque, r.dateOrToday());
        publishCleared(cheque);
        return dto(cheque, lease);
    }

    // ------------------------------------------------------------------
    // -> BOUNCED  (CBR)
    // ------------------------------------------------------------------

    /**
     * The bank returned it.
     *
     * <p>From {@code DEPOSITED} the money never arrived, so the {@code CBR} simply
     * puts the debt back where the {@code PDR} took it from: Dr rent receivable /
     * Cr PDC receivable. From {@code CLEARED} the money <em>did</em> arrive and has
     * now been taken back out of the bank, so the credit goes to the very account
     * that was debited when it cleared — not to PDC receivable, which was already
     * settled and would otherwise go negative on a cheque nobody holds.</p>
     */
    @Transactional
    public ChequeDTO bounce(UUID chequeId, ChequeActionRequest request) {
        ChequeActionRequest r = request == null ? ChequeActionRequest.empty() : request;
        Cheque cheque = lock(chequeId);
        Lease lease = managedLeaseOf(cheque);
        requireStatus(cheque, "bounce", ChequeStatus.DEPOSITED, ChequeStatus.CLEARED);
        boolean afterClearing = cheque.getStatus() == ChequeStatus.CLEARED;
        if (afterClearing && cheque.getMode() != ChequeMode.PDC) {
            // Cash in the drawer does not un-arrive, and a settled transfer is
            // reversed by the bank as its own receipt, not by editing this one.
            throw new BusinessRuleViolationException(
                    "Only a post-dated cheque can bounce after it has cleared; " + label(cheque)
                            + " is a " + cheque.getMode() + " receipt.");
        }

        LocalDate date = r.dateOrToday();
        BigDecimal amount = cheque.getAmount();
        String narration = LeaseChequeRegistrar.narrationOf(cheque);
        Line credit;
        if (afterClearing) {
            Account banked = cheque.getDebitAccount();
            credit = banked != null
                    ? PostingRequest.cr(banked.getId(), amount)
                    : PostingRequest.cr(settlementRole(cheque.getMode()), amount);
        } else {
            credit = PostingRequest.cr(AccountRole.PDC_RECEIVABLE, amount);
        }
        JournalEntry cbr = postingService.post(PostingRequest.ofPairs(
                JournalDocType.CBR,
                date,
                narration,
                LeaseChequeRegistrar.dimensions(lease, cheque.getId()),
                JournalSourceType.CHEQUE,
                cheque.getId(),
                null,
                List.of(PostingRequest.pair(
                        LeaseChequeRegistrar.drReceivable(lease, amount).withNarration(narration),
                        credit.withNarration(narration)))));

        cheque.setCbrJournalId(cbr.getId());
        cheque.setBouncedAt(date);
        cheque.setFailureReason(r.failureReason());
        moveTo(cheque, ChequeStatus.BOUNCED, r.notes());
        chequeRepository.save(cheque);
        recordLeaseEvent(lease, cheque, "returned by the bank"
                + (r.failureReason() != null ? " (" + r.failureReason() + ")" : "")
                + " — " + money(amount) + " AED back on the receivable");

        publish(EmailEventType.CHEQUE_BOUNCED, cheque,
                ChequePayload.ofCheque(cheque, null,
                        r.failureReason() != null ? r.failureReason().name() : null));
        notifyRenter(cheque, "PAYMENT_BOUNCED", "Cheque Failed",
                "Instalment #" + cheque.getSeqNo() + " of " + money(amount)
                        + " AED was returned" + (r.failureReason() != null ? " (" + r.failureReason() + ")" : "")
                        + ". Please arrange a replacement.");
        // The threshold count and the fine itself are the penalty module's. Called
        // after the save so the bounce this call is recording is inside the count,
        // and inside this transaction so a rule that cannot write its proposal rolls
        // the bounce back rather than leaving a returned cheque finance never sees.
        penaltyRules.onBounce(cheque);
        return dto(cheque, lease);
    }

    // ------------------------------------------------------------------
    // BOUNCED -> REPLACED
    // ------------------------------------------------------------------

    /**
     * What the renter handed over instead (spec §7.2, BOUNCED → REPLACED).
     *
     * <p>The bounced row is superseded, never edited: the history of what was
     * actually written and what actually failed is the reason the register exists.
     * Each replacement registers in its own right, with its own {@code PDR}, so
     * three replacement cheques are three instruments in the drawer and three rows
     * on the deposit run.</p>
     *
     * <p>Σ replacements may fall <em>short</em> of the bounced amount — the
     * difference stays in rent receivable, where the bounce put it, and remains
     * visible as what the renter still owes. It may not exceed it: that is not a
     * replacement, it is a receipt for something else.</p>
     */
    @Transactional
    public List<ChequeDTO> replace(UUID chequeId, ReplaceChequeRequest request) {
        if (request == null) throw new BusinessRuleViolationException("At least one replacement is required");
        Cheque bounced = lock(chequeId);
        Lease lease = managedLeaseOf(bounced);
        // The lease row is locked too: seq numbers are max+1 over the register, so
        // two replacements agreed at the same moment on the same lease would both
        // read the same maximum and both claim the same position.
        lockLease(lease.getId());
        requireStatus(bounced, "replace", ChequeStatus.BOUNCED);

        List<ChequeRowInput> rows = request.replacements();
        List<Cheque> register = chequeRepository.findByLease_IdOrderBySeqNoAsc(lease.getId());
        ChequeRowRules.validateNewRows(rows, takenNumbers(register), "replacement");

        BigDecimal total = BigDecimal.ZERO;
        for (ChequeRowInput row : rows) {
            total = total.add(row.amount());
        }
        if (total.compareTo(bounced.getAmount()) > 0) {
            throw new BusinessRuleViolationException(
                    "The replacements total " + money(total) + " but " + label(bounced)
                            + " was " + money(bounced.getAmount())
                            + "; a replacement cannot collect more than the cheque it replaces.");
        }

        LocalDate date = request.dateOrToday();
        List<ChequeDTO> out = new ArrayList<>(rows.size());
        int seq = nextSeqNo(register);
        for (ChequeRowInput row : rows) {
            // The registrar directly, not addRowToPostedLease: that is a public
            // method on this same bean, so calling it here would bypass the Spring
            // proxy anyway, and it would re-lock the lease and re-read the register
            // once per replacement. The row-building and PDR steps are shared; the
            // guards this method already ran are not repeated.
            Cheque replacement = newRow(lease, row, seq++, date);
            replacement.setReplaces(bounced);
            replacement.setPenaltyAssessmentId(bounced.getPenaltyAssessmentId());
            chequeRepository.save(replacement);
            registrar.register(lease, replacement);
            if (bounced.getReplacedBy() == null) {
                // The chain points at the first replacement; the rest are reachable
                // through their own replaces_id. A single column cannot hold three.
                bounced.setReplacedBy(replacement);
                repointPenaltyCollection(bounced, replacement);
            }
            publish(EmailEventType.CHEQUE_RECEIVED, replacement,
                    ChequePayload.ofCheque(replacement, null, null));
            out.add(dto(replacement, lease));
        }

        moveTo(bounced, ChequeStatus.REPLACED, request.notes());
        chequeRepository.save(bounced);
        recordLeaseEvent(lease, bounced, "replaced by " + rows.size()
                + (rows.size() == 1 ? " instrument" : " instruments") + " totalling " + money(total) + " AED");
        return out;
    }

    /**
     * The gateway's door into the register: a bounced cheque becomes one
     * {@code ONLINE} row the renter can pay through Razorpay (spec §9.3).
     *
     * <p>This exists because a bounced row cannot be paid online <em>in place</em>.
     * Its {@code CBR} has already credited PDC receivable back to nothing and put
     * the debt on rent receivable; a capture against that row would post a
     * {@code CRT} crediting a PDC balance that is no longer there, driving it
     * negative while the rent receivable it was meant to settle stays debited. So
     * the bounce is replaced first, exactly as a paper replacement is, and the new
     * row carries its own {@code PDR} — which is precisely the PDC balance the
     * capture then clears.</p>
     *
     * <p>One row, the same amount, dated the day the renter is paying: the renter
     * is not negotiating instalments here, they are settling a specific failed
     * cheque. {@code ONLINE} is refused on every user-facing path
     * ({@code saveRows}, {@link #replace}, {@link #addRowToPostedLease}) and
     * created only here, so an online row always has a gateway behind it.</p>
     */
    @Transactional
    public ChequeDTO replaceForOnlinePayment(UUID bouncedChequeId, LocalDate date) {
        Cheque bounced = lock(bouncedChequeId);
        Lease lease = gatewayLeaseOf(bounced);
        lockLease(lease.getId());
        requireStatus(bounced, "replace", ChequeStatus.BOUNCED);

        LocalDate on = date != null ? date : LocalDate.now();
        ChequeRowInput gatewayRow = new ChequeRowInput(
                null, null, on, null, on, null, null, null,
                bounced.getAmount(), "Online payment for cheque " + label(bounced), ChequeMode.ONLINE);
        List<Cheque> register = chequeRepository.findByLease_IdOrderBySeqNoAsc(lease.getId());
        // The gateway overload, not the public one: the rule that ONLINE is never
        // typed in stays where every other caller meets it.
        ChequeRowRules.validateGatewayRow(gatewayRow, takenNumbers(register));

        Cheque replacement = newRow(lease, gatewayRow, nextSeqNo(register), on);
        replacement.setReplaces(bounced);
        replacement.setPenaltyAssessmentId(bounced.getPenaltyAssessmentId());
        chequeRepository.save(replacement);
        registrar.register(lease, replacement);

        bounced.setReplacedBy(replacement);
        repointPenaltyCollection(bounced, replacement);
        moveTo(bounced, ChequeStatus.REPLACED, "Replaced by an online payment row");
        chequeRepository.save(bounced);

        publish(EmailEventType.CHEQUE_RECEIVED, replacement, ChequePayload.ofCheque(replacement, null, null));
        return dto(replacement, lease);
    }

    // ------------------------------------------------------------------
    // -> CANCELLED / RETURNED  (reversal of the PDR)
    // ------------------------------------------------------------------

    /**
     * Finance cancels a registered instrument — it was entered in error, or the
     * renter took it back before it was ever banked.
     *
     * <p>The {@code PDR} is reversed through {@code PostingService}, never mirrored
     * by hand: the reversal has to carry the original's line dimensions and contra
     * accounts and has to mark the original REVERSED, and a hand-built opposite
     * entry does none of that.</p>
     */
    @Transactional
    public ChequeDTO cancel(UUID chequeId, ChequeActionRequest request) {
        ChequeActionRequest r = request == null ? ChequeActionRequest.empty() : request;
        Cheque cheque = lock(chequeId);
        Lease lease = managedLeaseOf(cheque);
        requireStatus(cheque, "cancel", ChequeStatus.REGISTERED);

        reversePdr(cheque, r.dateOrToday(), reasonOr(r.notes(), "Cheque cancelled"));
        moveTo(cheque, ChequeStatus.CANCELLED, r.notes());
        chequeRepository.save(cheque);
        recordLeaseEvent(lease, cheque, "cancelled and its registration reversed"
                + (r.notes() != null && !r.notes().isBlank() ? " — " + r.notes().trim() : ""));
        return dto(cheque, lease);
    }

    /**
     * The landlord handed the paper back — what a termination does to every
     * uncleared instrument it finds (spec §9.1). Same reversal as a cancellation;
     * the distinct status is what tells the renter's statement "you have your
     * cheque" rather than "we tore it up".
     */
    @Transactional
    public ChequeDTO returnToTenant(UUID chequeId, LocalDate date, String reason) {
        Cheque cheque = lock(chequeId);
        Lease lease = managedLeaseOf(cheque);
        requireStatus(cheque, "return", ChequeStatus.REGISTERED, ChequeStatus.DEPOSITED);

        LocalDate on = date != null ? date : LocalDate.now();
        reversePdr(cheque, on, reasonOr(reason, "Cheque returned to tenant"));
        cheque.setReturnedAt(on);
        moveTo(cheque, ChequeStatus.RETURNED, reason);
        chequeRepository.save(cheque);
        recordLeaseEvent(lease, cheque, "handed back to the tenant"
                + (reason != null && !reason.isBlank() ? " — " + reason.trim() : ""));
        return dto(cheque, lease);
    }

    // ------------------------------------------------------------------
    // a row added to a lease that is already on the books
    // ------------------------------------------------------------------

    /**
     * One more instrument against a posted contract — a replacement, a penalty
     * collection, an extension's extra instalment, a cash receipt taken at the
     * counter (spec §7.2, DRAFT → REGISTERED, "row added to a posted lease").
     *
     * <p>It registers immediately: there is no draft grid to sit in, because the
     * grid closed when the lease posted. The {@code PDR} is the same entry the
     * lease's own post writes, through the same collaborator, so a row added in
     * March is indistinguishable in the ledger from one that was in the contract.</p>
     *
     * <p>A row on a lease that has <em>not</em> posted is refused rather than
     * quietly registered: on a draft lease the grid is still editable, Σ rows is
     * still checked against the contract value at posting time, and a row that
     * arrived through here would carry a journal the post would then duplicate.</p>
     *
     * <p><b>The lease row is locked first.</b> The new row's position is max+1 over
     * the register, so two clerks adding a row to the same lease at the same moment
     * would both read the same maximum and both write position 6. Nothing in the
     * database forbids that — only the cheque number is indexed — so the register
     * would quietly show two "row 6"s.</p>
     */
    @Transactional
    public ChequeDTO addRowToPostedLease(UUID leaseId, ChequeRowInput row) {
        Lease lease = lockLease(leaseId);
        leaseAccessPolicy.requireManageable(lease);
        if (!POSTED.contains(lease.getStatus())) {
            throw new BusinessRuleViolationException(
                    "This lease is " + lease.getStatus() + "; use the cheque grid to add rows until it is posted.");
        }
        // Read once: the numbers already taken and the last position come off the
        // same list, and a second query would be a second chance to disagree.
        List<Cheque> register = chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId);
        ChequeRowRules.validateNewRows(List.of(row), takenNumbers(register), "row");

        Cheque cheque = newRow(lease, row, nextSeqNo(register), LocalDate.now());
        chequeRepository.save(cheque);
        registrar.register(lease, cheque);
        publish(EmailEventType.CHEQUE_RECEIVED, cheque, ChequePayload.ofCheque(cheque, null, null));
        return dto(cheque, lease);
    }

    /**
     * Cash or a transfer taken at the counter (spec §7.4, "Cash Receipt Voucher –
     * Rent"): the row is added to the posted lease and received in the same breath.
     *
     * <p>One transaction, deliberately. The two halves are a {@code PDR} raising a
     * receipt the landlord holds and a {@code CRT} settling it against the bank,
     * and a register that committed the first without the second would show money
     * the counter took as still outstanding. The self-call to
     * {@link #addRowToPostedLease} runs inside this method's transaction — the
     * guards it carries (manageable lease, posted lease, row rules, the lease row
     * lock) all apply, and Spring's {@code REQUIRED} would have joined this
     * transaction anyway.</p>
     */
    @Transactional
    public ChequeDTO cashReceipt(UUID leaseId, ChequeRowInput row) {
        if (row == null) throw new BusinessRuleViolationException("A receipt needs a row");
        ChequeMode mode = row.mode();
        if (mode != ChequeMode.CASH && mode != ChequeMode.TRANSFER) {
            // A PDC is paper to be banked and cleared later; an ONLINE row belongs to
            // the gateway. Neither arrives over the counter.
            throw new BusinessRuleViolationException(
                    "A counter receipt must be a CASH or TRANSFER row"
                            + (mode == null ? "" : "; this one is " + mode) + ".");
        }
        // The row's own date is the posting date when it names none, not today: a
        // receipt written up on Monday for cash taken on Friday would otherwise get
        // a PDR dated after the CRT that settles it — an instrument that cleared
        // before it was registered, which no reconciliation can explain.
        ChequeRowInput onItsOwnDate = row.postingDate() != null ? row
                : new ChequeRowInput(row.id(), row.seqNo(), row.chequeDate(), row.chequeNumber(),
                        row.chequeDate(), row.payeeBank(), row.payerName(), row.debitAccountId(),
                        row.amount(), row.narration(), row.mode());
        ChequeDTO created = addRowToPostedLease(leaseId, onItsOwnDate);
        return receive(created.id(), new ChequeActionRequest(
                row.chequeDate(), null, null, row.debitAccountId()));
    }

    // ------------------------------------------------------------------
    // online (Razorpay) — spec §9.3, driven by Task 10
    // ------------------------------------------------------------------

    /**
     * The renter started paying this instalment online. Nothing posts: an
     * authorisation is not money, and a gateway session that is abandoned has to
     * leave the register exactly as it found it.
     *
     * <p><b>REGISTERED only.</b> A bounced row looks like the obvious thing to
     * offer the renter — it is the debt they most urgently owe — and it is exactly
     * the row that must not go down this path. Its {@code CBR} already credited PDC
     * receivable back to nothing, so the {@code CRT} on capture would credit a
     * balance that is not there (PDC goes negative, rent receivable stays debited),
     * and an abandoned session reverting to REGISTERED would launder the bounce
     * out of the register entirely. {@link #replaceForOnlinePayment} is the way
     * in: it supersedes the bounce with an ONLINE row that has its own PDR.</p>
     */
    @Transactional
    public ChequeDTO registerOnlinePending(UUID chequeId) {
        Cheque cheque = lock(chequeId);
        Lease lease = gatewayLeaseOf(cheque);
        if (cheque.getStatus() == ChequeStatus.BOUNCED) {
            throw new BusinessRuleViolationException("Replace the bounced cheque before paying online");
        }
        requireStatus(cheque, "start an online payment for", ChequeStatus.REGISTERED);
        if (cheque.getMode() != ChequeMode.PDC && cheque.getMode() != ChequeMode.ONLINE) {
            // Cash and bank transfers are recorded when they arrive, by receive();
            // there is nothing for a gateway to authorise.
            throw new BusinessRuleViolationException(
                    "Only a post-dated cheque or an online row can be paid through the gateway; "
                            + label(cheque) + " is a " + cheque.getMode() + " receipt.");
        }
        moveTo(cheque, ChequeStatus.ONLINE_PENDING, null);
        chequeRepository.save(cheque);
        return dto(cheque, lease);
    }

    /**
     * The gateway session failed or was abandoned; the row goes back on the
     * register exactly as it left — REGISTERED, which is the only status it can
     * have arrived from.
     */
    @Transactional
    public ChequeDTO revertOnlinePending(UUID chequeId) {
        Cheque cheque = lock(chequeId);
        Lease lease = gatewayLeaseOf(cheque);
        requireStatus(cheque, "revert", ChequeStatus.ONLINE_PENDING);
        moveTo(cheque, ChequeStatus.REGISTERED, null);
        chequeRepository.save(cheque);
        return dto(cheque, lease);
    }

    /**
     * The gateway captured the payment: the same {@code CRT} any other receipt
     * writes, into the settlement account the gateway pays out to.
     *
     * <p><b>Idempotent on an already-captured row.</b> Payment gateways retry their
     * webhooks, and a retry that posted a second CRT would collect the same
     * instalment twice. An {@code ONLINE} row that is already CLEARED is therefore
     * returned as it stands — but only an ONLINE one: a cheque that cleared at the
     * bank arriving here means the webhook is pointing at the wrong row, and
     * answering "fine, already done" would hide that.</p>
     *
     * <p>The idempotent return sits <em>above</em> the late-payment hook on purpose:
     * a retried webhook must not re-propose a penalty any more than it may post a
     * second CRT, and a second proposal would be a second fine on finance's
     * worklist for one payment.</p>
     */
    @Transactional
    public ChequeDTO clearOnline(UUID chequeId, LocalDate capturedOn, UUID settlementAccountId) {
        Cheque cheque = lock(chequeId);
        Lease lease = gatewayLeaseOf(cheque);
        if (cheque.getStatus() == ChequeStatus.CLEARED) {
            if (cheque.getMode() == ChequeMode.ONLINE) {
                return dto(cheque, lease);
            }
            throw new BusinessRuleViolationException(label(cheque) + " is not an online payment row");
        }
        requireStatus(cheque, "capture", ChequeStatus.ONLINE_PENDING);

        LocalDate on = capturedOn != null ? capturedOn : LocalDate.now();
        applyClearing(lease, cheque, on, settlementAccountId, null);
        chequeRepository.save(cheque);
        penaltyRules.onLateClear(cheque, on);
        publishCleared(cheque);
        return dto(cheque, lease);
    }

    // ------------------------------------------------------------------
    // the transitions' shared bodies
    // ------------------------------------------------------------------

    private void applyDeposit(Cheque cheque, LocalDate date, UUID debitAccountId, String notes) {
        if (debitAccountId != null) {
            // Which of our banks the paper physically went to. Recorded now so the
            // CRT that follows debits it rather than re-resolving the role.
            cheque.setDebitAccount(settlementAccount(debitAccountId));
        }
        cheque.setDepositedAt(date);
        moveTo(cheque, ChequeStatus.DEPOSITED, notes);
    }

    /**
     * The {@code CRT} that turns an instrument into money, shared by clear, receive
     * and the gateway's capture.
     *
     * <p>The debit account is chosen once — the caller's override, else the one the
     * row carries, else the role the mode implies — and then <em>written onto the
     * cheque</em>. That is not bookkeeping tidiness: a bounce after clearing has to
     * credit the account this debited, and a role resolved a second time a month
     * later can easily answer with a different leaf.</p>
     */
    private void applyClearing(Lease lease, Cheque cheque, LocalDate date, UUID debitAccountId, String notes) {
        BigDecimal amount = cheque.getAmount();
        String narration = LeaseChequeRegistrar.narrationOf(cheque);
        // Checked on the way in whichever door it came through: an override the
        // caller typed, and the account already on the row — that one was validated
        // when it was set, but a chart of accounts is edited, and a receivable leaf
        // debited here would look exactly like money in the bank on the balance sheet.
        Account debit = debitAccountId != null
                ? settlementAccount(debitAccountId)
                : requireSettlementAccount(cheque.getDebitAccount());
        // No resolveOrNull fallback and no try/catch: when the row names no account
        // the role goes into the request and PostingService resolves it, so an
        // unmapped BANK is one refusal from one place.
        Line dr = debit != null
                ? PostingRequest.dr(debit.getId(), amount)
                : PostingRequest.dr(settlementRole(cheque.getMode()), amount);

        JournalEntry crt = postingService.post(PostingRequest.ofPairs(
                JournalDocType.CRT,
                date,
                narration,
                LeaseChequeRegistrar.dimensions(lease, cheque.getId()),
                JournalSourceType.CHEQUE,
                cheque.getId(),
                null,
                List.of(PostingRequest.pair(
                        dr.withNarration(narration),
                        PostingRequest.cr(AccountRole.PDC_RECEIVABLE, amount).withNarration(narration)))));

        if (debit == null) {
            // Read back off the entry rather than resolved again: the line that was
            // actually written is the only account a later bounce may credit.
            debit = crt.getLines().stream()
                    .filter(l -> l.getDebit() != null && l.getDebit().signum() > 0)
                    .map(l -> l.getAccount())
                    .findFirst().orElse(null);
        }
        cheque.setDebitAccount(debit);
        cheque.setCrtJournalId(crt.getId());
        cheque.setClearedAt(date);
        moveTo(cheque, ChequeStatus.CLEARED, notes);
    }

    private void reversePdr(Cheque cheque, LocalDate date, String reason) {
        if (cheque.getPdrJournalId() == null) {
            throw new BusinessRuleViolationException(
                    label(cheque) + " has no registering journal to reverse");
        }
        postingService.reverse(cheque.getPdrJournalId(), date, reason);
    }

    /**
     * One new register row from a typed one. The posting date is what the caller
     * asked for, else today — a replacement agreed today registers today, whatever
     * date is written on the paper.
     */
    private Cheque newRow(Lease lease, ChequeRowInput row, int seqNo, LocalDate fallbackPostingDate) {
        Unit unit = lease.getUnit();
        Property property = unit != null ? unit.getProperty() : null;
        if (property == null) {
            throw new BusinessRuleViolationException("The lease's unit has no property; cheques cannot be attributed");
        }
        Cheque c = new Cheque();
        c.setTenantId(lease.getTenantId());
        c.setLease(lease);
        c.setUnit(unit);
        c.setProperty(property);
        c.setRenter(lease.getRenter());
        c.setSeqNo(seqNo);
        c.setPostingDate(row.postingDate() != null ? row.postingDate() : fallbackPostingDate);
        c.setChequeDate(row.chequeDate());
        c.setChequeNumber(ChequeRowRules.blankToNull(row.chequeNumber()));
        c.setPayeeBank(row.payeeBank());
        c.setPayerName(row.payerName() != null && !row.payerName().isBlank()
                ? row.payerName()
                : (lease.getRenter() != null ? lease.getRenter().getNameEn() : null));
        c.setAmount(row.amount());
        c.setNarration(row.narration());
        c.setMode(row.mode() == null ? ChequeMode.PDC : row.mode());
        c.setStatus(ChequeStatus.DRAFT);
        // Left null when the caller named none: a cash row with no account clears
        // against the CASH role, which is the tenant-level default, and forcing the
        // property's bank onto it here would bank cash that never went to a bank.
        if (row.debitAccountId() != null) {
            c.setDebitAccount(settlementAccount(row.debitAccountId()));
        }
        return c;
    }

    // ------------------------------------------------------------------
    // guards
    // ------------------------------------------------------------------

    private static final String BEING_UPDATED =
            "This cheque is being updated by another request. Please try again.";

    /**
     * The row, locked, tenant-checked.
     *
     * <p>A NOWAIT conflict is a 400 that says "try again", not a 500: the other
     * caller is almost always the same clerk double-clicking, or the clerk at the
     * next desk working the same pile.</p>
     */
    private Cheque lock(UUID chequeId) {
        Cheque cheque;
        try {
            cheque = chequeRepository.findByIdForUpdate(chequeId)
                    .orElseThrow(() -> new NotFoundException("Cheque not found"));
        } catch (PessimisticLockingFailureException e) {
            throw new BusinessRuleViolationException(BEING_UPDATED);
        }
        requireSameTenant(cheque);
        return cheque;
    }

    private static void requireSameTenant(Cheque cheque) {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(cheque.getTenantId())) {
            throw new NotFoundException("Cheque not found");
        }
    }

    /**
     * The lease row, locked, tenant-checked — taken by every path that adds a row
     * to the register, because a new row's position is computed as max+1 over the
     * lease's existing rows.
     */
    private Lease lockLease(UUID leaseId) {
        Lease lease;
        try {
            lease = leaseRepository.findByIdForUpdate(leaseId)
                    .orElseThrow(() -> new NotFoundException("Lease not found"));
        } catch (PessimisticLockingFailureException e) {
            throw new BusinessRuleViolationException(BEING_UPDATED);
        }
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(lease.getTenantId())) {
            throw new NotFoundException("Lease not found");
        }
        return lease;
    }

    /**
     * The cheque's lease, for a finance action: the caller must be entitled to
     * <em>manage</em> it, and it must be on the books.
     *
     * <p>Manage, not read. A renter passes {@code requireReadable} for their own
     * lease — it is their contract — which would have let them mark their own
     * cheque cleared or bounce it back off their statement.</p>
     *
     * <p>Both checks happen before the status guard on purpose: a REGISTERED row on
     * a lease someone reverted to DRAFT would otherwise deposit and clear happily
     * against a receivable the ledger never raised.</p>
     */
    private Lease managedLeaseOf(Cheque cheque) {
        Lease lease = cheque.getLease();
        if (lease == null) throw new NotFoundException("Lease not found");
        leaseAccessPolicy.requireManageable(lease);
        return requirePosted(lease);
    }

    /**
     * The same lease for the payment-gateway path, which has three possible
     * callers and only one of them is staff.
     *
     * <ul>
     *   <li><b>A manager</b> starting the payment for a renter at the counter:
     *       passes on {@code canManage}.</li>
     *   <li><b>The renter</b>, in their own portal, paying their own instalment:
     *       passes only for a lease that is theirs — which is exactly what
     *       {@code requireReadable} decides for a renter. This is the one place a
     *       renter may move a register row, and all they can do with it is pay.</li>
     *   <li><b>The gateway's webhook</b>, which carries no user at all: allowed,
     *       because there is nobody to authorise. That is not a hole — an
     *       unauthenticated HTTP request never reaches a service (see
     *       {@code ApiSecurityFilter}), so a null SecurityContext here means an
     *       internal caller, and the webhook's own authorisation is its signature
     *       check (Task 10).</li>
     * </ul>
     */
    private Lease gatewayLeaseOf(Cheque cheque) {
        Lease lease = cheque.getLease();
        if (lease == null) throw new NotFoundException("Lease not found");
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && !leaseAccessPolicy.canManage(lease)) {
            leaseAccessPolicy.requireReadable(lease);
        }
        return requirePosted(lease);
    }

    private static Lease requirePosted(Lease lease) {
        if (!POSTED.contains(lease.getStatus())) {
            throw new BusinessRuleViolationException(
                    "This cheque's lease is " + lease.getStatus()
                            + "; post the lease before changing its register rows.");
        }
        return lease;
    }

    /** "Can only clear cheques in DEPOSITED (current: REGISTERED)" — spec §7.2's refusal. */
    private static void requireStatus(Cheque cheque, String verb, ChequeStatus... allowed) {
        for (ChequeStatus s : allowed) {
            if (cheque.getStatus() == s) return;
        }
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < allowed.length; i++) {
            if (i > 0) names.append(i == allowed.length - 1 ? " or " : ", ");
            names.append(allowed[i]);
        }
        throw new BusinessRuleViolationException(
                "Can only " + verb + " cheques in " + names + " (current: " + cheque.getStatus() + ")");
    }

    /**
     * A replaced penalty receipt is still the same fine.
     *
     * <p>Approving a penalty raises a {@code PEN} and puts a collection row on the
     * register for it, and two things key off that link. The assessment is
     * outstanding while its {@code collectionCheque} has not CLEARED, and the
     * settlement preview leaves rows carrying a {@code penaltyAssessmentId} out of
     * arrears precisely so the fine is not charged to the deposit twice.</p>
     *
     * <p>Replacing that row broke both at once. The replacement carried no link, so
     * it landed in arrears as ordinary rent, while the assessment went on pointing
     * at a BOUNCED row that would never clear and went on counting in penalties:
     * one 500 fine, deducted twice from the renter's deposit, with no screen
     * showing why.</p>
     *
     * <p><b>Every replacement inherits the id; only the first becomes the
     * assessment's collection row.</b> The inheritance is what keeps them all out
     * of arrears — one fine cannot become three rent debts because it was settled
     * in instalments — and the assessment has a single column, so the first row is
     * the one it can name. A fine split across several replacements therefore reads
     * as collected once the first of them clears, which is a real limitation and an
     * unlikely shape: a collection row is raised for one amount and replaced by one
     * instrument for the same amount.</p>
     *
     * <p>{@code PenaltyAssessmentService.reverse} keeps working against whatever
     * this points at: it reads {@code getCollectionCheque()} and refuses on CLEARED,
     * cancels on REGISTERED. The re-pointed replacement is REGISTERED the moment it
     * is created, which is exactly the state reverse wants to cancel.</p>
     *
     * <p>A no-op for the ordinary case: a rent cheque carries no assessment id and
     * this does not touch the database.</p>
     */
    private void repointPenaltyCollection(Cheque bounced, Cheque replacement) {
        UUID assessmentId = bounced.getPenaltyAssessmentId();
        if (assessmentId == null) {
            return;
        }
        penaltyAssessments.findById(assessmentId).ifPresent(assessment -> {
            // Only if this row really is the one the assessment is collected
            // through. A penalty raised *about* a bounced rent cheque also carries
            // the link the other way round (assessment.cheque), and re-pointing on
            // that would make the fine collectable through the rent replacement.
            Cheque collection = assessment.getCollectionCheque();
            if (collection != null && collection.getId().equals(bounced.getId())) {
                assessment.setCollectionCheque(replacement);
                penaltyAssessments.save(assessment);
            }
        });
    }

    private static void requireDepositable(Cheque cheque) {
        if (cheque.getMode() != ChequeMode.PDC) {
            throw new BusinessRuleViolationException(
                    "Only a post-dated cheque can be deposited; " + label(cheque)
                            + " is a " + cheque.getMode() + " receipt. Record it as received instead.");
        }
    }

    /** Where a receipt of this kind lands when the row names no account of its own. */
    private static AccountRole settlementRole(ChequeMode mode) {
        return mode == ChequeMode.CASH ? AccountRole.CASH : AccountRole.BANK;
    }

    private static void moveTo(Cheque cheque, ChequeStatus status, String notes) {
        cheque.setStatus(status);
        // The clock, not the transition's date: the date columns say when the money
        // moved, statusChangedAt says when we were told.
        cheque.setStatusChangedAt(Instant.now());
        appendNote(cheque, notes);
    }

    /**
     * Notes accumulate; they are not a field the last transition owns.
     *
     * <p>A cheque that bounced with "insufficient funds" and was then replaced with
     * "renter paying by transfer" has two facts about it, and overwriting the first
     * with the second threw away the only free-text record of why the instrument
     * failed. Each line is dated so the column reads as a log.</p>
     */
    private static void appendNote(Cheque cheque, String note) {
        if (note == null || note.isBlank()) return;
        String stamped = LocalDate.now() + ": " + note.trim();
        String existing = cheque.getNotes();
        cheque.setNotes(existing == null || existing.isBlank() ? stamped : existing + "\n" + stamped);
    }

    private static String reasonOr(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason;
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private Account account(UUID id) {
        Account a = accountRepository.findById(id)
                .orElseThrow(() -> new BusinessRuleViolationException("Account " + id + " does not exist"));
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(a.getTenantId())) {
            // Another tenant's account, which to this one simply does not exist.
            throw new BusinessRuleViolationException("Account " + id + " does not exist");
        }
        return a;
    }

    private Account settlementAccount(UUID id) {
        return requireSettlementAccount(account(id));
    }

    /**
     * An account cleared funds may actually land in.
     *
     * <p>Without this, {@code debitAccountId} was a free hand into the chart of
     * accounts: passing the property's rent-receivable leaf would debit the
     * receivable the cheque was raised against, silently doubling the debt and
     * showing the money as still owed rather than as collected. The rule is the
     * narrow one the column means — an active asset leaf whose sub-type is BANK or
     * CASH — and it is applied to the caller's override, to the account already on
     * the row, and to a row's account as it is created.</p>
     *
     * <p>Public because the gateway's settlement account is the same question asked
     * at configuration time rather than at clearing time: {@code TenantGatewayConfigService}
     * validates the account an organisation nominates for Razorpay payouts through
     * this very check (and then narrows it to BANK), so the account a capture may
     * debit can never be one this would refuse.</p>
     */
    public static Account requireSettlementAccount(Account a) {
        if (a == null) return null;
        boolean settles = !a.isGroup() && a.isActive()
                && a.getAccountType() == AccountType.ASSET
                && (a.getAccountSubType() == AccountSubType.BANK || a.getAccountSubType() == AccountSubType.CASH);
        if (!settles) {
            throw new BusinessRuleViolationException(
                    "Debit account " + a.getCode() + " must be a bank or cash account");
        }
        return a;
    }

    /** Cheque numbers already live on the lease — everything a new row may not reuse. */
    private static Set<String> takenNumbers(List<Cheque> register) {
        Set<String> taken = new HashSet<>();
        for (Cheque c : register) {
            if (c.getMode() == ChequeMode.PDC && c.getChequeNumber() != null) {
                taken.add(c.getChequeNumber());
            }
        }
        return taken;
    }

    /**
     * The next position on the register. Safe only under the lease row lock — see
     * {@link #lockLease}.
     */
    private static int nextSeqNo(List<Cheque> register) {
        return register.stream().mapToInt(Cheque::getSeqNo).max().orElse(0) + 1;
    }

    /** "12,750.00" — the shape an accountant reads amounts in, in the refusal messages. */
    private static String money(BigDecimal amount) {
        return String.format(Locale.ROOT, "%,.2f", amount);
    }

    private static String label(Cheque c) {
        return c.getChequeNumber() != null && !c.getChequeNumber().isBlank()
                ? c.getChequeNumber() : "row " + c.getSeqNo();
    }

    private static ChequeDTO dto(Cheque cheque, Lease lease) {
        return ChequeMapper.toDto(cheque, LocalDate.now(), lease.getGracePeriodDays());
    }

    // ------------------------------------------------------------------
    // side effects
    // ------------------------------------------------------------------

    /**
     * A line in the lease's own history for the transitions somebody will later ask
     * about: a returned cheque, what replaced it, a cancellation, paper handed back.
     *
     * <p>Not written for deposit, clear or receive — those are the register doing
     * what it is for, and a row per instalment per month would bury the four events
     * that actually need explaining. The lease's status does not change, so previous
     * and new state are both what it already is; {@code LeaseService} records
     * ordinary edits the same way.</p>
     *
     * <p>Inside the transition's own transaction and deliberately not wrapped in a
     * try/catch: an insert that fails has already marked the transaction
     * rollback-only, so swallowing it would only turn a clear failure into an
     * {@code UnexpectedRollbackException} at commit time.</p>
     */
    private void recordLeaseEvent(Lease lease, Cheque cheque, String what) {
        LeaseEvent event = new LeaseEvent();
        event.setLease(lease);
        event.setTenantId(lease.getTenantId());
        event.setPreviousState(lease.getStatus());
        event.setNewState(lease.getStatus());
        event.setNotes("Cheque " + label(cheque) + ": " + what);
        event.setCreatedAt(Instant.now());
        leaseEventRepository.save(event);
    }

    private void publishDeposited(Cheque cheque) {
        publish(EmailEventType.CHEQUE_DEPOSITED, cheque,
                ChequePayload.ofCheque(cheque, cheque.getDepositedAt(), null));
    }

    private void publishCleared(Cheque cheque) {
        publish(EmailEventType.CHEQUE_CLEARED, cheque,
                ChequePayload.ofCheque(cheque, cheque.getDepositedAt(), null));
    }

    /**
     * The dedupe key names the transition and the row, so a retried request cannot
     * send the same renter two "your cheque cleared" emails — and so a cheque that
     * bounces, is replaced and bounces again still gets an email each time, because
     * those are different rows.
     */
    private void publish(EmailEventType type, Cheque cheque, ChequePayload payload) {
        events.publishEvent(new EmailEvent(this, type, cheque.getTenantId(), payload,
                type.name() + ":" + cheque.getId()));
    }

    /**
     * The in-app row only — {@code notifyInAppInNewTx}, not {@code notify}: the
     * structured {@link EmailEvent} is published above, and {@code notify}'s legacy
     * mapping would send the renter a second copy of the same email. REQUIRES_NEW
     * so a failed notification row cannot mark the transition's transaction
     * rollback-only from inside the catch block.
     */
    private void notifyRenter(Cheque cheque, String type, String title, String message) {
        try {
            UUID renterUserId = cheque.getRenter() != null ? cheque.getRenter().getUserId() : null;
            if (renterUserId != null) {
                notificationService.notifyInAppInNewTx(cheque.getTenantId(), renterUserId,
                        type, title, message, "CHEQUE", cheque.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to send {} notification for cheque {}: {}", type, cheque.getId(), e.getMessage());
        }
    }
}
