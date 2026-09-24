package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.core.notification.NotificationMessage;
import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.cheque.ClearBatchRequest;
import com.datagami.rentaxis.api.dto.cheque.DepositBatchRequest;
import com.datagami.rentaxis.api.dto.cheque.ReplaceChequeRequest;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.RowLockedException;
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.ChequePayload;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.NotificationService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Line;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.vat.VatTaxPointService;
import com.datagami.rentaxis.core.service.lease.LeaseChequeRegistrar;
import com.datagami.rentaxis.core.service.lease.LeaseClosureService;
import com.datagami.rentaxis.core.service.penalty.PenaltyRuleEngine;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.core.service.bank.OwnedBankLeaf;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
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
     * A lease whose contract is on the books <em>and still running</em> — the set
     * that may take a new register row typed by a user.
     *
     * <p>A DRAFT lease's rows are a proposal, and moving one through the register
     * would clear money against a receivable nobody has raised. A contract that has
     * <em>ended</em> is the other end of the same rule: TERMINATED and EXPIRED both
     * mean the tenancy is over, so neither grows new instalments — what the renter
     * still owes is collected through the settlement (spec §9.2), which has its own
     * door in {@link #addCollectionRow}.</p>
     *
     * <p><b>RENEWED is in</b> because it is not an ending: the predecessor of a
     * posted successor is still owed its own last instalments, and nothing about the
     * renewal chain says otherwise.</p>
     *
     * <p>EXPIRED was here until review I2. It was kept because approving a penalty
     * on an expired lease raises its collection row through this method — true, and
     * an argument for giving that path the internal door rather than for leaving a
     * user-facing endpoint ({@code POST /cheques/lease/{id}/cash-receipt}) open on an
     * ended contract.</p>
     */
    /**
     * A transition that is being <em>replayed</em> out of a cut-over import rather
     * than made now (spec §10.3, controller ruling R4).
     *
     * <p>One object, because the two things it decides are one decision and must
     * not drift apart:</p>
     * <ul>
     *   <li><b>The journal carries {@code batchId}</b>, which is what exempts it
     *       from the period lock — every cut-over date is inside a closed month by
     *       definition — and what lets "Reverse batch" find it again.</li>
     *   <li><b>Nothing is announced and nothing is proposed.</b> A cheque that
     *       bounced last March already cost the renter a fine in PACT and already
     *       produced whatever conversation it was going to; re-proposing it would
     *       put a year of settled penalties on finance's worklist on the first
     *       morning, and e-mailing "your cheque cleared" for a payment made eight
     *       months ago is a message with no possible use. The register still records
     *       what happened — the dates, the statuses, the lease-event line for a
     *       bounce — because that history is the thing being migrated.</li>
     * </ul>
     *
     * <p>A parameter rather than a thread-local or a mutable flag on the service:
     * the fact belongs to the one transition it describes, and an ambient value
     * would silence whatever the same thread happened to do next.</p>
     *
     * <p>{@code null} everywhere a user is driving. Every public no-{@code Replay}
     * overload below is that door.</p>
     */
    public record Replay(UUID batchId) {
        public Replay {
            if (batchId == null) {
                throw new IllegalArgumentException("A replay must name the import batch it belongs to");
            }
        }

        /** The batch id to stamp on a journal, or null when this is an ordinary action. */
        static UUID batchIdOf(Replay replay) {
            return replay == null ? null : replay.batchId();
        }
    }

    private static final Set<LeaseStatus> POSTED = EnumSet.of(
            LeaseStatus.ACTIVE, LeaseStatus.NOTICE_GIVEN, LeaseStatus.RENEWED);

    /**
     * A lease whose <em>existing</em> rows may still move — which is every posted
     * lease plus a terminated one.
     *
     * <p><b>Money owed stays collectable.</b> Spec §9.1's keep list leaves uncleared
     * instruments dated on or before {@code T} on the register of a TERMINATED lease
     * precisely so they can be banked, and §9.2 raises a CASH row on that same lease
     * for a balance the deposit could not cover. Both are claims against a debt the
     * renter genuinely owes; refusing to deposit, clear, receive, bounce, replace,
     * hand back or cancel them would make the register a place money goes to be
     * forgotten.</p>
     *
     * <p><b>CLOSED is not here, and that is the point.</b> A closed contract has a
     * finalised settlement and nothing outstanding (see
     * {@link com.datagami.rentaxis.core.service.lease.LeaseClosureService}); a
     * transition on it would be a movement after the books on that tenancy were
     * shut.</p>
     */
    private static final Set<LeaseStatus> COLLECTABLE = EnumSet.of(
            LeaseStatus.ACTIVE, LeaseStatus.NOTICE_GIVEN, LeaseStatus.EXPIRED,
            LeaseStatus.RENEWED, LeaseStatus.TERMINATED);

    /**
     * A tenancy that has ended, and might therefore be finished with the moment its
     * books go flat — the cheap pre-check in {@link #closeIfThisWasTheLastOne}
     * before {@link LeaseClosureService} is asked the real question. It mirrors that
     * class's own {@code ENDED} set, RENEWED included (spec §6.6).
     */
    private static final Set<LeaseStatus> COULD_CLOSE =
            EnumSet.of(LeaseStatus.TERMINATED, LeaseStatus.EXPIRED, LeaseStatus.RENEWED);

    /**
     * A contract that has <b>ended</b> and may therefore still acquire the one row
     * nobody typed: a settlement's balance due. Its register is closed to everything
     * else.
     *
     * <p>Named for what it gates rather than for the settlement, because
     * {@code SettlementService} has a set of the same old name with <em>different</em>
     * members — RENEWED is settleable there (spec §6.6) and needs no entry here,
     * since {@link #POSTED} already admits it. Two sets called SETTLEABLE that are
     * not the same set is how a reader concludes they are.</p>
     *
     * <p><b>CLOSED is not here</b> (review M-3). Closure requires a FINALIZED
     * settlement, so a CLOSED lease cannot be the one raising a balance-due row;
     * and if it somehow were, the row it created could never be received — every
     * transition on a closed contract is refused, which is a collection row nobody
     * can collect. RENEWED needs no entry either: a predecessor settled instead of
     * carrying its deposit forward (spec §6.6) can owe a balance like any other, and
     * {@link #POSTED} already admits it.</p>
     */
    private static final Set<LeaseStatus> ENDED_BUT_COLLECTABLE = EnumSet.of(
            LeaseStatus.TERMINATED, LeaseStatus.EXPIRED);

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
    private final LeaseClosureService closure;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    private final OwnedBankLeaf ownedBankLeaf;
    private final Clock clock;

    /**
     * The VAT tax point hooks (spec 2026-09-24 §1): an early receipt moves and posts
     * the instalment's tax point, and a cancel has to say where its undeclared VAT goes.
     */
    private final VatTaxPointService vatTaxPoints;
    /** Finance-ops spec §4: the per-bank lock, checked before a clearing or a bounce does any work. */
    private final com.datagami.rentaxis.core.service.ledger.BankLockService bankLock;

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
                         LeaseClosureService closure,
                         ApplicationEventPublisher events,
                         EntityManager entityManager,
                         Clock clock,
                         VatTaxPointService vatTaxPoints,
                         com.datagami.rentaxis.core.service.ledger.BankLockService bankLock,
                         OwnedBankLeaf ownedBankLeaf) {
        this.bankLock = bankLock;
        this.ownedBankLeaf = ownedBankLeaf;
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
        this.closure = closure;
        this.events = events;
        this.entityManager = entityManager;
        this.clock = clock;
        this.vatTaxPoints = vatTaxPoints;
    }

    /**
     * The most rows one batch call may carry (PR #340 review I2). Each row is
     * locked up front and, on a clear, posts a journal, all inside one
     * transaction that holds every lock until commit; an unbounded selection is a
     * long transaction that refuses every other action on those cheques, and past
     * the driver's 32,767 bind parameters it is a 500. A deposit slip holds far
     * fewer than this.
     */
    static final int MAX_BATCH = 500;

    private static List<UUID> batchIds(List<UUID> chequeIds, String verb) {
        if (chequeIds == null || chequeIds.isEmpty()) {
            throw new BusinessRuleViolationException("Select at least one cheque to " + verb);
        }
        if (chequeIds.size() > MAX_BATCH) {
            throw new BusinessRuleViolationException("Select at most " + MAX_BATCH + " cheques to " + verb
                    + " at a time; " + chequeIds.size() + " were selected, and nothing was changed.");
        }
        // Distinct, because the same row ticked twice would otherwise be reported
        // as its own duplicate and would be saved twice.
        return chequeIds.stream().distinct().toList();
    }

    /**
     * Whether a batch row is outside what the caller may manage. Such a row is
     * reported exactly as a missing id is ("does not exist"), before anything
     * that names it: a property manager holding a UUID from another building must
     * not learn its cheque number or status from the refusal (PR #340 review M1).
     * A row with no lease cannot be placed in anyone's scope, so a restricted
     * caller gets the same answer for it.
     */
    private boolean outOfScope(Lease lease) {
        return lease == null ? leaseAccessPolicy.isRestricted() : !leaseAccessPolicy.canManage(lease);
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
        return deposit(chequeId, request, null);
    }

    /**
     * The same banking, optionally as a {@link Replay} of what PACT recorded.
     *
     * <p>Nothing posts either way, so the batch id has nothing to stamp here; what
     * the replay changes is that the renter is not told their cheque went to the
     * bank eight months ago.</p>
     */
    @Transactional
    public ChequeDTO deposit(UUID chequeId, ChequeActionRequest request, Replay replay) {
        ChequeActionRequest r = request == null ? ChequeActionRequest.empty() : request;
        Cheque cheque = lock(chequeId);
        Lease lease = managedLeaseOf(cheque);
        requireStatus(cheque, "deposit", ChequeStatus.REGISTERED);
        requireDepositable(cheque);
        if (replay == null) {
            requireNotPresentedEarly(cheque, r.dateOrToday());
        }

        applyDeposit(cheque, r.dateOrToday(), r.debitAccountId(), r.notes());
        chequeRepository.save(cheque);
        if (replay == null) {
            publishDeposited(cheque);
        }
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
        List<UUID> ids = batchIds(request == null ? null : request.chequeIds(), "deposit");

        List<Cheque> cheques;
        try {
            cheques = chequeRepository.findAllByIdForUpdate(ids);
        } catch (PessimisticLockingFailureException e) {
            throw new RowLockedException(BEING_UPDATED);
        }
        Map<UUID, Cheque> byId = new LinkedHashMap<>();
        for (Cheque c : cheques) {
            requireSameTenant(c);
            byId.put(c.getId(), c);
        }
        // Read before the loop so the rows can be checked against the date the
        // clerk actually chose, not just against each other. With
        // useChequeDates (#10) that is each row's own cheque date instead.
        LocalDate today = LocalDate.now();
        List<String> problems = new ArrayList<>();
        for (UUID id : ids) {
            Cheque c = byId.get(id);
            if (c == null || outOfScope(c.getLease())) {
                problems.add(id + " does not exist");
                continue;
            }
            Lease lease = c.getLease();
            if (lease == null) {
                problems.add(label(c) + " belongs to no lease");
            } else if (!COLLECTABLE.contains(lease.getStatus())) {
                // Named by status rather than "not posted": on this screen the row
                // the clerk has to pull out of the pile is usually one whose lease
                // is CLOSED, not one that was never posted.
                problems.add(label(c) + " belongs to a lease that is " + lease.getStatus());
            } else if (c.getStatus() != ChequeStatus.REGISTERED) {
                problems.add(label(c) + " is " + c.getStatus());
            } else if (c.getMode() != ChequeMode.PDC) {
                problems.add(label(c) + " is a " + c.getMode() + " receipt, not a cheque");
            } else if (Boolean.TRUE.equals(request.useChequeDates()) && c.getChequeDate() != null
                    && c.getChequeDate().isAfter(today)) {
                // Its own date is still to come: it cannot have been banked, and
                // banking it today would be the early presentation below.
                problems.add(label(c) + " is dated " + c.getChequeDate()
                        + ", which has not arrived yet — it cannot have been banked on its own date.");
            } else if (c.getChequeDate() != null && request.dateFor(c.getChequeDate()).isBefore(c.getChequeDate())) {
                // One date covers the whole selection, so a clerk banking
                // October's pile can easily sweep up a November cheque.
                problems.add(earlyPresentation(c, request.dateFor(c.getChequeDate())));
            }
        }
        if (!problems.isEmpty()) {
            // Named, and nothing changed: the clerk is holding the pile and needs to
            // know which piece of paper to pull out of it.
            throw new BusinessRuleViolationException(
                    "These cheques cannot be deposited: " + String.join("; ", problems)
                            + ". Only REGISTERED post-dated cheques can be banked, and nothing was deposited.");
        }

        List<ChequeDTO> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Cheque c = byId.get(id);
            Lease lease = managedLeaseOf(c);
            applyDeposit(c, request.dateFor(c.getChequeDate()), request.debitAccountId(), null);
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
     * One bank credit, cleared together (gap #57): the mirror of
     * {@link #depositBatch}.
     *
     * <p>All or nothing, for the same reason: a statement line either matches the
     * register or it does not. Every id is locked and checked up front, so a row
     * that is not DEPOSITED fails the call by name before anything posts. Each row
     * is then cleared through {@link #clear(UUID, ChequeActionRequest, Replay)}
     * itself — the same {@code CRT}, late-payment hook, notification and closure
     * check a single clear runs — inside this one transaction, so a failure on any
     * row rolls back every clearance before it.</p>
     */
    @Transactional
    public List<ChequeDTO> clearBatch(ClearBatchRequest request) {
        List<UUID> ids = batchIds(request == null ? null : request.chequeIds(), "clear");
        // One date covers every row, so it is checked against every row (review M2).
        // Not after today on the app clock (Asia/Dubai): a future clearing would
        // also hand the late-payment rule a day that has not happened yet.
        LocalDate today = LocalDate.now(clock);
        LocalDate date = request.clearingDate() != null ? request.clearingDate() : today;
        if (date.isAfter(today)) {
            throw new BusinessRuleViolationException("The clearing date " + date
                    + " is in the future. Funds cannot have cleared yet, and nothing was cleared.");
        }

        List<Cheque> cheques;
        try {
            cheques = chequeRepository.findAllByIdForUpdate(ids);
        } catch (PessimisticLockingFailureException e) {
            throw new RowLockedException(BEING_UPDATED);
        }
        Map<UUID, Cheque> byId = new LinkedHashMap<>();
        for (Cheque c : cheques) {
            requireSameTenant(c);
            byId.put(c.getId(), c);
        }
        List<String> problems = new ArrayList<>();
        for (UUID id : ids) {
            Cheque c = byId.get(id);
            if (c == null || outOfScope(c.getLease())) {
                problems.add(id + " does not exist");
                continue;
            }
            Lease lease = c.getLease();
            if (lease == null) {
                problems.add(label(c) + " belongs to no lease");
            } else if (!COLLECTABLE.contains(lease.getStatus())) {
                problems.add(label(c) + " belongs to a lease that is " + lease.getStatus());
            } else if (c.getStatus() != ChequeStatus.DEPOSITED) {
                problems.add(label(c) + " is " + c.getStatus());
            } else if (c.getDepositedAt() != null && date.isBefore(c.getDepositedAt())) {
                // A clearance dated before the deposit writes a CRT the bank
                // statement can never have shown.
                problems.add(label(c) + " was deposited on " + c.getDepositedAt()
                        + ", after the clearing date " + date);
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(
                    "These cheques cannot be cleared: " + String.join("; ", problems)
                            + ". Only DEPOSITED cheques can be cleared, on or after the day they were"
                            + " deposited, and nothing was cleared.");
        }

        ChequeActionRequest each = new ChequeActionRequest(
                date,
                request.narration() == null || request.narration().isBlank() ? null : request.narration().trim(),
                null, null);
        List<ChequeDTO> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            out.add(clear(id, each, null));
        }
        return out;
    }

    /**
     * The bank confirmed it: {@code CRT} Dr the bank / Cr PDC receivable. The
     * instrument stops being a claim and becomes money.
     */
    @Transactional
    public ChequeDTO clear(UUID chequeId, ChequeActionRequest request) {
        return clear(chequeId, request, null);
    }

    /**
     * The same clearing, optionally as a {@link Replay}: the {@code CRT} carries the
     * batch id, and the late-payment hook, the renter's e-mail and the
     * did-this-close-the-tenancy question are all left alone.
     *
     * <p>The close hook in particular would be answered "no" anyway — a lease the
     * bulk post has just activated is ACTIVE, not ended — but a cut-over has no
     * business finishing a tenancy off, and saying so costs one status read less.</p>
     */
    @Transactional
    public ChequeDTO clear(UUID chequeId, ChequeActionRequest request, Replay replay) {
        ChequeActionRequest r = request == null ? ChequeActionRequest.empty() : request;
        Cheque cheque = lock(chequeId);
        Lease lease = managedLeaseOf(cheque);
        requireStatus(cheque, "clear", ChequeStatus.DEPOSITED);

        applyClearing(lease, cheque, r.dateOrToday(), r.debitAccountId(), r.notes(), replay);
        chequeRepository.save(cheque);
        if (replay != null) {
            return dto(cheque, lease);
        }
        // A cheque cleared after its grace period is a late payment. Inside this
        // transaction on purpose: "the money arrived late" and "finance should look
        // at a late fee" are one fact, and a proposal that failed to write while the
        // clearing committed would lose it silently.
        penaltyRules.onLateClear(cheque, r.dateOrToday());
        publishCleared(cheque);
        closeIfThisWasTheLastOne(lease, "the last instrument cleared");
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
        return receive(chequeId, request, null);
    }

    /** The same receipt, optionally as a {@link Replay} — see {@link #clear(UUID, ChequeActionRequest, Replay)}. */
    @Transactional
    public ChequeDTO receive(UUID chequeId, ChequeActionRequest request, Replay replay) {
        ChequeActionRequest r = request == null ? ChequeActionRequest.empty() : request;
        Cheque cheque = lock(chequeId);
        Lease lease = managedLeaseOf(cheque);
        requireStatus(cheque, "receive", ChequeStatus.REGISTERED);
        if (cheque.getMode() != ChequeMode.CASH && cheque.getMode() != ChequeMode.TRANSFER) {
            throw new BusinessRuleViolationException(
                    "Only a CASH or TRANSFER receipt can be received directly; " + label(cheque)
                            + " is a " + cheque.getMode() + " row. Deposit it and clear it instead.");
        }

        applyClearing(lease, cheque, r.dateOrToday(), r.debitAccountId(), r.notes(), replay, true);
        chequeRepository.save(cheque);
        if (replay != null) {
            return dto(cheque, lease);
        }
        // Cash over the counter reaches CLEARED by a different door, but it is the
        // same fact: the money arrived, and it may have arrived late. Leaving the
        // hook on clear() alone made the late fee depend on which door the renter
        // happened to pay through.
        penaltyRules.onLateClear(cheque, r.dateOrToday());
        publishCleared(cheque);
        closeIfThisWasTheLastOne(lease, "the last receipt was taken");
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
        return bounce(chequeId, request, null);
    }

    /**
     * The same return, optionally as a {@link Replay}: the {@code CBR} carries the
     * batch id, and no penalty is proposed, no e-mail sent and no in-app
     * notification raised.
     *
     * <p><b>The lease-event line stays.</b> It is the record that this instrument
     * was returned, which is exactly what the migration is carrying over; what must
     * not happen is the renter being told about it again, or finance being asked a
     * second time about a fine PACT has already dealt with (spec §10.3 — the cut-over
     * copies history, it does not re-live it).</p>
     */
    @Transactional
    public ChequeDTO bounce(UUID chequeId, ChequeActionRequest request, Replay replay) {
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
            if (banked != null) bankLock.assertOpen(List.of(banked.getId()), date);
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
                Replay.batchIdOf(replay),
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
        if (replay != null) {
            return dto(cheque, lease);
        }

        publish(EmailEventType.CHEQUE_BOUNCED, cheque,
                ChequePayload.ofCheque(cheque, null,
                        r.failureReason() != null ? r.failureReason().name() : null));
        notifyRenter(cheque, "PAYMENT_BOUNCED", "Cheque Failed",
                "Instalment #" + cheque.getSeqNo() + " of " + money(amount)
                        + " AED was returned" + (r.failureReason() != null ? " (" + r.failureReason() + ")" : "")
                        + ". Please arrange a replacement.",
                NotificationMessage.of(r.failureReason() != null ? "CHEQUE_RETURNED_REASON" : "CHEQUE_RETURNED",
                        "seq", cheque.getSeqNo(), "amount", amount, "reason", r.failureReason(),
                        "chequeNo", cheque.getChequeNumber()));
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
        requireNotAlreadySettled(lease, bounced);

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
        // Asked, though a replacement is itself outstanding and the answer is
        // therefore always "no": the rule is "every transition that takes a row out
        // of the outstanding set ends by asking", and a future replacement that
        // registers nothing must not be the one case that silently does not.
        closeIfThisWasTheLastOne(lease, "the last instrument was replaced");
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
        Lease lease = requireCollectable(gatewayLeaseOf(bounced));
        lockLease(lease.getId());
        requireStatus(bounced, "replace", ChequeStatus.BOUNCED);
        // The renter's own portal offers this door, so it needs the same rule the
        // clerk's does: a bounce the settlement absorbed must not be paid again.
        requireNotAlreadySettled(lease, bounced);

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
        return cancel(chequeId, request, null);
    }

    /**
     * The same cancellation, moving the row's undeclared VAT onto another pending
     * instalment of the lease ({@code moveVatToChequeId}). A row carrying PLANNED VAT
     * cannot be cancelled without naming one: its share would be stranded in the
     * deferred account (spec 2026-09-24 §1).
     */
    @Transactional
    public ChequeDTO cancel(UUID chequeId, ChequeActionRequest request, UUID moveVatToChequeId) {
        ChequeActionRequest r = request == null ? ChequeActionRequest.empty() : request;
        // Both rows are claimed in id order, so two cancels that move VAT onto each
        // other's row cannot cross (re-review N3).
        if (moveVatToChequeId != null && moveVatToChequeId.compareTo(chequeId) < 0) lock(moveVatToChequeId);
        Cheque cheque = lock(chequeId);
        Lease lease = managedLeaseOf(cheque);
        requireStatus(cheque, "cancel", ChequeStatus.REGISTERED);
        requireSettlementUndisturbed(lease, cheque);
        vatTaxPoints.beforeCancel(cheque, moveVatToChequeId);

        reversePdr(cheque, r.dateOrToday(), reasonOr(r.notes(), "Cheque cancelled"));
        moveTo(cheque, ChequeStatus.CANCELLED, r.notes());
        chequeRepository.save(cheque);
        recordLeaseEvent(lease, cheque, "cancelled and its registration reversed"
                + (r.notes() != null && !r.notes().isBlank() ? " — " + r.notes().trim() : ""));
        closeIfThisWasTheLastOne(lease, "the last instrument was cancelled");
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
        requireSettlementUndisturbed(lease, cheque);

        LocalDate on = date != null ? date : LocalDate.now();
        reversePdr(cheque, on, reasonOr(reason, "Cheque returned to tenant"));
        cheque.setReturnedAt(on);
        moveTo(cheque, ChequeStatus.RETURNED, reason);
        chequeRepository.save(cheque);
        recordLeaseEvent(lease, cheque, "handed back to the tenant"
                + (reason != null && !reason.isBlank() ? " — " + reason.trim() : ""));
        closeIfThisWasTheLastOne(lease, "the last instrument was handed back");
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
        return addRow(lease, row);
    }

    /**
     * The rows a lease may acquire that <em>nobody typed</em>: a settlement balance
     * the deposit could not cover (spec §9.2) and an approved penalty's collection
     * row (spec §7.3).
     *
     * <p><b>Not a widening of {@link #addRowToPostedLease}.</b> That method refuses a
     * contract that has ended on purpose, and every user-facing door into the
     * register goes through it. These two are the legitimate exceptions and they
     * share a shape: the system — not a user — raises an instrument for a debt that
     * already exists in the ledger, at the moment it is raised there. A settlement's
     * shortfall is already sitting in rent receivable; an approved penalty's
     * {@code PEN} debited it a line earlier. Neither can be an invented instalment,
     * because neither amount is the caller's to choose.</p>
     *
     * <p>Restricted to {@link #POSTED} ∪ {@link #ENDED_BUT_COLLECTABLE} rather than "any
     * status", so it cannot become the back door either — a DRAFT lease is refused
     * here exactly as it is there. Its two callers,
     * {@code SettlementService.finalizeSettlement} and
     * {@code PenaltyAssessmentService.approve}, each run their own narrower rule
     * first ({@code SettlementService.SETTLEABLE} and {@code CHARGEABLE}
     * respectively) — the former being the set this one was renamed away from.</p>
     *
     * <p>The row is ordinary in every other way: the same {@code PDR}, the same
     * lifecycle, the same clearing rules — the renter pays a fine on an expired
     * lease through {@link #receive} like any other receipt.</p>
     */
    @Transactional
    public ChequeDTO addCollectionRow(UUID leaseId, ChequeRowInput row) {
        Lease lease = lockLease(leaseId);
        leaseAccessPolicy.requireManageable(lease);
        if (!POSTED.contains(lease.getStatus()) && !ENDED_BUT_COLLECTABLE.contains(lease.getStatus())) {
            throw new BusinessRuleViolationException(
                    "This lease is " + lease.getStatus() + "; a collection row needs a lease that is on the books.");
        }
        return addRow(lease, row);
    }

    /**
     * The transition that finishes a contract off (spec §9.1–§9.2).
     *
     * <p>Called from <b>every path that takes a row out of the outstanding set</b>,
     * not only the ones that collect money: {@link #clear}, {@link #receive} and
     * {@link #clearOnline} put a row into CLEARED, {@link #returnToTenant} and
     * {@link #cancel} end the landlord's claim on it altogether, and
     * {@link #replace} supersedes it. Leaving the last two out left a lease the rule
     * says is CLOSED permanently open, with no path back — nothing remained whose
     * clearance could ask the question again (review I1). The penalty module reaches
     * this through {@code cancel}, which is how reversing a fine closes the tenancy
     * it was the last thing outstanding on.</p>
     *
     * <p>The rule itself lives in {@link LeaseClosureService}: a lease that has
     * ended, whose settlement is FINALIZED, with nothing left outstanding on its
     * register, is CLOSED. On every other lease this is one cheap status read and no
     * write.</p>
     *
     * <p>Inside the transition's own transaction, under the lease's row lock — taken
     * here, after the cheque's, which is the order {@link #replace} already
     * establishes. Without it two clerks resolving the last two rows at once could
     * each see the other's still outstanding and neither would close. The status is
     * read off the <em>locked</em> row rather than off the instance the cheque
     * carried, which may have been loaded before this transaction took any lock.</p>
     */
    private void closeIfThisWasTheLastOne(Lease lease, String reason) {
        // Asked of the database, not of the instance the cheque carried (review
        // M-1). The overwhelmingly common case — a running lease collecting its
        // rent — must not cost a row lock or a ledger read, so this stays a cheap
        // filter; but it cannot be a *stale* one. `lease` arrives through
        // `cheque.getLease()` and may have been resolved before this transition
        // began, and reading ACTIVE for a row that is now TERMINATED with its
        // settlement finalised is precisely a skipped close — the opposite of what
        // the old comment here claimed. A scalar query is not answered from the
        // first-level cache, so it always sees the committed row.
        LeaseStatus current = leaseRepository.findStatusById(lease.getId()).orElse(null);
        if (current == null || !COULD_CLOSE.contains(current)) {
            return;
        }
        Lease locked = lockLease(lease.getId());
        if (!COULD_CLOSE.contains(locked.getStatus())) {
            return;
        }
        closure.closeIfFullyCollected(locked, reason);
    }

    /** The shared body: validate against the register, register the row, post its PDR. */
    private ChequeDTO addRow(Lease lease, ChequeRowInput row) {
        // Read once: the numbers already taken and the last position come off the
        // same list, and a second query would be a second chance to disagree.
        List<Cheque> register = chequeRepository.findByLease_IdOrderBySeqNoAsc(lease.getId());
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
     * <p><b>Which rows.</b> {@link ChequeGatewayRules#payableThroughGateway} decides,
     * and it is the same method the renter's portal computes its Pay-now flag from
     * — a portal that offered what this refuses was the whole of finding I4.</p>
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
        Lease lease = requireCollectable(gatewayLeaseOf(cheque));
        if (cheque.getStatus() == ChequeStatus.BOUNCED) {
            throw new BusinessRuleViolationException("Replace the bounced cheque before paying online");
        }
        requireStatus(cheque, "start an online payment for", ChequeStatus.REGISTERED);
        if (!ChequeGatewayRules.payableThroughGateway(cheque)) {
            // Cash and bank transfers are recorded when they arrive, by receive();
            // there is nothing for a gateway to authorise. An approved penalty's
            // collection row is the exception and the rule knows it — see
            // ChequeGatewayRules.
            throw new BusinessRuleViolationException(
                    "Only a post-dated cheque, an online row or an approved penalty can be paid "
                            + "through the gateway; " + label(cheque) + " is a "
                            + cheque.getMode() + " receipt.");
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
        Lease lease = requireCollectable(gatewayLeaseOf(cheque));
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
     *
     * <p><b>It also sits above the lease-status guard</b>, which is the one place
     * {@link #requireCollectable} is not the first thing a transition does. This
     * very capture can be the clearance that closes the lease, and Razorpay will
     * redeliver the same webhook afterwards; answering that retry with "this
     * cheque's lease is CLOSED" would have the gateway retrying a 400 until it
     * gives up, over a payment that is already in the bank. Nothing is written on
     * that path — it is a read of a row that already captured.</p>
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
        requireCollectable(lease);
        requireStatus(cheque, "capture", ChequeStatus.ONLINE_PENDING);

        LocalDate on = capturedOn != null ? capturedOn : LocalDate.now();
        // The gateway's settlement account is configured per organisation and
        // validated there (TenantGatewayConfigService), not typed by a caller, so it
        // is exempt from the per-property rule applied to caller overrides below.
        if (settlementAccountId != null) {
            cheque.setDebitAccount(requireSettlementAccount(account(settlementAccountId)));
        }
        applyClearing(lease, cheque, on, null, null);
        chequeRepository.save(cheque);
        penaltyRules.onLateClear(cheque, on);
        publishCleared(cheque);
        closeIfThisWasTheLastOne(lease, "an online payment captured");
        return dto(cheque, lease);
    }

    // ------------------------------------------------------------------
    // the transitions' shared bodies
    // ------------------------------------------------------------------

    private void applyDeposit(Cheque cheque, LocalDate date, UUID debitAccountId, String notes) {
        if (debitAccountId != null) {
            // Which of our banks the paper physically went to. Recorded now so the
            // CRT that follows debits it rather than re-resolving the role.
            cheque.setDebitAccount(settlementAccount(debitAccountId, cheque.getProperty()));
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
        applyClearing(lease, cheque, date, debitAccountId, notes, null);
    }

    private void applyClearing(Lease lease, Cheque cheque, LocalDate date, UUID debitAccountId, String notes,
                               Replay replay) {
        applyClearing(lease, cheque, date, debitAccountId, notes, replay, false);
    }

    /**
     * {@code receiptSource}: the caller is {@link #receive}, which may also take the
     * money from the unidentified-receipts suspense leaf (finance-ops spec §3,
     * "clearing the suspense": {@link #isReceiptSource}). Every other door keeps
     * {@link #isSettlementAccount}.
     */
    private void applyClearing(Lease lease, Cheque cheque, LocalDate date, UUID debitAccountId, String notes,
                               Replay replay, boolean receiptSource) {
        // Live actions only (R1 P2-6): a cut-over replay records what PACT did, and
        // PACT's history is authoritative even when cash came in before it was booked.
        if (replay == null) {
            requireNotBeforeBooked(cheque, bookedOn(cheque), date);
        }
        BigDecimal amount = cheque.getAmount();
        String narration = LeaseChequeRegistrar.narrationOf(cheque);
        // Checked on the way in whichever door it came through: an override the
        // caller typed, and the account already on the row — that one was validated
        // when it was set, but a chart of accounts is edited, and a receivable leaf
        // debited here would look exactly like money in the bank on the balance sheet.
        Account debit = debitAccountId != null
                ? (receiptSource && isSuspenseLeaf(account(debitAccountId))
                        ? account(debitAccountId)
                        : settlementAccount(debitAccountId, cheque.getProperty()))
                : requireSettlementAccount(cheque.getDebitAccount());
        if (debitAccountId == null && replay == null) {
            debit = reconcilableLeaf(debit, cheque);
        }
        // No resolveOrNull fallback and no try/catch: when the row names no account
        // the role goes into the request and PostingService resolves it, so an
        // unmapped BANK is one refusal from one place.
        Line dr = debit != null
                ? PostingRequest.dr(debit.getId(), amount)
                : PostingRequest.dr(settlementRole(cheque.getMode()), amount);
        // Early, with the lock's own message: a clearing into a reconciled bank
        // leaf dated inside the reconciled period (PostingService refuses it too).
        if (debit != null) bankLock.assertOpen(List.of(debit.getId()), date);

        JournalEntry crt = postingService.post(PostingRequest.ofPairs(
                JournalDocType.CRT,
                date,
                narration,
                LeaseChequeRegistrar.dimensions(lease, cheque.getId()),
                JournalSourceType.CHEQUE,
                cheque.getId(),
                Replay.batchIdOf(replay),
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
        // Received before it fell due, the receipt is the VAT tax point: the
        // instalment's VTP posts now, dated the receipt, in this same transaction
        // (spec 2026-09-24 §1). On or after the due date nothing changes.
        vatTaxPoints.onCleared(cheque, date);
    }

    /**
     * F14-16: a receipt nobody chose an account for lands in a bank leaf that some
     * bank account owns. The row's own account — defaulted when the grid was
     * generated — is kept when it is a cash leaf or an owned bank leaf; a bank leaf
     * no bank account owns (the one property creation used to generate) is swapped
     * for the property's owned leaf ({@link OwnedBankLeaf}), so the money can be
     * reconciled. With no bank account in the tenant nothing changes. A CASH row with
     * no account still settles to the CASH role.
     */
    private Account reconcilableLeaf(Account stamped, Cheque cheque) {
        UUID property = cheque.getProperty() != null ? cheque.getProperty().getId() : null;
        if (cheque.getMode() == ChequeMode.CASH) {
            // R1 P2-2: cash is counted into the till unless somebody chose otherwise.
            // A CASH row carrying a bank leaf got it as the grid's default, not as a
            // decision; its money goes to cash in hand when the chart has one.
            if (stamped != null && stamped.getAccountSubType() == AccountSubType.CASH) return stamped;
            java.util.Optional<UUID> cash = ownedBankLeaf.cashInHand(property);
            if (cash.isPresent()) return account(cash.get());
            if (stamped == null) return null;
        }
        if (stamped != null && stamped.getAccountSubType() != AccountSubType.BANK) return stamped;
        if (stamped != null && ownedBankLeaf.isOwned(stamped.getId())) return stamped;
        UUID propertyId = cheque.getProperty() != null ? cheque.getProperty().getId() : null;
        return ownedBankLeaf.forProperty(propertyId).map(this::account).orElse(stamped);
    }

    /**
     * R1 P2-2/P2-3: where a receipt or clearing of this row lands when the caller
     * names no account — the same resolution {@link #applyClearing} uses — and the
     * accounts the caller may choose instead. The dialog shows the first and offers
     * the second, so what it displays is what posts.
     */
    @Transactional(readOnly = true)
    public com.datagami.rentaxis.api.dto.cheque.SettlementTargetDTO settlementTarget(UUID chequeId) {
        Cheque cheque = chequeRepository.findById(chequeId).orElseThrow(() -> new NotFoundException("Cheque not found"));
        if (cheque.getLease() == null) throw new NotFoundException("Lease not found");
        leaseAccessPolicy.requireManageable(cheque.getLease());
        Account stamped = cheque.getDebitAccount() != null && isSettlementAccount(cheque.getDebitAccount())
                ? cheque.getDebitAccount() : null;
        Account target = reconcilableLeaf(stamped, cheque);
        UUID property = cheque.getProperty() != null ? cheque.getProperty().getId() : null;
        List<com.datagami.rentaxis.api.dto.cheque.SettlementTargetDTO.Option> options = new ArrayList<>();
        for (OwnedBankLeaf.Option o : ownedBankLeaf.optionsFor(property)) {
            options.add(new com.datagami.rentaxis.api.dto.cheque.SettlementTargetDTO.Option(
                    o.id(), o.code(), o.name(), o.nameAr(), o.kind(), o.bankAccount()));
        }
        if (target != null && options.stream().noneMatch(o -> o.id().equals(target.getId()))) {
            options.add(option(target));
        }
        return new com.datagami.rentaxis.api.dto.cheque.SettlementTargetDTO(
                target == null ? null : option(target), options);
    }

    private static com.datagami.rentaxis.api.dto.cheque.SettlementTargetDTO.Option option(Account a) {
        return new com.datagami.rentaxis.api.dto.cheque.SettlementTargetDTO.Option(a.getId(), a.getCode(), a.getName(),
                a.getNameAr(), a.getAccountSubType() == AccountSubType.CASH ? "CASH" : "BANK", null);
    }

    /**
     * F14-02: money cannot settle a row before the row is on the books. The CRT
     * credits PDC receivable, which the row's PDR debited; dated earlier, the leaf
     * runs negative for the days in between and every as-of report in that window
     * is wrong. The row's own date is its PDR's entry date (the posting date when
     * no PDR was written).
     */
    static void requireNotBeforeBooked(Cheque cheque, LocalDate booked, LocalDate date) {
        if (booked != null && date != null && date.isBefore(booked)) {
            throw new BusinessRuleViolationException(label(cheque) + " was put on the books on "
                    + booked.format(DMY) + "; it cannot be received or cleared on " + date.format(DMY)
                    + ", before that date. Receive it on or after " + booked.format(DMY) + ".",
                    "cheque.receiveBeforeBooked",
                    Map.of("row", label(cheque), "booked", booked.format(DMY), "date", date.format(DMY)));
        }
    }

    /** The date the row entered the ledger: its PDR's entry date, else its posting date. */
    LocalDate bookedOn(Cheque cheque) {
        if (cheque.getPdrJournalId() != null) {
            JournalEntry pdr = entityManager.find(JournalEntry.class, cheque.getPdrJournalId());
            if (pdr != null && pdr.getEntryDate() != null) {
                return pdr.getEntryDate();
            }
        }
        return cheque.getPostingDate();
    }

    private static final java.time.format.DateTimeFormatter DMY =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

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
            c.setDebitAccount(settlementAccount(row.debitAccountId(), property));
        }
        return c;
    }

    // ------------------------------------------------------------------
    // guards
    // ------------------------------------------------------------------

    private static final String BEING_UPDATED =
            "This cheque is being updated by another request. Please try again.";

    /**
     * The lease row's own version of it. The close hook and every add-a-row path
     * lock the <em>lease</em>, and telling a clerk to retry a cheque they are not
     * contending on sends them looking in the wrong place (review M3).
     */
    private static final String LEASE_BEING_UPDATED =
            "This lease is being updated by another request. Please try again.";

    /**
     * A cheque whose debt the settlement has already paid for cannot be collected a
     * second time (review C-1).
     *
     * <p>Only reachable for a BOUNCED row, which is the only status either
     * replacement door accepts. The {@code CBR} put that amount back on the rent
     * receivable; if the statement was drawn <em>afterwards</em> it netted exactly
     * that against the deposit and raised a CASH row for the rest, so by the time
     * the {@code STL} is posted the debt has been settled in full and the
     * receivable is flat. A replacement's {@code PDR} would credit it a second
     * time, leaving the landlord paid twice over one failed cheque.</p>
     *
     * <p><b>The discriminator is the ledger, not the row's status.</b> A cheque the
     * settlement kept and that bounces <em>after</em> finalise leaves a receivable
     * genuinely in debit — that money really is owed, nobody has been paid for it,
     * and {@code replace} is exactly the right answer.</p>
     *
     * <p><b>Asked of this cheque, not of the contract</b> (issue #297). "Does the
     * lease still show a debt" separates the two cases only while there is one
     * unreplaced bounce on the contract. With two, it gets the first one wrong: a
     * settlement finalised over bounced cheque A leaves the receivable flat, and a
     * <em>kept</em> cheque B bouncing afterwards makes it positive again — which
     * re-opened {@code replace} on A, whose debt the {@code STL} had already paid
     * for. A could then be collected a second time, B was afterwards locked out of
     * its own replacement, and an A larger than B left the contract unable ever to
     * close. So {@link LeaseClosureService#settlementAbsorbed} asks whether
     * <em>this</em> cheque's {@code CBR} was posted before the {@code STL}, and only
     * a row the settlement never saw falls through to the contract-level test.</p>
     */
    private void requireNotAlreadySettled(Lease lease, Cheque bounced) {
        if (!closure.isSettlementFinalized(lease.getId())) {
            return;
        }
        if (!closure.settlementAbsorbed(lease.getId(), bounced)
                && closure.receivableBalance(lease).signum() > 0) {
            return;
        }
        throw new BusinessRuleViolationException(
                "This cheque was settled through the lease settlement; " + label(bounced)
                        + " cannot be collected again.");
    }

    /**
     * A reversal must not put money back onto a contract the settlement has already
     * balanced (review I-2).
     *
     * <p>{@link #cancel} and {@link #returnToTenant} both reverse the row's
     * {@code PDR}, which re-debits the rent receivable by the row's amount. On a
     * running contract that is the point — the renter owes the instalment again. On
     * a settled one it is not: the statement netted this instrument against the
     * deposit and a refund was paid out on that basis, so handing the paper back
     * leaves an ended, settled contract owing its full amount with no door left that
     * could collect it. That was lifecycle row 12, and it ended CLOSED.</p>
     *
     * <p><b>Measured, not assumed.</b> The test is what the receivable would read
     * <em>after</em> this reversal, which is why reversing an approved penalty still
     * works: that path reverses the {@code PEN} first, so the collection row's own
     * reversal takes the receivable back to nil rather than into debit. The rule is
     * "a reversal may not leave a settled contract owing", not "these two verbs are
     * banned", and the difference is exactly the pair of cases finance needs.</p>
     */
    private void requireSettlementUndisturbed(Lease lease, Cheque cheque) {
        if (!closure.isSettlementFinalized(lease.getId())) {
            return;
        }
        BigDecimal amount = cheque.getAmount() == null ? BigDecimal.ZERO : cheque.getAmount();
        if (closure.receivableBalance(lease).add(amount).signum() <= 0) {
            return;
        }
        throw new BusinessRuleViolationException(
                "The settlement was finalised counting on this cheque — replace it instead:"
                        + " reversing " + label(cheque) + " would put " + money(amount)
                        + " AED back on a contract the ledger says is settled.");
    }

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
            throw new RowLockedException(BEING_UPDATED);
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

    /** NOWAIT, the way {@code findByIdForUpdate} declares it, for the re-read below. */
    private static final Map<String, Object> NOWAIT = Map.of("jakarta.persistence.lock.timeout", 0);

    /**
     * The lease row, locked, <em>re-read</em> and tenant-checked — taken by every
     * path that adds a row to the register, because a new row's position is
     * computed as max+1 over the lease's existing rows.
     *
     * <p><b>{@code refresh}, not a locking finder</b> (review M-1). Every caller
     * here reaches the lease through {@code cheque.getLease()}, so the row is
     * usually already managed by this transaction — and on an already-managed row
     * a locking query does not do what its name suggests:</p>
     *
     * <ul>
     *   <li>if the row has <em>not</em> moved, it is answered from the first-level
     *       cache and hands back the stale state, which is the value the lock
     *       exists to stop us acting on;</li>
     *   <li>if it <em>has</em> moved, {@code Lease.@Version} makes Hibernate refuse
     *       outright — "query result contains conflicting version of entity already
     *       held in persistence context" — a 500-shaped optimistic-lock failure on
     *       a path that is merely trying to read the truth.</li>
     * </ul>
     *
     * <p>Neither is what the caller wants, and the second is not hypothetical: it
     * is what {@code aCloseIsNotSkippedBecauseTheLeaseWasLoadedBeforeItEnded}
     * produced the first time this method kept its locking finder. So the row is
     * taken by key — a cache hit when it is already here — and then
     * {@code refresh(…, PESSIMISTIC_WRITE)}, which is defined as "overwrite this
     * instance from the database" and therefore both takes the lock and re-syncs
     * the version. It costs one extra select the first time a transaction meets a
     * lease, and it is the only form that is correct in both cases.</p>
     *
     * <p>{@code find} rather than {@code findByIdScopedToTenant} for the same
     * reason: it is the one load that is answered from the context. <b>The explicit
     * tenant check below is therefore the only guard here — not a repeat of the
     * Hibernate filter.</b> {@code BaseTenantEntity} does set
     * {@code applyToLoadByKey = true}, but that only takes effect once the filter is
     * <em>enabled</em>, and {@code TenantAspect} enables it {@code @Before} execution
     * of {@code domain.repository..*} only; whether one has run in this transaction
     * before the {@code find} is not something this method can assume.</p>
     */
    private Lease lockLease(UUID leaseId) {
        Lease lease;
        try {
            lease = entityManager.find(Lease.class, leaseId);
            if (lease == null) {
                throw new NotFoundException("Lease not found");
            }
            entityManager.refresh(lease, LockModeType.PESSIMISTIC_WRITE, NOWAIT);
        } catch (PessimisticLockingFailureException | PessimisticLockException | LockTimeoutException e) {
            // Three types for one event. The locking finder this used to call came
            // back through Spring Data's exception translation, so a NOWAIT refusal
            // arrived as Spring's PessimisticLockingFailureException; a direct
            // EntityManager call is not translated and raises JPA's own
            // LockTimeoutException instead. Missing that turned "another clerk has
            // this lease, try again" back into a 500 — which is the very thing
            // RowLockedException exists to prevent, and what
            // ChequeServiceIT.concurrentAddsNeverDuplicateASequenceNumber caught.
            throw new RowLockedException(LEASE_BEING_UPDATED);
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
        return requireCollectable(lease);
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
     *
     * <p><b>Authorisation only — the lease-status guard is the caller's.</b> Every
     * other door calls {@link #requireCollectable} straight after this one; the
     * gateway's capture deliberately does not, until it has answered a retried
     * webhook (see {@link #clearOnline}).</p>
     */
    private Lease gatewayLeaseOf(Cheque cheque) {
        Lease lease = cheque.getLease();
        if (lease == null) throw new NotFoundException("Lease not found");
        // Not `getAuthentication() != null`: the anonymous filter makes that true
        // for the signature-verified webhook too. See hasAuthenticatedCaller.
        if (leaseAccessPolicy.hasAuthenticatedCaller() && !leaseAccessPolicy.canManage(lease)) {
            leaseAccessPolicy.requireReadable(lease);
        }
        return lease;
    }

    /**
     * The guard every <em>transition</em> runs: this row's lease must still be one
     * whose money is being collected.
     *
     * <p>Two refusals, because they are two different problems. A DRAFT lease has
     * not been posted yet and the answer is to post it; a CLOSED one is finished
     * and there is no answer — whatever this instrument is, it is not part of that
     * tenancy's collections any more.</p>
     */
    private static Lease requireCollectable(Lease lease) {
        if (!COLLECTABLE.contains(lease.getStatus())) {
            throw new BusinessRuleViolationException(lease.getStatus() == LeaseStatus.CLOSED
                    ? "This cheque's lease is CLOSED; its settlement is finished and its register rows"
                            + " can no longer be changed."
                    : "This cheque's lease is " + lease.getStatus()
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

    /**
     * A post-dated cheque cannot be banked before the day it is payable.
     *
     * <p>The clue is in the name: a bank refuses a PDC presented early, so a
     * register that accepted one would be claiming cash in transit that could
     * not exist, and the CRT that follows would carry a value date before the
     * instrument was payable.</p>
     *
     * <p>Not applied to a {@link Replay}: a cut-over import states what the
     * previous system recorded, and history has to be reproducible even where
     * it was irregular.</p>
     */
    private static void requireNotPresentedEarly(Cheque cheque, LocalDate depositDate) {
        LocalDate payableOn = cheque.getChequeDate();
        if (payableOn != null && depositDate != null && depositDate.isBefore(payableOn)) {
            throw new BusinessRuleViolationException(earlyPresentation(cheque, depositDate));
        }
    }

    /** Shared wording so the single-cheque and batch paths read the same. */
    private static String earlyPresentation(Cheque cheque, LocalDate depositDate) {
        return label(cheque) + " is dated " + cheque.getChequeDate()
                + " and cannot be banked on " + depositDate
                + " — a post-dated cheque may not be presented before its date.";
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

    /**
     * A caller-named bank or cash account for a cheque on {@code property}: it must
     * be that building's own leaf or a tenant-level one (audit C-F1). Otherwise a
     * manager of building X could clear X's receipt into building Y's bank, and
     * both buildings' bank reconciliations would break. Same "does not exist"
     * wording as a foreign tenant's account.
     */
    private Account settlementAccount(UUID id, com.datagami.rentaxis.domain.entity.Property property) {
        Account a = requireSettlementAccount(account(id));
        UUID accountProperty = a.getPropertyId();
        if (accountProperty != null && (property == null || !accountProperty.equals(property.getId()))) {
            throw new BusinessRuleViolationException("Account " + id + " does not belong to this property");
        }
        return a;
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
        if (!isSettlementAccount(a)) {
            throw new BusinessRuleViolationException(
                    "Debit account " + a.getCode() + " must be a bank or cash account");
        }
        return a;
    }

    /**
     * The predicate behind {@link #requireSettlementAccount}, for callers that ask
     * the same question about a column of their own and so have to word the refusal
     * differently — a payment voucher's {@code payment_account_id} is credited, not
     * debited, and "Debit account …" would be the wrong sentence on that screen.
     * Exposed rather than copied so there is exactly one definition of "an account
     * cleared funds may land in or leave from" in the codebase.
     */
    /**
     * Where a receipt ({@link #receive}) may take its money from: a settlement
     * account, or the tenant's {@code BANK_SUSPENSE} leaf — an unidentified bank
     * receipt now identified as this renter's payment (finance-ops spec §3). Used
     * by {@code receive} only; {@link #isSettlementAccount}, and so every payment
     * voucher, is unchanged.
     */
    public boolean isReceiptSource(Account a) {
        return isSettlementAccount(a) || isSuspenseLeaf(a);
    }

    private boolean isSuspenseLeaf(Account a) {
        if (a == null || a.isGroup() || !a.isActive()) return false;
        UUID t = TenantContextHolder.getTenantId();
        List<?> hit = entityManager.createQuery("""
                select m.id from TenantDefaultAccountMapping m
                where m.role = :role and m.account.id = :a and m.tenantId = :t""")
                .setParameter("role", AccountRole.BANK_SUSPENSE).setParameter("a", a.getId()).setParameter("t", t)
                .getResultList();
        return !hit.isEmpty();
    }

    public static boolean isSettlementAccount(Account a) {
        return a != null && !a.isGroup() && a.isActive()
                && a.getAccountType() == AccountType.ASSET
                && (a.getAccountSubType() == AccountSubType.BANK || a.getAccountSubType() == AccountSubType.CASH);
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
    private void notifyRenter(Cheque cheque, String type, String title, String message,
                              NotificationMessage structured) {
        try {
            UUID renterUserId = cheque.getRenter() != null ? cheque.getRenter().getUserId() : null;
            if (renterUserId != null) {
                notificationService.notifyInAppInNewTx(cheque.getTenantId(), renterUserId,
                        type, title, message, "CHEQUE", cheque.getId(), structured);
            }
        } catch (Exception e) {
            log.warn("Failed to send {} notification for cheque {}: {}", type, cheque.getId(), e.getMessage());
        }
    }
}
