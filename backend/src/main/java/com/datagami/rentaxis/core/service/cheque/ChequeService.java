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
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
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

    /** "12,750.00" — the shape an accountant reads amounts in, in the refusal messages. */
    private static final DecimalFormat MONEY =
            new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));

    /**
     * A lease whose contract is on the books. Its cheques are instruments against
     * a real debt; a DRAFT lease's rows are a proposal, and moving one through the
     * register would clear money against a receivable nobody has raised.
     */
    private static final Set<LeaseStatus> POSTED = EnumSet.of(
            LeaseStatus.ACTIVE, LeaseStatus.NOTICE_GIVEN, LeaseStatus.EXPIRED, LeaseStatus.RENEWED);

    private final ChequeRepository chequeRepository;
    private final LeaseRepository leaseRepository;
    private final AccountRepository accountRepository;
    private final PostingService postingService;
    private final LeaseChequeRegistrar registrar;
    private final LeaseAccessPolicy leaseAccessPolicy;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher events;

    public ChequeService(ChequeRepository chequeRepository,
                         LeaseRepository leaseRepository,
                         AccountRepository accountRepository,
                         PostingService postingService,
                         LeaseChequeRegistrar registrar,
                         LeaseAccessPolicy leaseAccessPolicy,
                         NotificationService notificationService,
                         ApplicationEventPublisher events) {
        this.chequeRepository = chequeRepository;
        this.leaseRepository = leaseRepository;
        this.accountRepository = accountRepository;
        this.postingService = postingService;
        this.registrar = registrar;
        this.leaseAccessPolicy = leaseAccessPolicy;
        this.notificationService = notificationService;
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
        Lease lease = postedLeaseOf(cheque);
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
            Lease lease = postedLeaseOf(c);
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
        Lease lease = postedLeaseOf(cheque);
        requireStatus(cheque, "clear", ChequeStatus.DEPOSITED);

        applyClearing(lease, cheque, r.dateOrToday(), r.debitAccountId(), r.notes());
        chequeRepository.save(cheque);
        // A cheque cleared after its grace period is a late payment; the penalty
        // proposal hook is added by the penalty module.
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
        Lease lease = postedLeaseOf(cheque);
        requireStatus(cheque, "receive", ChequeStatus.REGISTERED);
        if (cheque.getMode() != ChequeMode.CASH && cheque.getMode() != ChequeMode.TRANSFER) {
            throw new BusinessRuleViolationException(
                    "Only a CASH or TRANSFER receipt can be received directly; " + label(cheque)
                            + " is a " + cheque.getMode() + " row. Deposit it and clear it instead.");
        }

        applyClearing(lease, cheque, r.dateOrToday(), r.debitAccountId(), r.notes());
        chequeRepository.save(cheque);
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
        Lease lease = postedLeaseOf(cheque);
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

        publish(EmailEventType.CHEQUE_BOUNCED, cheque,
                ChequePayload.ofCheque(cheque, null,
                        r.failureReason() != null ? r.failureReason().name() : null));
        notifyRenter(cheque, "PAYMENT_BOUNCED", "Cheque Failed",
                "Instalment #" + cheque.getSeqNo() + " of " + MONEY.format(amount)
                        + " AED was returned" + (r.failureReason() != null ? " (" + r.failureReason() + ")" : "")
                        + ". Please arrange a replacement.");
        // The threshold count and the fine itself are the penalty module's; the
        // penalty proposal hook is added by the penalty module.
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
        Lease lease = postedLeaseOf(bounced);
        requireStatus(bounced, "replace", ChequeStatus.BOUNCED);

        List<ChequeRowInput> rows = request.replacements();
        ChequeRowRules.validateNewRows(rows, takenNumbers(lease.getId()), "replacement");

        BigDecimal total = BigDecimal.ZERO;
        for (ChequeRowInput row : rows) {
            total = total.add(row.amount());
        }
        if (total.compareTo(bounced.getAmount()) > 0) {
            throw new BusinessRuleViolationException(
                    "The replacements total " + MONEY.format(total) + " but " + label(bounced)
                            + " was " + MONEY.format(bounced.getAmount())
                            + "; a replacement cannot collect more than the cheque it replaces.");
        }

        LocalDate date = request.dateOrToday();
        List<ChequeDTO> out = new ArrayList<>(rows.size());
        int seq = nextSeqNo(lease.getId());
        for (ChequeRowInput row : rows) {
            Cheque replacement = newRow(lease, row, seq++, date);
            replacement.setReplaces(bounced);
            chequeRepository.save(replacement);
            registrar.register(lease, replacement);
            if (bounced.getReplacedBy() == null) {
                // The chain points at the first replacement; the rest are reachable
                // through their own replaces_id. A single column cannot hold three.
                bounced.setReplacedBy(replacement);
            }
            publish(EmailEventType.CHEQUE_RECEIVED, replacement,
                    ChequePayload.ofCheque(replacement, null, null));
            out.add(dto(replacement, lease));
        }

        moveTo(bounced, ChequeStatus.REPLACED, request.notes());
        chequeRepository.save(bounced);
        return out;
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
        Lease lease = postedLeaseOf(cheque);
        requireStatus(cheque, "cancel", ChequeStatus.REGISTERED);

        reversePdr(cheque, r.dateOrToday(), reasonOr(r.notes(), "Cheque cancelled"));
        moveTo(cheque, ChequeStatus.CANCELLED, r.notes());
        chequeRepository.save(cheque);
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
        Lease lease = postedLeaseOf(cheque);
        requireStatus(cheque, "return", ChequeStatus.REGISTERED, ChequeStatus.DEPOSITED);

        LocalDate on = date != null ? date : LocalDate.now();
        reversePdr(cheque, on, reasonOr(reason, "Cheque returned to tenant"));
        cheque.setReturnedAt(on);
        moveTo(cheque, ChequeStatus.RETURNED, reason);
        chequeRepository.save(cheque);
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
     */
    @Transactional
    public ChequeDTO addRowToPostedLease(UUID leaseId, ChequeRowInput row) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(lease.getTenantId())) {
            throw new NotFoundException("Lease not found");
        }
        leaseAccessPolicy.requireReadable(lease);
        if (!POSTED.contains(lease.getStatus())) {
            throw new BusinessRuleViolationException(
                    "This lease is " + lease.getStatus() + "; use the cheque grid to add rows until it is posted.");
        }
        ChequeRowRules.validateNewRows(List.of(row), takenNumbers(leaseId), "row");

        Cheque cheque = newRow(lease, row, nextSeqNo(leaseId), LocalDate.now());
        chequeRepository.save(cheque);
        registrar.register(lease, cheque);
        publish(EmailEventType.CHEQUE_RECEIVED, cheque, ChequePayload.ofCheque(cheque, null, null));
        return dto(cheque, lease);
    }

    // ------------------------------------------------------------------
    // online (Razorpay) — spec §9.3, driven by Task 10
    // ------------------------------------------------------------------

    /**
     * The renter started paying this instalment online. Nothing posts: an
     * authorisation is not money, and a gateway session that is abandoned has to
     * leave the register exactly as it found it.
     */
    @Transactional
    public ChequeDTO registerOnlinePending(UUID chequeId) {
        Cheque cheque = lock(chequeId);
        Lease lease = postedLeaseOf(cheque);
        boolean payable = cheque.getStatus() == ChequeStatus.REGISTERED
                // A bounced row is a live debt from the moment it failed, and paying
                // it online is the fastest way the renter can cure it.
                || (cheque.getStatus() == ChequeStatus.BOUNCED && ChequeDueRules.due(cheque, LocalDate.now()));
        if (!payable) {
            throw new BusinessRuleViolationException(
                    "Can only start an online payment for cheques in REGISTERED or BOUNCED (current: "
                            + cheque.getStatus() + ")");
        }
        moveTo(cheque, ChequeStatus.ONLINE_PENDING, null);
        chequeRepository.save(cheque);
        return dto(cheque, lease);
    }

    /** The gateway session failed or was abandoned; the row goes back on the register. */
    @Transactional
    public ChequeDTO revertOnlinePending(UUID chequeId) {
        Cheque cheque = lock(chequeId);
        Lease lease = postedLeaseOf(cheque);
        requireStatus(cheque, "revert", ChequeStatus.ONLINE_PENDING);
        moveTo(cheque, ChequeStatus.REGISTERED, null);
        chequeRepository.save(cheque);
        return dto(cheque, lease);
    }

    /**
     * The gateway captured the payment: the same {@code CRT} any other receipt
     * writes, into the settlement account the gateway pays out to.
     *
     * <p><b>Idempotent on an already-cleared row.</b> Payment gateways retry their
     * webhooks, and a retry that posted a second CRT would collect the same
     * instalment twice. A row that is already CLEARED is returned as it stands.</p>
     */
    @Transactional
    public ChequeDTO clearOnline(UUID chequeId, LocalDate capturedOn, UUID settlementAccountId) {
        Cheque cheque = lock(chequeId);
        Lease lease = postedLeaseOf(cheque);
        if (cheque.getStatus() == ChequeStatus.CLEARED) {
            return dto(cheque, lease);
        }
        requireStatus(cheque, "capture", ChequeStatus.ONLINE_PENDING);

        applyClearing(lease, cheque, capturedOn != null ? capturedOn : LocalDate.now(), settlementAccountId, null);
        chequeRepository.save(cheque);
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
            cheque.setDebitAccount(account(debitAccountId));
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
        Account debit = debitAccountId != null ? account(debitAccountId) : cheque.getDebitAccount();
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
            c.setDebitAccount(account(row.debitAccountId()));
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
     * The cheque's lease, required to be on the books.
     *
     * <p>Checked before the status guard on purpose: a REGISTERED row on a lease
     * someone reverted to DRAFT would otherwise deposit and clear happily against a
     * receivable the ledger never raised.</p>
     */
    private Lease postedLeaseOf(Cheque cheque) {
        Lease lease = cheque.getLease();
        if (lease == null) throw new NotFoundException("Lease not found");
        leaseAccessPolicy.requireReadable(lease);
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
        if (notes != null && !notes.isBlank()) {
            cheque.setNotes(notes);
        }
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
            throw new BusinessRuleViolationException("Account " + id + " does not exist");
        }
        return a;
    }

    /** Cheque numbers already live on the lease — everything a new row may not reuse. */
    private Set<String> takenNumbers(UUID leaseId) {
        Set<String> taken = new HashSet<>();
        for (Cheque c : chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId)) {
            if (c.getMode() == ChequeMode.PDC && c.getChequeNumber() != null) {
                taken.add(c.getChequeNumber());
            }
        }
        return taken;
    }

    private int nextSeqNo(UUID leaseId) {
        return chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId).stream()
                .mapToInt(Cheque::getSeqNo).max().orElse(0) + 1;
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
