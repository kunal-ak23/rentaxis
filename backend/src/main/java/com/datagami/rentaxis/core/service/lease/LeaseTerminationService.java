package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TerminationPreviewDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.cheque.ChequeMapper;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ending a tenancy on a date (spec §9.1).
 *
 * <p>Three things happen and they are one act:</p>
 * <ol>
 *   <li><b>The paper goes back.</b> Every uncleared instrument dated after
 *       {@code T} has its {@code PDR} reversed and goes RETURNED; ones dated on or
 *       before it are kept, because that money was already due. Finance can flip
 *       any row before confirming.</li>
 *   <li><b>The schedule is cut.</b> Recognition is truncated at {@code T}
 *       ({@code RecognitionService.truncateForTermination}) and the advance rent
 *       that will never be earned is handed back as one {@code TCR}:
 *       {@code Dr Advance Rent / Cr Rent Receivable}.</li>
 *   <li><b>The contract closes.</b> TERMINATED, dated {@code T}, and the unit is
 *       released unless another lease still holds it.</li>
 * </ol>
 *
 * <p>After (1) and (2) the renter's rent receivable reads exactly
 * <em>earned − received</em>, which is the number the settlement statement is
 * built on (spec §9.2) and the reason the three steps cannot be split across
 * transactions. They are not: this whole method is one transaction, and the
 * replacement {@code CIL} the truncation may repost is deliberately posted through
 * {@code RecognitionPoster.postJoining} rather than the month-end run's
 * {@code REQUIRES_NEW} entry point — otherwise a refused {@code TCR} would roll
 * everything back except that one journal, and leave a lease with its rent
 * recognised to {@code T} and its cheques still on the register.</p>
 *
 * <p><b>Order inside the transaction is deliberate</b> and is the same order
 * posting and amendment take: the lease row is locked first, then everything is
 * validated, then the cheques, then the recognition, then the {@code TCR}, then
 * the status. Locking first means two clerks terminating the same lease queue up
 * rather than both reading an ACTIVE row.</p>
 */
@Service
public class LeaseTerminationService {

    /** A contract that can still be ended. EXPIRED and RENEWED ones are settled, not terminated. */
    private static final Set<LeaseStatus> TERMINABLE = EnumSet.of(LeaseStatus.ACTIVE, LeaseStatus.NOTICE_GIVEN);

    private final LeaseRepository leaseRepository;
    private final ChequeRepository chequeRepository;
    private final TenantFiscalSettingsRepository fiscalSettings;
    private final LeasePostingService leasePostingService;
    private final LeaseService leaseService;
    private final ChequeService chequeService;
    private final RecognitionService recognitionService;
    private final PostingService postingService;
    private final LedgerQueryService ledgerQueryService;
    private final AccountResolver accountResolver;
    private final LeaseAccessPolicy leaseAccessPolicy;
    private final Clock clock;

    public LeaseTerminationService(LeaseRepository leaseRepository,
                                   ChequeRepository chequeRepository,
                                   TenantFiscalSettingsRepository fiscalSettings,
                                   LeasePostingService leasePostingService,
                                   LeaseService leaseService,
                                   ChequeService chequeService,
                                   RecognitionService recognitionService,
                                   PostingService postingService,
                                   LedgerQueryService ledgerQueryService,
                                   AccountResolver accountResolver,
                                   LeaseAccessPolicy leaseAccessPolicy,
                                   Clock clock) {
        this.leaseRepository = leaseRepository;
        this.chequeRepository = chequeRepository;
        this.fiscalSettings = fiscalSettings;
        this.leasePostingService = leasePostingService;
        this.leaseService = leaseService;
        this.chequeService = chequeService;
        this.recognitionService = recognitionService;
        this.postingService = postingService;
        this.ledgerQueryService = ledgerQueryService;
        this.accountResolver = accountResolver;
        this.leaseAccessPolicy = leaseAccessPolicy;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // preview
    // ------------------------------------------------------------------

    /**
     * What terminating on {@code t} would do, with nothing written.
     *
     * <p>Readable rather than manageable: a property manager may look at the
     * consequences of ending a tenancy in a building they run. Only the finance
     * roles may press the button (see the controller's role gate), which is why
     * {@link #terminate} asks a different question.</p>
     */
    @Transactional(readOnly = true)
    public TerminationPreviewDTO preview(UUID leaseId, LocalDate t) {
        Lease lease = lease(leaseId);
        leaseAccessPolicy.requireReadable(lease);
        validate(lease, t);

        Split split = defaultSplit(chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId), t);
        RecognitionService.TerminationRecognition plan = recognitionService.previewTermination(leaseId, t);
        return new TerminationPreviewDTO(
                t,
                plan.earnedThrough(),
                plan.recognisedSoFar(),
                plan.unearned(),
                dtos(split.toReturn(), lease),
                dtos(split.toKeep(), lease),
                dtos(split.bounced(), lease),
                receivableAfter(lease, split.toReturn(), plan.unearned()));
    }

    // ------------------------------------------------------------------
    // terminate
    // ------------------------------------------------------------------

    /** End the contract on {@code r.terminationDate()}. One transaction; see the class note. */
    @Transactional
    public LeaseDTO terminate(UUID leaseId, TerminateLeaseRequest r, UUID byUser) {
        if (r == null || r.terminationDate() == null) {
            throw new BusinessRuleViolationException("A termination needs a date");
        }
        LocalDate t = r.terminationDate();

        Lease lease = leasePostingService.lockLease(leaseId);
        leaseAccessPolicy.requireManageable(lease);
        validate(lease, t);

        List<Cheque> register = chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId);
        List<Cheque> toReturn = chosenReturns(register, r, t);
        // Everything this is about to write, priced before the first write. A
        // recognition reversal carries the later of T and the entry's own date
        // (see RecognitionService.reversalDate), so a month closed after T files
        // on its own month-end, and that date has to be open too.
        requireOpenPeriod(recognitionService.previewTermination(leaseId, t).latestPostingDate(), t);

        for (Cheque cheque : toReturn) {
            // An abandoned checkout is not money and the contract it belonged to is
            // ending: the row goes back to REGISTERED first, which is the only status
            // returnToTenant accepts and the one it came from.
            if (cheque.getStatus() == ChequeStatus.ONLINE_PENDING) {
                chequeService.revertOnlinePending(cheque.getId());
            }
            chequeService.returnToTenant(cheque.getId(), t, "Contract terminated " + t);
        }

        RecognitionService.TerminationRecognition plan = recognitionService.truncateForTermination(leaseId, t);
        UUID tcrId = postUnearnedReversal(lease, plan, t);

        return leaseService.markTerminated(leaseId, t, r.notes(), tcrId, byUser);
    }

    /**
     * One {@code TCR} handing the unearned advance rent back to the receivable.
     *
     * <p>One pair per segment that has anything left, each debiting the leaf that
     * segment's {@code TCO} actually deferred into — a line with a manual account
     * override is exactly the case where "resolve {@code ADVANCE_RENT} again"
     * strands a liability in one leaf while releasing from another.</p>
     *
     * @return the journal's id, or null when nothing was unearned — a termination
     *         on the last day of the term posts no {@code TCR} at all, and an entry
     *         for zero is not a document.
     */
    private UUID postUnearnedReversal(Lease lease, RecognitionService.TerminationRecognition plan, LocalDate t) {
        if (plan.unearned().signum() <= 0) {
            return null;
        }
        String narration = "Unearned rent reversed on termination";
        // Merged by account, not one pair per segment. An extended lease has two
        // RENT lines and both normally defer into the same leaf; a pair each would
        // put the same account on two debit lines of one journal, which is legal,
        // balanced, and a question a bookkeeper has to stop and ask. A line that
        // names its own deferral account still gets its own pair, which is the
        // distinction that actually matters.
        Map<PostingRequest.AccountRef, BigDecimal> byAccount = new LinkedHashMap<>();
        for (RecognitionService.UnearnedDeferral d : plan.deferrals()) {
            byAccount.merge(d.account(), d.amount(), BigDecimal::add);
        }
        List<PostingRequest.Pair> pairs = new ArrayList<>(byAccount.size());
        byAccount.forEach((account, amount) -> pairs.add(PostingRequest.pair(
                new PostingRequest.Line(account, PostingRequest.Side.DR, amount, null, narration),
                LeaseChequeRegistrar.crReceivable(lease, amount).withNarration(narration))));
        JournalEntry tcr = postingService.post(PostingRequest.ofPairs(
                JournalDocType.TCR,
                t,
                narration,
                LeaseChequeRegistrar.dimensions(lease, null),
                JournalSourceType.LEASE,
                lease.getId(),
                null,
                pairs));
        return tcr.getId();
    }

    // ------------------------------------------------------------------
    // the cheque split
    // ------------------------------------------------------------------

    /** The three buckets a terminated lease's register falls into. */
    private record Split(List<Cheque> toReturn, List<Cheque> toKeep, List<Cheque> bounced) {
    }

    /**
     * The default: uncleared and dated after {@code t} goes back, uncleared and
     * dated on or before it stays. A BOUNCED row is neither — the instrument
     * already failed, there is nothing to hand back, and the amount is owed.
     */
    private static Split defaultSplit(List<Cheque> register, LocalDate t) {
        List<Cheque> toReturn = new ArrayList<>();
        List<Cheque> toKeep = new ArrayList<>();
        List<Cheque> bounced = new ArrayList<>();
        for (Cheque c : register) {
            if (c.getStatus() == ChequeStatus.BOUNCED) {
                bounced.add(c);
            } else if (c.getStatus().isUncleared()) {
                (c.getChequeDate() != null && c.getChequeDate().isAfter(t) ? toReturn : toKeep).add(c);
            }
        }
        return new Split(List.copyOf(toReturn), List.copyOf(toKeep), List.copyOf(bounced));
    }

    /**
     * The rows this request wants returned.
     *
     * <p>Two lists arrive and <em>both</em> are checked, because the interesting
     * failure is not an unknown id: it is an uncleared row that appears in neither
     * list. "Return these" read on its own is silently "and do whatever you like
     * with the rest", and what happens to the rest is the difference between
     * handing a renter their cheques back and banking them. So every uncleared row
     * must be in exactly one list, and a request that is not a complete answer is
     * refused naming the rows it forgot.</p>
     *
     * <p>Both lists empty means "the defaults", which is what a caller that simply
     * accepted the preview sends.</p>
     */
    private static List<Cheque> chosenReturns(List<Cheque> register, TerminateLeaseRequest r, LocalDate t) {
        Split defaults = defaultSplit(register, t);
        List<UUID> returnIds = r.returnChequeIdsOrEmpty();
        List<UUID> keepIds = r.keepChequeIdsOrEmpty();
        if (returnIds.isEmpty() && keepIds.isEmpty()) {
            return defaults.toReturn();
        }

        Set<UUID> uncleared = new LinkedHashSet<>();
        for (Cheque c : register) {
            if (c.getStatus().isUncleared()) uncleared.add(c.getId());
        }
        Set<UUID> toReturn = new LinkedHashSet<>(returnIds);
        Set<UUID> toKeep = new LinkedHashSet<>(keepIds);

        Set<UUID> both = new LinkedHashSet<>(toReturn);
        both.retainAll(toKeep);
        if (!both.isEmpty()) {
            throw new BusinessRuleViolationException(
                    "These cheques are listed as both returned and kept: " + join(both) + ".");
        }
        Set<UUID> unknown = new LinkedHashSet<>(toReturn);
        unknown.addAll(toKeep);
        unknown.removeAll(uncleared);
        if (!unknown.isEmpty()) {
            throw new BusinessRuleViolationException(
                    "These cheques are not uncleared rows of this lease: " + join(unknown) + ".");
        }
        Set<UUID> unassigned = new LinkedHashSet<>(uncleared);
        unassigned.removeAll(toReturn);
        unassigned.removeAll(toKeep);
        if (!unassigned.isEmpty()) {
            throw new BusinessRuleViolationException(
                    "Every uncleared cheque must be either returned or kept; these are in neither list: "
                            + join(unassigned) + ".");
        }
        return register.stream().filter(c -> toReturn.contains(c.getId())).toList();
    }

    private static String join(Set<UUID> ids) {
        return ids.stream().map(UUID::toString).reduce((a, b) -> a + ", " + b).orElse("");
    }

    // ------------------------------------------------------------------
    // guards and arithmetic
    // ------------------------------------------------------------------

    private void validate(Lease lease, LocalDate t) {
        if (t == null) {
            throw new BusinessRuleViolationException("A termination needs a date");
        }
        if (!TERMINABLE.contains(lease.getStatus())) {
            throw new BusinessRuleViolationException(
                    "Only an ACTIVE or NOTICE_GIVEN lease can be terminated; this one is " + lease.getStatus() + ".");
        }
        if (t.isBefore(lease.getStartDate()) || t.isAfter(lease.getEndDate())) {
            throw new BusinessRuleViolationException("The termination date " + t + " is outside the lease term ("
                    + lease.getStartDate() + " – " + lease.getEndDate() + ").");
        }
        LocalDate locked = booksLockedThrough();
        if (locked != null && !t.isAfter(locked)) {
            // Checked here rather than left to PostingService so the refusal arrives
            // before the first cheque is handed back, and names the date finance has
            // to move rather than the fourth journal that happened to hit it.
            throw new BusinessRuleViolationException(
                    "Cannot terminate on " + t + ": books are locked through " + locked + ".");
        }
    }

    /**
     * A date this termination will post on has to be open too.
     *
     * <p>Today this can only ever pass: {@code books_locked_through} is one
     * monotone high-water mark, {@link #validate} already refuses a {@code t} at or
     * before it, and every date here is at or after {@code t}. It is written
     * anyway, and it is written <em>before</em> the first cheque is handed back,
     * because "the lock is a single date" is a property of the fiscal settings and
     * not of this method — a per-period lock, or a second lock date, would make it
     * reachable, and the failure mode without it is a termination that rolls back
     * after the accountant has watched it half-happen.</p>
     */
    private void requireOpenPeriod(LocalDate date, LocalDate t) {
        LocalDate locked = booksLockedThrough();
        if (locked != null && date != null && !date.isAfter(locked)) {
            throw new BusinessRuleViolationException("Cannot terminate on " + t
                    + ": reversing the income posted on " + date
                    + " would fall in a locked period (books are locked through " + locked + ").");
        }
    }

    private LocalDate booksLockedThrough() {
        UUID tenantId = TenantContextHolder.getTenantId();
        return tenantId == null ? null : fiscalSettings.findById(tenantId)
                .map(TenantFiscalSettings::getBooksLockedThrough).orElse(null);
    }

    /**
     * The receivable the renter will be left with: what it reads now, plus the
     * returns putting their instalments back on the renter's account, less the
     * advance rent the {@code TCR} hands over.
     *
     * <p>Derived rather than read back after the fact, because a preview has
     * written nothing — and derived from the same two numbers the termination will
     * actually post, so the page's figure and the ledger's cannot drift.</p>
     */
    private BigDecimal receivableAfter(Lease lease, List<Cheque> toReturn, BigDecimal unearned) {
        BigDecimal current = ledgerQueryService.accountLedger(receivableAccountOf(lease),
                        new LedgerQueryService.LedgerFilter(null, null, null, null, lease.getId(), null))
                .closingBalance();
        BigDecimal returned = toReturn.stream()
                .map(c -> c.getAmount() == null ? BigDecimal.ZERO : c.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return current.add(returned).subtract(unearned).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** The lease's own receivable leaf when it overrides the property's (spec §6.3). */
    private UUID receivableAccountOf(Lease lease) {
        if (lease.getReceivableAccountId() != null) {
            return lease.getReceivableAccountId();
        }
        return accountResolver.resolve(AccountRole.RENT_RECEIVABLE, propertyIdOf(lease)).getId();
    }

    private static UUID propertyIdOf(Lease lease) {
        return lease.getUnit() != null && lease.getUnit().getProperty() != null
                ? lease.getUnit().getProperty().getId() : null;
    }

    private Lease lease(UUID leaseId) {
        return leaseRepository.findByIdScopedToTenant(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
    }

    /** Mapped inside the caller's transaction: the mapper reads the lazy relations. */
    private List<ChequeDTO> dtos(List<Cheque> cheques, Lease lease) {
        LocalDate today = LocalDate.now(clock);
        return cheques.stream().map(c -> ChequeMapper.toDto(c, today, lease.getGracePeriodDays())).toList();
    }
}
