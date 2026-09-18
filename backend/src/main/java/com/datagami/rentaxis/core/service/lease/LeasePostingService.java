package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.AmendLeaseLinesRequest;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.dto.lease.PostLeaseDryRunResponse;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.cheque.ChequeMapper;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Dimensions;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Line;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Pair;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.UnmappedAccountRoleException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.ChargeType;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Turning a lease into journals (spec §6.4–§6.5).
 *
 * <p>This is the moment a contract stops being a proposal. Before it, a lease is
 * paper: lines the accountant can rewrite, a cheque grid that changes with every
 * edit, cheques the register cannot even see. After it, the renter owes money, the
 * landlord holds instruments against that debt, and both facts are in the ledger
 * where an auditor can find them. There is no other way in: {@code activateLease}
 * and {@code PUT /{id}/activate} are gone, because a lease that was ACTIVE without
 * a journal behind it meant "active" and "on the books" were two different truths
 * about the same contract and nothing kept them together.</p>
 *
 * <p><b>Two journals' worth of work, one transaction.</b> A post writes one
 * {@code TCO} dated the contract date, one {@code PDR} per cheque dated that
 * cheque's posting date, flips the lease to ACTIVE and claims the unit. Any
 * failure anywhere — a locked period on the third cheque, a unit another lease
 * already holds — rolls the lot back and leaves a DRAFT lease with no entries and
 * no entry numbers that point at nothing.</p>
 *
 * <p><b>Everything is checked before anything is written.</b> {@link #validate}
 * collects <em>every</em> problem rather than throwing on the first, because the
 * accountant reviewing a contract wants the list, not a conversation. The same
 * method serves the dry run behind the review screen and the real post, so the
 * two cannot drift: what the review promised is exactly what the post enforces.</p>
 *
 * <p><b>The lease row is locked first.</b> Two accountants posting the same
 * contract at the same moment would both read DRAFT, both write a full set of
 * journals and bill the renter twice; the lease's {@code @Version} would fail the
 * second commit, but only after it had burnt an entry number and written entries
 * it then rolls back. See {@code LeaseRepository.findByIdForUpdate}.</p>
 */
@Service
public class LeasePostingService {

    /** "53,000.00" — the shape an accountant reads amounts in, in the refusal messages. */
    private static final DecimalFormat MONEY =
            new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));

    /**
     * Roles every posted lease needs whatever it charges for: the receivable both
     * journals pivot on, the PDC account the instruments sit in, and the bank the
     * cheques will be deposited to. BANK posts nothing today — it is the cheque
     * lifecycle that uses it — but a lease whose property has no bank mapped is a
     * lease whose first deposit run will fail, and the accountant would rather hear
     * that now (spec §5.4).
     */
    private static final Set<AccountRole> ALWAYS_REQUIRED =
            Set.of(AccountRole.RENT_RECEIVABLE, AccountRole.PDC_RECEIVABLE, AccountRole.BANK);

    private final LeaseRepository leaseRepository;
    private final LeaseLineRepository leaseLineRepository;
    private final ChequeRepository chequeRepository;
    private final TenantFiscalSettingsRepository fiscalSettingsRepository;
    private final AccountResolver accountResolver;
    private final PostingService postingService;
    private final LeaseService leaseService;
    private final LeaseAccessPolicy leaseAccessPolicy;
    private final ApplicationEventPublisher events;

    public LeasePostingService(LeaseRepository leaseRepository,
                               LeaseLineRepository leaseLineRepository,
                               ChequeRepository chequeRepository,
                               TenantFiscalSettingsRepository fiscalSettingsRepository,
                               AccountResolver accountResolver,
                               PostingService postingService,
                               LeaseService leaseService,
                               LeaseAccessPolicy leaseAccessPolicy,
                               ApplicationEventPublisher events) {
        this.leaseRepository = leaseRepository;
        this.leaseLineRepository = leaseLineRepository;
        this.chequeRepository = chequeRepository;
        this.fiscalSettingsRepository = fiscalSettingsRepository;
        this.accountResolver = accountResolver;
        this.postingService = postingService;
        this.leaseService = leaseService;
        this.leaseAccessPolicy = leaseAccessPolicy;
        this.events = events;
    }

    // ------------------------------------------------------------------
    // post
    // ------------------------------------------------------------------

    /**
     * Post the lease: TCO, one PDR per cheque, ACTIVE, unit claimed.
     *
     * @throws UnmappedAccountRoleException when the only thing wrong is a role the
     *         property has no account for — a distinct type so the UI can offer to
     *         map it rather than just printing a sentence
     * @throws BusinessRuleViolationException with every other problem joined into
     *         one message
     */
    @Transactional
    public PostLeaseResponse post(UUID leaseId) {
        Lease lease = lockLease(leaseId);
        List<LeaseLine> lines = leaseLineRepository.findByLease_IdOrderBySeqNoAsc(leaseId);
        List<Cheque> cheques = chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId);

        PostingPlan plan = validate(lease, lines, cheques);
        plan.throwIfRefused(propertyIdOf(lease));

        JournalEntry tco = postTco(lease, plan.pairs());
        registerCheques(lease, cheques);

        // The predecessor is retired *before* the successor goes ACTIVE, and while
        // the successor's own row is still untouched — see markPredecessorRenewed.
        markPredecessorRenewed(lease, tco.getEntryNumber());

        lease.setPostingJournalId(tco.getId());
        lease.setPostedAt(Instant.now());
        lease.setPostedBy(currentUserId());

        leaseService.markActiveOnPosting(lease, "Lease posted " + tco.getEntryNumber());
        events.publishEvent(new LeasePostedEvent(lease.getTenantId(), lease.getId(), lease.getContractDate()));

        return response(lease, tco, cheques);
    }

    /**
     * Every validation, no writes, HTTP 200 whatever the answer.
     *
     * <p>{@code readOnly} is the guarantee, not a hint: Hibernate flushes nothing
     * from a read-only transaction, so a mistake in here cannot reach the database.
     * It also rules out {@code TenantFiscalSettingsService.get()}, which creates
     * the settings row on first access — the period lock is read straight from the
     * repository instead, and an absent row simply means nothing is locked.</p>
     */
    @Transactional(readOnly = true)
    public PostLeaseDryRunResponse dryRun(UUID leaseId) {
        Lease lease = leaseRepository.findByIdScopedToTenant(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        leaseAccessPolicy.requireReadable(lease);
        List<LeaseLine> lines = leaseLineRepository.findByLease_IdOrderBySeqNoAsc(leaseId);
        List<Cheque> cheques = chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId);

        PostingPlan plan = validate(lease, lines, cheques);
        List<String> errors = plan.errors(propertyIdOf(lease));
        return new PostLeaseDryRunResponse(
                errors.isEmpty(),
                errors,
                plan.contractValue(),
                plan.contractValueInclVat(),
                plan.chequeTotal(),
                new PostLeaseDryRunResponse.JournalPlan(1, plan.pairs().size() * 2, cheques.size()));
    }

    /**
     * The roles this lease's posting needs to resolve against its property
     * (spec §5.4): whatever its lines credit, plus {@link #ALWAYS_REQUIRED}, plus
     * OUTPUT_VAT when some line actually carries VAT.
     *
     * <p>OUTPUT_VAT is conditional because it is a tenant-level mapping most
     * landlords of residential property will never make, and demanding it from a
     * VAT-free contract would refuse a lease over an account it will never post
     * to.</p>
     */
    @Transactional(readOnly = true)
    public Set<AccountRole> requiredRoles(Lease lease) {
        return requiredRoles(leaseLineRepository.findByLease_IdOrderBySeqNoAsc(lease.getId()));
    }

    private static Set<AccountRole> requiredRoles(List<LeaseLine> lines) {
        Set<AccountRole> roles = EnumSet.copyOf(ALWAYS_REQUIRED);
        for (LeaseLine line : lines) {
            if (line.getChargeType() != null && line.getChargeType().getRole() != null) {
                roles.add(line.getChargeType().getRole());
            }
            if (LeaseVat.vatOf(line).signum() > 0) {
                roles.add(AccountRole.OUTPUT_VAT);
            }
        }
        return roles;
    }

    // ------------------------------------------------------------------
    // amend
    // ------------------------------------------------------------------

    /**
     * Replace a posted lease's lines: reverse the TCO, apply the new lines, post a
     * fresh TCO dated the same contract date (spec §6.5).
     *
     * <p>The cheques are not touched. That is the whole reason amendment is
     * restricted to a grid where every row is still REGISTERED: a REGISTERED cheque
     * is paper in a drawer with one journal against it, and leaving it alone is
     * harmless. A DEPOSITED or CLEARED one has money behind it, and the receivable
     * the new TCO raises would no longer be the receivable that money settled. Those
     * rows change through the cheque lifecycle (§7), never through here.</p>
     *
     * <p>Order matters and is deliberate: the new lines are validated and required
     * to still match the cheque grid <em>before</em> the reversal is written. The
     * transaction would roll an early reversal back anyway, but it would also have
     * consumed a TCR number for a correction that never happened, and gaps in a
     * numbered journal series are the kind of thing an auditor asks about.</p>
     */
    @Transactional
    public PostLeaseResponse amendLines(UUID leaseId, List<LeaseLineInput> newLines, String reason) {
        Lease lease = lockLease(leaseId);
        if (lease.getStatus() != LeaseStatus.ACTIVE) {
            throw new BusinessRuleViolationException(
                    "Only an ACTIVE lease can have its lines amended; this one is " + lease.getStatus());
        }
        UUID reversedJournalId = lease.getPostingJournalId();
        if (reversedJournalId == null) {
            throw new BusinessRuleViolationException("This lease has no posting journal to amend");
        }

        List<Cheque> cheques = chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId);
        for (Cheque c : cheques) {
            if (c.getStatus() != ChequeStatus.REGISTERED) {
                throw new BusinessRuleViolationException("Cheque " + label(c) + " is " + c.getStatus()
                        + "; amend is only possible while all cheques are REGISTERED");
            }
        }

        leaseService.applyLines(lease, newLines);
        leaseService.syncDerivedTotals(lease);
        List<LeaseLine> lines = leaseLineRepository.findByLease_IdOrderBySeqNoAsc(leaseId);

        // ACTIVE is the right status for an amendment and the wrong one for a first
        // post, so the status rule is checked above and skipped here.
        PostingPlan plan = validate(lease, lines, cheques, false);
        plan.throwIfRefused(propertyIdOf(lease));

        postingService.reverse(reversedJournalId, LocalDate.now(), reason);
        JournalEntry tco = postTco(lease, plan.pairs());

        lease.setPostingJournalId(tco.getId());
        leaseRepository.save(lease);
        leaseService.recordLeaseEvent(lease, LeaseStatus.ACTIVE, LeaseStatus.ACTIVE,
                "Lines amended, reposted as " + tco.getEntryNumber()
                        + (reason == null || reason.isBlank() ? "" : ": " + reason));
        events.publishEvent(new LeaseAmendedEvent(lease.getTenantId(), lease.getId(), reversedJournalId, tco.getId()));

        return response(lease, tco, cheques);
    }

    /** Convenience overload for the controller's request body. */
    @Transactional
    public PostLeaseResponse amendLines(UUID leaseId, AmendLeaseLinesRequest request) {
        return amendLines(leaseId, request.lines(), request.reason());
    }

    // ------------------------------------------------------------------
    // validation
    // ------------------------------------------------------------------

    /**
     * Everything that could stop this lease posting, collected.
     *
     * <p>Roles are resolved with {@code resolveOrNull}, never with {@code resolve}
     * in a try/catch: {@code AccountResolver} is a {@code @Transactional} proxy, so
     * an exception thrown out of {@code resolve} marks this transaction
     * rollback-only on its way through the interceptor and the catch block cannot
     * undo it. The dry run would then "succeed" and its commit would fail with
     * "Transaction silently rolled back".</p>
     */
    private PostingPlan validate(Lease lease, List<LeaseLine> lines, List<Cheque> cheques) {
        return validate(lease, lines, cheques, true);
    }

    private PostingPlan validate(Lease lease, List<LeaseLine> lines, List<Cheque> cheques, boolean checkStatus) {
        List<String> lineErrors = new ArrayList<>();
        List<String> otherErrors = new ArrayList<>();
        Set<AccountRole> missingRoles = EnumSet.noneOf(AccountRole.class);

        if (checkStatus && lease.getStatus() != LeaseStatus.DRAFT && lease.getStatus() != LeaseStatus.PENDING_SIGNATURE) {
            otherErrors.add("Only a DRAFT or PENDING_SIGNATURE lease can be posted; this one is " + lease.getStatus() + ".");
        }
        if (lease.getContractDate() == null) {
            otherErrors.add("The lease has no contract date.");
        }
        if (lines.isEmpty()) {
            lineErrors.add("The lease has no charged lines.");
        }

        BigDecimal net = BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO;
        List<Pair> pairs = new ArrayList<>();
        for (LeaseLine line : lines) {
            ChargeType type = line.getChargeType();
            String code = type != null ? type.getCode() : "?";
            String where = "Line " + line.getSeqNo() + " (" + code + ")";
            BigDecimal lineNet = line.getNetAmount() == null ? BigDecimal.ZERO : line.getNetAmount();
            BigDecimal lineVat = LeaseVat.vatOf(line);
            net = net.add(lineNet);
            gross = gross.add(lineNet).add(lineVat);

            Account credit = line.getCreditAccount();
            if (credit == null) {
                // The draft was allowed to be saved unmapped (see LeaseLine); this is
                // where that debt comes due.
                lineErrors.add(where + " has no credit account.");
                continue;
            }
            // Re-checked rather than trusted: the account was an active leaf of the
            // right type when the line was entered, and a chart of accounts is edited
            // between drafting a lease and posting it.
            if (credit.isGroup()) {
                lineErrors.add(where + ": credit account " + credit.getCode() + " is a group account.");
                continue;
            }
            if (!credit.isActive()) {
                lineErrors.add(where + ": credit account " + credit.getCode() + " is inactive.");
                continue;
            }

            String narration = narrationOf(line, type);
            if (lineNet.signum() > 0) {
                pairs.add(PostingRequest.pair(
                        drReceivable(lease, lineNet).withNarration(narration),
                        PostingRequest.cr(credit.getId(), lineNet).withNarration(narration)));
            }
            if (lineVat.signum() > 0) {
                String vatNarration = "VAT on " + (type != null ? type.getNameEn() : code);
                pairs.add(PostingRequest.pair(
                        drReceivable(lease, lineVat).withNarration(vatNarration),
                        PostingRequest.cr(AccountRole.OUTPUT_VAT, lineVat).withNarration(vatNarration)));
            }
        }
        if (!lines.isEmpty() && gross.signum() <= 0) {
            lineErrors.add("The lease charges nothing to post.");
        }

        UUID propertyId = propertyIdOf(lease);
        for (AccountRole role : requiredRoles(lines)) {
            if (accountResolver.resolveOrNull(role, propertyId) == null) {
                missingRoles.add(role);
            }
        }

        BigDecimal chequeTotal = BigDecimal.ZERO;
        if (cheques.isEmpty()) {
            otherErrors.add("The lease has no cheque grid; generate the instalments before posting.");
        }
        for (Cheque c : cheques) {
            chequeTotal = chequeTotal.add(c.getAmount() == null ? BigDecimal.ZERO : c.getAmount());
            if (checkStatus && c.getStatus() != ChequeStatus.DRAFT) {
                // A registered row already has a PDR against it; posting the lease
                // would raise a second one for the same instrument.
                otherErrors.add("Cheque " + label(c) + " is " + c.getStatus()
                        + "; a lease can only be posted while every cheque is still DRAFT.");
            }
            if (c.getChequeDate() == null) {
                otherErrors.add("Cheque " + label(c) + " has no cheque date.");
            }
            if (c.getPostingDate() == null) {
                otherErrors.add("Cheque " + label(c) + " has no posting date.");
            }
        }
        if (!cheques.isEmpty() && chequeTotal.compareTo(gross) != 0) {
            otherErrors.add("Cheque grid totals " + MONEY.format(chequeTotal)
                    + " but contract value" + (gross.compareTo(net) == 0 ? " is " : " incl. VAT is ")
                    + MONEY.format(gross) + ".");
        }

        otherErrors.addAll(periodLockErrors(lease, cheques));

        return new PostingPlan(pairs, net, gross, chequeTotal, missingRoles, lineErrors, otherErrors);
    }

    /**
     * The period lock, read rather than asserted.
     *
     * <p>{@code PostingService} enforces this itself on every entry, so a post that
     * skipped this check would still be refused — but only after the TCO had been
     * written and, for a cheque dated in a closed month, only on the fourth PDR.
     * Checking here means the dry run can say so and the refusal names the date the
     * accountant has to move.</p>
     */
    private List<String> periodLockErrors(Lease lease, List<Cheque> cheques) {
        UUID tenantId = TenantContextHolder.getTenantId();
        LocalDate locked = tenantId == null ? null : fiscalSettingsRepository.findById(tenantId)
                .map(TenantFiscalSettings::getBooksLockedThrough).orElse(null);
        if (locked == null) return List.of();

        List<String> errors = new ArrayList<>();
        if (lease.getContractDate() != null && !lease.getContractDate().isAfter(locked)) {
            errors.add("Cannot post on " + lease.getContractDate() + ": books are locked through " + locked + ".");
        }
        for (Cheque c : cheques) {
            if (c.getPostingDate() != null && !c.getPostingDate().isAfter(locked)) {
                errors.add("Cheque " + label(c) + " cannot post on " + c.getPostingDate()
                        + ": books are locked through " + locked + ".");
            }
        }
        return errors;
    }

    /**
     * The outcome of {@link #validate}: what would be written, and what is stopping
     * it.
     *
     * <p>The three buckets are kept apart for one reason: an unmapped role is worth
     * a distinct exception type so the UI can offer "map it now", but only when it
     * is the <em>only</em> problem. A line with no credit account is the more
     * specific complaint about the very same gap, and reporting the role instead
     * would send the accountant to the property's Accounts tab when the answer is
     * on the line in front of them.</p>
     */
    private record PostingPlan(List<Pair> pairs,
                               BigDecimal contractValue,
                               BigDecimal contractValueInclVat,
                               BigDecimal chequeTotal,
                               Set<AccountRole> missingRoles,
                               List<String> lineErrors,
                               List<String> otherErrors) {

        List<String> errors(UUID propertyId) {
            List<String> all = new ArrayList<>(lineErrors);
            if (!missingRoles.isEmpty()) {
                all.add(new UnmappedAccountRoleException(missingRoles, propertyId).getMessage());
            }
            all.addAll(otherErrors);
            return all;
        }

        void throwIfRefused(UUID propertyId) {
            if (lineErrors.isEmpty() && otherErrors.isEmpty() && !missingRoles.isEmpty()) {
                throw new UnmappedAccountRoleException(missingRoles, propertyId);
            }
            List<String> all = errors(propertyId);
            if (!all.isEmpty()) {
                throw new BusinessRuleViolationException(String.join(" ", all));
            }
        }
    }

    // ------------------------------------------------------------------
    // writing
    // ------------------------------------------------------------------

    private JournalEntry postTco(Lease lease, List<Pair> pairs) {
        return postingService.post(PostingRequest.ofPairs(
                JournalDocType.TCO,
                lease.getContractDate(),
                contractNarration(lease),
                dimensions(lease, null),
                JournalSourceType.LEASE,
                lease.getId(),
                null,
                pairs));
    }

    /**
     * One PDR per row, whatever the mode: a bank transfer promised for March is a
     * receivable in exactly the way a cheque dated March is, and giving cash rows
     * their own treatment is how the register and the ledger came to disagree about
     * what was outstanding.
     */
    private void registerCheques(Lease lease, List<Cheque> cheques) {
        for (Cheque c : cheques) {
            String narration = c.getNarration() != null && !c.getNarration().isBlank()
                    ? c.getNarration() : "Instalment " + c.getSeqNo();
            JournalEntry pdr = postingService.post(PostingRequest.ofPairs(
                    JournalDocType.PDR,
                    c.getPostingDate(),
                    narration,
                    dimensions(lease, c.getId()),
                    JournalSourceType.CHEQUE,
                    c.getId(),
                    null,
                    List.of(PostingRequest.pair(
                            PostingRequest.dr(AccountRole.PDC_RECEIVABLE, c.getAmount()).withNarration(narration),
                            crReceivable(lease, c.getAmount()).withNarration(narration)))));
            c.setPdrJournalId(pdr.getId());
            c.setStatus(ChequeStatus.REGISTERED);
            c.setStatusChangedAt(Instant.now());
            chequeRepository.save(c);
        }
    }

    /**
     * A renewal retires the lease it replaces (spec §6.6). The unit stays occupied
     * throughout — the renter has not moved out, and a moment of VACANT is a moment
     * the unit is lettable to somebody else.
     *
     * <p><b>Flushed on its own, before the successor's row is touched.</b>
     * {@code ux_leases_one_active_per_unit} (changeset 80) is a partial unique index
     * on the unit of every ACTIVE lease, and Hibernate orders the updates within a
     * flush by entity id — a random UUID. Queue both rows and roughly half the time
     * the successor's "status = ACTIVE" is written before the predecessor's
     * "status = RENEWED", the index sees two ACTIVE leases on one unit and the whole
     * post dies on a constraint violation. Flushing here means the predecessor has
     * left ACTIVE before anything else can join it.</p>
     */
    private void markPredecessorRenewed(Lease lease, String entryNumber) {
        if (lease.getRenewedFromLeaseId() == null) return;
        Lease predecessor = leaseRepository.findByIdScopedToTenant(lease.getRenewedFromLeaseId()).orElse(null);
        if (predecessor == null || predecessor.getStatus() != LeaseStatus.ACTIVE) return;
        predecessor.setStatus(LeaseStatus.RENEWED);
        leaseRepository.saveAndFlush(predecessor);
        leaseService.recordLeaseEvent(predecessor, LeaseStatus.ACTIVE, LeaseStatus.RENEWED,
                "Renewed by " + entryNumber);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * The lease row, locked, tenant-checked and access-checked.
     *
     * <p>A NOWAIT conflict is a 400 that says "try again", not a 500: the other
     * caller is almost certainly the same accountant double-clicking Post.</p>
     */
    private Lease lockLease(UUID leaseId) {
        Lease lease;
        try {
            lease = leaseRepository.findByIdForUpdate(leaseId)
                    .orElseThrow(() -> new NotFoundException("Lease not found"));
        } catch (PessimisticLockingFailureException e) {
            throw new BusinessRuleViolationException(
                    "This lease is being posted by another request. Please try again.");
        }
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(lease.getTenantId())) {
            throw new NotFoundException("Lease not found");
        }
        leaseAccessPolicy.requireReadable(lease);
        return lease;
    }

    /**
     * The receivable side of both journals: the lease's own override if it carries
     * one, else the property's RENT_RECEIVABLE mapping (spec §6.3).
     */
    private static Line drReceivable(Lease lease, BigDecimal amount) {
        return lease.getReceivableAccountId() != null
                ? PostingRequest.dr(lease.getReceivableAccountId(), amount)
                : PostingRequest.dr(AccountRole.RENT_RECEIVABLE, amount);
    }

    private static Line crReceivable(Lease lease, BigDecimal amount) {
        return lease.getReceivableAccountId() != null
                ? PostingRequest.cr(lease.getReceivableAccountId(), amount)
                : PostingRequest.cr(AccountRole.RENT_RECEIVABLE, amount);
    }

    /**
     * What the ledger row is called. The line's own narration when the user wrote
     * one — "Rent 01-Oct-26 to 30-Sep-27" is more use in a ledger than "Rent" —
     * and the charge type's name otherwise.
     */
    private static String narrationOf(LeaseLine line, ChargeType type) {
        if (line.getNarration() != null && !line.getNarration().isBlank()) return line.getNarration();
        return type != null ? type.getNameEn() : null;
    }

    private static String contractNarration(Lease lease) {
        Unit unit = lease.getUnit();
        Property property = unit != null ? unit.getProperty() : null;
        StringBuilder sb = new StringBuilder("Tenancy contract");
        if (property != null) sb.append(' ').append(property.getNameEn());
        if (unit != null) sb.append(' ').append(unit.getUnitNumber());
        if (lease.getContractNumber() != null) sb.append(" - ").append(lease.getContractNumber());
        return sb.toString();
    }

    private static Dimensions dimensions(Lease lease, UUID chequeId) {
        Unit unit = lease.getUnit();
        Property property = unit != null ? unit.getProperty() : null;
        return new Dimensions(
                property != null ? property.getId() : null,
                unit != null ? unit.getId() : null,
                lease.getId(),
                lease.getRenter() != null ? lease.getRenter().getId() : null,
                chequeId);
    }

    private static UUID propertyIdOf(Lease lease) {
        Unit unit = lease.getUnit();
        return unit != null && unit.getProperty() != null ? unit.getProperty().getId() : null;
    }

    /** How a refusal names a cheque: by its number when it has one, else by its row. */
    private static String label(Cheque c) {
        return c.getChequeNumber() != null && !c.getChequeNumber().isBlank()
                ? c.getChequeNumber() : "row " + c.getSeqNo();
    }

    private PostLeaseResponse response(Lease lease, JournalEntry tco, List<Cheque> cheques) {
        LocalDate today = LocalDate.now();
        List<ChequeDTO> rows = cheques.stream()
                .map(c -> ChequeMapper.toDto(c, today, lease.getGracePeriodDays()))
                .toList();
        return new PostLeaseResponse(leaseService.getLeaseById(lease.getId()), tco.getId(), tco.getEntryNumber(), rows);
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
