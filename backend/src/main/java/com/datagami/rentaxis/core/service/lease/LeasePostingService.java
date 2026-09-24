package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.AmendLeaseLinesRequest;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.dto.lease.PostLeaseDryRunResponse;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.RowLockedException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.cheque.ChequeMapper;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Pair;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.UnmappedAccountRoleException;
import com.datagami.rentaxis.core.service.renewal.RenewalOpportunityService;
import com.datagami.rentaxis.core.service.vat.VatTaxPointService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.ChargeType;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.ImportBatch;
import com.datagami.rentaxis.domain.entity.ImportBatchLease;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.VatTiming;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.ImportBatchLeaseRepository;
import com.datagami.rentaxis.domain.repository.ImportBatchRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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

    /**
     * "53,000.00" — the shape an accountant reads amounts in, in the refusal
     * messages. Formatted per call rather than through a shared
     * {@code DecimalFormat}: that class is mutable and not thread-safe, and a
     * static one shared by every concurrent post is a data race that shows up as a
     * garbled figure in an error message nobody can reproduce.
     */
    static String money(BigDecimal amount) {
        return String.format(Locale.ROOT, "%,.2f", amount);
    }

    private final LeaseRepository leaseRepository;
    private final LeaseLineRepository leaseLineRepository;
    private final ChequeRepository chequeRepository;
    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final TenantFiscalSettingsRepository fiscalSettingsRepository;
    private final AccountResolver accountResolver;
    private final PostingService postingService;
    private final LeaseChequeRegistrar chequeRegistrar;
    private final LeaseService leaseService;
    private final LeaseAccessPolicy leaseAccessPolicy;
    private final DepositCarryForward depositCarryForward;
    private final RenewalOpportunityService renewalOpportunities;
    private final ApplicationEventPublisher events;

    /**
     * Only for {@link #refuseIfItBelongsToADraftImportBatch}. Repositories rather
     * than {@code ImportBatchService}: that service depends on {@code PostingService}
     * and on the fiscal calendar, and the lease module has no business pulling the
     * cut-over module in to answer one boolean.
     */
    private final ImportBatchLeaseRepository importBatchLeases;
    private final ImportBatchRepository importBatches;

    /** Where the organisation's TRN lives — a VAT-bearing contract needs one (spec 2026-09-24 §1). */
    private final LandlordOrgRepository landlordOrgs;

    /** The instalment VAT schedule, for the amendment rules (spec 2026-09-24 §1). */
    private final VatTaxPointService vatTaxPoints;

    public LeasePostingService(LeaseRepository leaseRepository,
                               LeaseLineRepository leaseLineRepository,
                               ChequeRepository chequeRepository,
                               AccountRepository accountRepository,
                               JournalEntryRepository journalEntryRepository,
                               TenantFiscalSettingsRepository fiscalSettingsRepository,
                               AccountResolver accountResolver,
                               PostingService postingService,
                               LeaseChequeRegistrar chequeRegistrar,
                               LeaseService leaseService,
                               LeaseAccessPolicy leaseAccessPolicy,
                               DepositCarryForward depositCarryForward,
                               RenewalOpportunityService renewalOpportunities,
                               ApplicationEventPublisher events,
                               ImportBatchLeaseRepository importBatchLeases,
                               ImportBatchRepository importBatches,
                               LandlordOrgRepository landlordOrgs,
                               VatTaxPointService vatTaxPoints) {
        this.leaseRepository = leaseRepository;
        this.leaseLineRepository = leaseLineRepository;
        this.chequeRepository = chequeRepository;
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.fiscalSettingsRepository = fiscalSettingsRepository;
        this.accountResolver = accountResolver;
        this.postingService = postingService;
        this.chequeRegistrar = chequeRegistrar;
        this.leaseService = leaseService;
        this.leaseAccessPolicy = leaseAccessPolicy;
        this.depositCarryForward = depositCarryForward;
        this.renewalOpportunities = renewalOpportunities;
        this.events = events;
        this.importBatchLeases = importBatchLeases;
        this.importBatches = importBatches;
        this.landlordOrgs = landlordOrgs;
        this.vatTaxPoints = vatTaxPoints;
    }

    // ------------------------------------------------------------------
    // post
    // ------------------------------------------------------------------

    /**
     * Post the lease: TCO, one PDR per cheque, ACTIVE, unit claimed.
     *
     * <p><b>Refused for a contract that belongs to a DRAFT cut-over batch</b>
     * (review M4). This door is reachable from the lease screen by anyone who may
     * post, and an imported contract pushed through it comes out subtly wrong in
     * three ways at once: its journals carry no {@code importBatchId}, so they are
     * outside the period-lock exemption a pre-books contract needs and outside
     * "Reverse batch" forever; the cheque replay is skipped, so the statuses and
     * dates PACT exported are silently ignored and no {@code CRT}/{@code CBR} is
     * written; and afterwards the batch can be neither reversed (its TCO carries no
     * batch id) nor discarded (journals name the lease). The remedy is one sentence
     * and one button, so it is said rather than guessed at.</p>
     *
     * @throws UnmappedAccountRoleException when the only thing wrong is a role the
     *         property has no account for — a distinct type so the UI can offer to
     *         map it rather than just printing a sentence
     * @throws BusinessRuleViolationException with every other problem joined into
     *         one message
     */
    @Transactional
    public PostLeaseResponse post(UUID leaseId) {
        draftImportBatchProblem(leaseId).ifPresent(m -> { throw new BusinessRuleViolationException(m); });
        return post(leaseId, null);
    }

    /**
     * "This contract belongs to import batch X; post the batch instead." — or empty
     * when this door is the right one.
     *
     * <p><b>A sentence rather than a throw</b>, because two callers need it in two
     * shapes (ruling R28): {@link #post(UUID)} refuses with it, and {@link #dryRun}
     * has to <em>report</em> it. A dry run that answered {@code ok: true} for a
     * contract the very next click would refuse is the one answer that endpoint must
     * never give — its whole purpose is that the review step gets every problem at
     * once instead of one refusal per attempt.</p>
     *
     * <p>The link table carries no tenant column of its own — every reader has to
     * resolve the batch and compare, which is what {@code ContractImportValidator}'s
     * lookups do — so the batch is loaded through the filtered repository and
     * compared again explicitly. Only a <b>DRAFT</b> batch blocks: once the batch has
     * posted, its contracts are ACTIVE and the post's own status check refuses
     * them anyway, and a REVERSED batch's contracts are back in DRAFT deliberately so
     * that "Post again" can put them back — through the batch, which is the door this
     * one points at.</p>
     */
    private Optional<String> draftImportBatchProblem(UUID leaseId) {
        UUID tenantId = TenantContextHolder.getTenantId();
        for (ImportBatchLease link : importBatchLeases.findByLeaseId(leaseId)) {
            ImportBatch batch = importBatches.findById(link.getBatchId()).orElse(null);
            if (batch == null || batch.getStatus() != ImportBatchStatus.DRAFT) continue;
            if (tenantId != null && !tenantId.equals(batch.getTenantId())) continue;
            return Optional.of(
                    "This contract belongs to import batch " + batch.getId() + "; post the batch instead.");
        }
        return Optional.empty();
    }

    /**
     * The same post, as part of a cut-over import batch (spec §10.3, controller
     * ruling R4).
     *
     * <p><b>Three things change, and all three follow from one fact:</b> a cut-over
     * contract is dated before the day the client's books open, which is a period
     * that is closed by definition.</p>
     * <ul>
     *   <li>The {@code TCO} and every {@code PDR} carry {@code importBatchId}, which
     *       is what exempts them from the period lock in {@code PostingService} and
     *       what lets "Reverse batch" find them again.</li>
     *   <li>The period-lock <em>pre-check</em> is skipped. It is a courtesy that
     *       turns a refusal buried in the fourth PDR into one the dry run can print;
     *       applied to an import it would refuse every contract before the exemption
     *       it is anticipating ever ran.</li>
     *   <li>No activation e-mail. A cut-over is hundreds of tenancies that have been
     *       running for months, and telling every renter their lease has just been
     *       activated is the first visible effect the landlord's move would have.
     *       {@code ContractImportPersistService} makes the same choice about
     *       LEASE_CREATED, for the same reason.</li>
     * </ul>
     *
     * <p>The recognition schedule is still built — {@code LeasePostedEvent} is
     * published either way — because the catch-up that follows has nothing to post
     * without it.</p>
     *
     * @param importBatchId non-null only for a cut-over bulk post.
     */
    @Transactional
    public PostLeaseResponse post(UUID leaseId, UUID importBatchId) {
        return post(leaseId, importBatchId,
                importBatchId == null ? Preconditions.FOR_POST : Preconditions.FOR_IMPORT_POST,
                importBatchId == null);
    }

    /**
     * The post a <b>portfolio import</b> (Properties → Import Portfolio) makes for a
     * row the sheet marked ACTIVE (gap #83).
     *
     * <p>It is the interactive post in every respect that touches the ledger — no
     * batch id, so the period lock applies to its journals exactly as it would to
     * the accountant clicking Post — with the one difference the cut-over also
     * makes: nobody is e-mailed. An import is the landlord moving tenancies that
     * already exist onto the system, and "your lease has been activated" landing in
     * every renter's inbox would be the first thing they noticed about it.</p>
     *
     * <p><b>Cheque numbers (#80).</b> A post-dated cheque is registered with its
     * number here as everywhere else, except the rows named in
     * {@code importGeneratedRows}: the ones the import itself <em>generated</em>
     * (deposit and fee rows, and the rent rows when the workbook had no Cheques
     * sheet), for which no number exists yet. A row the landlord's sheet stated is
     * not exempt — the sheet carries its number (UniqueId).</p>
     *
     * @param importGeneratedRows ids of the cheque rows the import generated.
     */
    @Transactional
    public PostLeaseResponse postForPortfolioImport(UUID leaseId, Set<UUID> importGeneratedRows) {
        draftImportBatchProblem(leaseId).ifPresent(m -> { throw new BusinessRuleViolationException(m); });
        return post(leaseId, null, Preconditions.FOR_PORTFOLIO_IMPORT, false,
                importGeneratedRows == null ? Set.of() : Set.copyOf(importGeneratedRows));
    }

    private PostLeaseResponse post(UUID leaseId, UUID importBatchId, Preconditions checks, boolean announce) {
        return post(leaseId, importBatchId, checks, announce, Set.of());
    }

    private PostLeaseResponse post(UUID leaseId, UUID importBatchId, Preconditions checks, boolean announce,
                                   Set<UUID> numberExempt) {
        Lease lease = lockLease(leaseId);
        List<LeaseLine> lines = leaseLineRepository.findByLease_IdOrderBySeqNoAsc(leaseId);
        List<Cheque> cheques = chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId);
        allocateVatIfNeverAllocated(lease, lines, cheques);

        PostingPlan plan = validate(lease, lines, cheques, checks, numberExempt);
        plan.throwIfRefused(propertyIdOf(lease));

        JournalEntry tco = postTco(lease, plan.pairs(), lease.getContractDate(), contractNarration(lease),
                importBatchId);
        registerCheques(lease, cheques, importBatchId, numberExempt);

        // The renter's deposit follows them into the new contract (spec §6.6). It
        // has to happen inside this transaction and before the predecessor is
        // retired: the JV is subject to the same period lock as the TCO, and a
        // renewal that went on the books without the deposit that paid for it
        // would leave the money on a lease nothing will ever settle.
        depositCarryForward.carry(lease, importBatchId);

        // The predecessor is retired *before* the successor goes ACTIVE, and while
        // the successor's own row is still untouched — see markPredecessorRenewed.
        markPredecessorRenewed(lease, tco.getEntryNumber());

        lease.setPostingJournalId(tco.getId());
        lease.setPostedAt(Instant.now());
        lease.setPostedBy(currentUserId());

        leaseService.markActiveOnPosting(lease, "Lease posted " + tco.getEntryNumber(), announce);
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
     *
     * <p><b>It answers for the door it is standing in front of</b> (ruling R28). The
     * batch rule {@link #post(UUID)} enforces is reported here too, first in the
     * list: a dry run that said {@code ok: true} for an imported DRAFT contract and
     * then watched the post refuse would be worse than no dry run, because the whole
     * contract of this endpoint is that the review step sees every problem before the
     * button rather than one refusal per attempt.</p>
     */
    @Transactional(readOnly = true)
    public PostLeaseDryRunResponse dryRun(UUID leaseId) {
        Lease lease = leaseRepository.findByIdScopedToTenant(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        leaseAccessPolicy.requireReadable(lease);
        List<LeaseLine> lines = leaseLineRepository.findByLease_IdOrderBySeqNoAsc(leaseId);
        List<Cheque> cheques = chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId);

        PostingPlan plan = validate(lease, lines, cheques);
        List<String> errors = new ArrayList<>();
        // First, because it is the only one of these that is not about the contract's
        // own contents: no amount of fixing the grid makes this door the right one.
        draftImportBatchProblem(leaseId).ifPresent(errors::add);
        errors.addAll(plan.errors(propertyIdOf(lease)));
        // What "carry the deposit forward" is actually worth today. Shown because
        // it is not the figure on last year's contract — a partly refunded deposit
        // carries only what is left — and an accountant approving the renewal
        // should see the number before the JV exists. The lock problem it could hit
        // is the contract date's, which is already in `errors`: the JV is dated the
        // same day as the TCO.
        return new PostLeaseDryRunResponse(
                errors.isEmpty(),
                errors,
                plan.contractValue(),
                plan.contractValueInclVat(),
                plan.chequeTotal(),
                depositCarryForward.total(lease),
                new PostLeaseDryRunResponse.JournalPlan(1, plan.pairs().size() * 2, cheques.size()));
    }

    /**
     * The roles this posting actually needs resolved against the property
     * (spec §5.4), and nothing more.
     *
     * <p>Only what is genuinely reached for:</p>
     * <ul>
     *   <li>{@code RENT_RECEIVABLE} — both journals pivot on it, <em>unless</em> the
     *       lease names its own receivable, in which case the role is never
     *       consulted;</li>
     *   <li>{@code PDC_RECEIVABLE} — every PDR debits it;</li>
     *   <li>{@code OUTPUT_VAT} — only when some line actually carries VAT. It is a
     *       tenant-level mapping most residential landlords will never make, and
     *       demanding it from a VAT-free contract refuses a lease over an account it
     *       would never post to;</li>
     *   <li>a line's own role — only when the line has <em>no</em> credit account, so
     *       the role is the thing that was supposed to supply one. A line that names
     *       its account outright does not need its role mapped: the TCO credits the
     *       account by id and the role is not read. Requiring it anyway refused
     *       perfectly good contracts over a mapping the posting never touches;</li>
     *   <li>{@code BANK} — only when some cheque row names no debit account of its
     *       own. The PDR does not touch the bank; BANK matters at clear time, and a
     *       row with nowhere for its cleared funds to land would strand. A row that
     *       already carries an account is not the property mapping's business.</li>
     * </ul>
     */
    private static Set<AccountRole> requiredRoles(Lease lease, List<LeaseLine> lines, List<Cheque> cheques) {
        Set<AccountRole> roles = EnumSet.of(AccountRole.PDC_RECEIVABLE);
        if (lease.getReceivableAccountId() == null) {
            roles.add(AccountRole.RENT_RECEIVABLE);
        }
        for (LeaseLine line : lines) {
            if (line.getCreditAccount() == null
                    && line.getChargeType() != null && line.getChargeType().getRole() != null) {
                roles.add(line.getChargeType().getRole());
            }
            if (LeaseVat.vatOf(line).signum() > 0) {
                roles.add(AccountRole.OUTPUT_VAT);
                // The TCO parks an INSTALMENT lease's VAT here until each
                // instalment's tax point moves it to OUTPUT_VAT (spec 2026-09-24 §1).
                if (lease.getVatTiming() == VatTiming.INSTALMENT) {
                    roles.add(AccountRole.OUTPUT_VAT_DEFERRED);
                }
            }
        }
        for (Cheque c : cheques) {
            if (c.getDebitAccount() == null) {
                roles.add(AccountRole.BANK);
                break;
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
     *
     * <p><b>Every POSTED {@code TCO} on the lease is reversed, not just the one
     * {@code postingJournalId} names.</b> An extension (spec §6.7) posts a second
     * TCO and deliberately leaves the first on the lease, so a lease that has been
     * extended carries two. This method reposts <em>all</em> the lease's lines —
     * {@code applyLines} replaces the whole set, and the Σ check below is against Σ
     * of the whole register — so reversing one of the two would have left the
     * extension's charges on the books twice: rent receivable and income overstated
     * by exactly the extension's value, while the Σ guard reported everything was
     * fine because it was comparing the new total against the same total. Reverse
     * exactly the journals whose lines are being reposted, and the ledger after an
     * amendment is what a from-scratch posting of the amended lease would be.</p>
     */
    @Transactional
    public PostLeaseResponse amendLines(UUID leaseId, List<LeaseLineInput> newLines, String reason) {
        Lease lease = lockLease(leaseId);
        if (lease.getStatus() != LeaseStatus.ACTIVE) {
            throw new BusinessRuleViolationException(
                    "Only an ACTIVE lease can have its lines amended; this one is " + lease.getStatus());
        }
        if (lease.getPostingJournalId() == null) {
            throw new BusinessRuleViolationException("This lease has no posting journal to amend");
        }
        List<JournalEntry> contractEntries = postedContractEntries(leaseId);
        if (contractEntries.isEmpty()) {
            throw new BusinessRuleViolationException("This lease has no posting journal to amend");
        }

        List<Cheque> cheques = chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId);
        for (Cheque c : cheques) {
            if (c.getStatus() != ChequeStatus.REGISTERED) {
                throw new BusinessRuleViolationException("Cheque " + label(c) + " is " + c.getStatus()
                        + "; amend is only possible while all cheques are REGISTERED");
            }
        }
        // Anything whose VAT has been declared is settled, and settled things are
        // corrected by an addendum's delta, never rewritten (spec 2026-09-24 §1).
        vatTaxPoints.requireNothingDeclared(leaseId);

        leaseService.applyAmendedLines(lease, newLines);
        leaseService.syncDerivedTotals(lease);
        List<LeaseLine> lines = leaseLineRepository.findByLease_IdOrderBySeqNoAsc(leaseId);
        if (lease.getVatTiming() == VatTiming.INSTALMENT) {
            // The new lines may charge different VAT; the register rows are the same
            // paper, so their VAT is re-spread pro rata before the Σ check below.
            InstalmentVat.fill(cheques, java.util.Set.of(), InstalmentVat.contractVat(lines),
                    InstalmentVat.contractTaxable(lines));
            chequeRepository.saveAll(cheques);
        }

        // ACTIVE is the right status for an amendment and the wrong one for a first
        // post, so the status rule is checked above and skipped here.
        PostingPlan plan = validate(lease, lines, cheques, Preconditions.FOR_AMEND);
        plan.throwIfRefused(propertyIdOf(lease));

        // Nothing declared, so every point is PLANNED: cancelled here, and the
        // amended event rebuilds the schedule from the re-spread rows. The TCO
        // reversal below carries the deferred credit away with it.
        vatTaxPoints.cancelPlanned(leaseId);

        LocalDate reversedOn = LocalDate.now();
        // Collected as they are reversed, so the event names exactly the entries this
        // amendment unwound — the contract's own TCO and one per extension.
        List<UUID> reversedJournalIds = new ArrayList<>(contractEntries.size());
        for (JournalEntry contract : contractEntries) {
            postingService.reverse(contract.getId(), reversedOn, reason);
            reversedJournalIds.add(contract.getId());
        }
        JournalEntry tco = postTco(lease, plan.pairs());

        lease.setPostingJournalId(tco.getId());
        leaseRepository.save(lease);
        leaseService.recordLeaseEvent(lease, LeaseStatus.ACTIVE, LeaseStatus.ACTIVE,
                "Lines amended, reposted as " + tco.getEntryNumber()
                        + (reason == null || reason.isBlank() ? "" : ": " + reason));
        events.publishEvent(new LeaseAmendedEvent(lease.getTenantId(), lease.getId(), reversedJournalIds, tco.getId()));

        return response(lease, tco, cheques);
    }

    /** Convenience overload for the controller's request body. */
    @Transactional
    public PostLeaseResponse amendLines(UUID leaseId, AmendLeaseLinesRequest request) {
        return amendLines(leaseId, request.lines(), request.reason());
    }

    /**
     * Every still-POSTED {@code TCO} raised against this lease, oldest first — the
     * contract's own and one per extension.
     *
     * <p>There is no column listing them and there deliberately is not one: a lease
     * extended three times would need three, and {@code postingJournalId} already
     * names the first. They are found the way every other journal on a lease is
     * found, by {@code sourceType LEASE / sourceId leaseId}, narrowed to TCO (a
     * deposit carry-forward JV shares the source) and to POSTED (a TCO an earlier
     * amendment already reversed is history, and {@code PostingService.reverse}
     * refuses to reverse it twice anyway).</p>
     */
    private List<JournalEntry> postedContractEntries(UUID leaseId) {
        return journalEntryRepository
                .findBySourceTypeAndSourceIdOrderByEntryDateAscCreatedAtAsc(JournalSourceType.LEASE, leaseId)
                .stream()
                .filter(e -> e.getDocType() == JournalDocType.TCO && e.getStatus() == JournalStatus.POSTED)
                .toList();
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
        return validate(lease, lines, cheques, Preconditions.FOR_POST);
    }

    /**
     * Which "is this lease untouched?" checks apply.
     *
     * <p>They are two separate questions and an amendment answers them differently:
     * an ACTIVE lease with REGISTERED cheques is exactly what amendment operates on,
     * while for a first post either one would mean the contract is already on the
     * books. One shared flag hid that, and reading {@code validate(…, false)} at the
     * call site told you nothing about which rule was being waived.</p>
     */
    /*
     * pdcNumbersRequired (#80): a post-dated cheque with no number cannot be
     * matched at the bank, bounced by number or searched for, so a post refuses
     * one. The cut-over keeps the rule too — ContractImportValidator already
     * refuses a PDC row without its number, so a batch never reaches here with one.
     *
     * The PORTFOLIO import keeps the rule too, with one narrow exemption: the
     * rows the import itself GENERATED (deposit and fee rows, and with no Cheques
     * sheet the whole grid) — passed by id, never "every row of this door" — have
     * no number to give, and refusing would leave a running tenancy off the books
     * (gap #83), the worse error. The import result counts them so the numbers
     * are typed in afterwards (ChequeDetailsService, bulk attach). A row the
     * sheet stated carries its UniqueId and is not exempt.
     */
    private record Preconditions(boolean leaseMustBeUnposted, boolean chequesMustBeDraft,
                                 boolean periodLockApplies, boolean pdcNumbersRequired) {
        static final Preconditions FOR_POST = new Preconditions(true, true, true, true);
        /** An amendment does not touch the cheques, so it has nothing to say about their numbers. */
        static final Preconditions FOR_AMEND = new Preconditions(false, false, true, false);
        /**
         * A cut-over import: the same untouched-lease rules, and no period-lock
         * pre-check. Its journals carry the batch id and {@code PostingService}
         * exempts them, so checking the lock here would refuse a contract the
         * ledger is about to accept — see {@link #post(UUID, UUID)}.
         */
        static final Preconditions FOR_IMPORT_POST = new Preconditions(true, true, false, true);
        /**
         * A portfolio import's post: the interactive rules, lock included — its
         * journals carry no batch id, so {@code PostingService} enforces the lock on
         * them and the pre-check has to say so first.
         */
        static final Preconditions FOR_PORTFOLIO_IMPORT = new Preconditions(true, true, true, true);
    }

    private PostingPlan validate(Lease lease, List<LeaseLine> lines, List<Cheque> cheques, Preconditions checks) {
        return validate(lease, lines, cheques, checks, Set.of());
    }

    private PostingPlan validate(Lease lease, List<LeaseLine> lines, List<Cheque> cheques, Preconditions checks,
                                 Set<UUID> numberExempt) {
        // Errors about the accounts this posting would use — a line's credit account
        // or the lease's receivable override. They rank above the role-level
        // complaint, which is usually the same gap seen from further away.
        List<String> accountErrors = new ArrayList<>();
        List<String> otherErrors = new ArrayList<>();
        Set<AccountRole> missingRoles = EnumSet.noneOf(AccountRole.class);

        if (checks.leaseMustBeUnposted()
                && lease.getStatus() != LeaseStatus.DRAFT && lease.getStatus() != LeaseStatus.PENDING_SIGNATURE) {
            if (lease.getStatus() == LeaseStatus.ACTIVE && lease.getPostingJournalId() == null) {
                // What the pre-v2 portfolio import left behind (gap #83): ACTIVE, with
                // no contract journal. Posting from ACTIVE would skip the DRAFT-only
                // grid rules the whole post relies on, so it is refused — but by name,
                // so nobody reads "this one is ACTIVE" as "it is already on the books".
                otherErrors.add("This lease is ACTIVE but was never posted (no contract journal), so it is"
                        + " not on the books. An ACTIVE lease cannot be posted; recreate it as a draft"
                        + " and post that.");
            } else {
                otherErrors.add("Only a DRAFT or PENDING_SIGNATURE lease can be posted; this one is "
                        + lease.getStatus() + ".");
            }
        }
        if (lease.getContractDate() == null) {
            otherErrors.add("The lease has no contract date.");
        }
        if (lines.isEmpty()) {
            accountErrors.add("The lease has no charged lines.");
        }
        accountErrors.addAll(receivableOverrideErrors(lease));

        LinePlan linePlan = planLines(lease, lines);
        BigDecimal net = linePlan.net();
        BigDecimal gross = linePlan.gross();
        List<Pair> pairs = linePlan.pairs();
        accountErrors.addAll(linePlan.errors());
        if (!lines.isEmpty() && gross.signum() <= 0) {
            accountErrors.add("The lease charges nothing to post.");
        }

        missingRoles.addAll(unmappedRoles(lease, lines, cheques));

        BigDecimal chequeTotal = BigDecimal.ZERO;
        if (cheques.isEmpty()) {
            otherErrors.add("The lease has no cheque grid; generate the instalments before posting.");
        }
        for (Cheque c : cheques) {
            chequeTotal = chequeTotal.add(c.getAmount() == null ? BigDecimal.ZERO : c.getAmount());
            if (checks.chequesMustBeDraft() && c.getStatus() != ChequeStatus.DRAFT) {
                // A registered row already has a PDR against it; posting the lease
                // would raise a second one for the same instrument.
                otherErrors.add("Cheque " + label(c) + " is " + c.getStatus()
                        + "; a lease can only be posted while every cheque is still DRAFT.");
            }
            // Reported here rather than left to PostingService's "Line amounts must be
            // positive": that one fires halfway through writing the grid's journals,
            // so the dry run would have promised a clean post and the real one would
            // die on the fourth PDR.
            if (c.getAmount() == null || c.getAmount().signum() <= 0) {
                otherErrors.add("Cheque " + label(c) + " must be for an amount greater than zero.");
            }
            if (c.getChequeDate() == null) {
                otherErrors.add("Cheque " + label(c) + " has no cheque date.");
            }
            if (checks.pdcNumbersRequired() && c.getMode() == ChequeMode.PDC
                    && (c.getChequeNumber() == null || c.getChequeNumber().isBlank())
                    && !numberExempt.contains(c.getId())) {
                // Cash and transfer rows have no cheque number by nature.
                otherErrors.add("Cheque #" + c.getSeqNo() + " has no number; a post-dated cheque needs"
                        + " its number before the lease is posted.");
            }
            if (c.getPostingDate() == null) {
                otherErrors.add("Cheque " + label(c) + " has no posting date.");
            }
        }
        if (!cheques.isEmpty() && chequeTotal.compareTo(gross) != 0) {
            otherErrors.add("Cheque grid totals " + money(chequeTotal)
                    + " but contract value" + (gross.compareTo(net) == 0 ? " is " : " incl. VAT is ")
                    + money(gross) + ".");
        }
        otherErrors.addAll(instalmentVatErrors(lease, lines, cheques, checks.periodLockApplies()));

        if (checks.periodLockApplies()) {
            otherErrors.addAll(periodLockErrors(lease, cheques));
        }

        return new PostingPlan(pairs, net, gross, chequeTotal, missingRoles, accountErrors, otherErrors);
    }

    /**
     * The {@code TCO} pairs a set of lines would raise, and everything wrong with
     * the accounts they name.
     *
     * <p>Split out of {@link #validate} because an extension posts a further TCO
     * for its <em>new lines only</em> (spec §6.7) and has to build it by the very
     * same rules: one pair per line against the lease's receivable, a second pair
     * against {@code OUTPUT_VAT} where the line carries VAT, the same narration
     * rule, the same re-check of a credit account that may have been retired since
     * the line was entered. Copying thirty lines of that into the extension is how
     * two doors onto one ledger come to disagree about which account a fee credits.
     * Package-visible, not public: {@code LeaseRenewalService} is the only caller
     * and this is not an API.</p>
     */
    LinePlan planLines(Lease lease, List<LeaseLine> lines) {
        List<String> errors = new ArrayList<>();
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
                errors.add(where + " has no credit account.");
                continue;
            }
            // Re-checked rather than trusted: the account was an active leaf of the
            // right type when the line was entered, and a chart of accounts is edited
            // between drafting a lease and posting it.
            if (credit.isGroup()) {
                errors.add(where + ": credit account " + credit.getCode() + " is a group account.");
                continue;
            }
            if (!credit.isActive()) {
                errors.add(where + ": credit account " + credit.getCode() + " is inactive.");
                continue;
            }

            String narration = narrationOf(line, type);
            if (lineNet.signum() > 0) {
                pairs.add(PostingRequest.pair(
                        LeaseChequeRegistrar.drReceivable(lease, lineNet).withNarration(narration),
                        PostingRequest.cr(credit.getId(), lineNet).withNarration(narration)));
            }
            if (lineVat.signum() > 0) {
                String vatNarration = "VAT on " + (type != null ? type.getNameEn() : code);
                // INSTALMENT: the contract is not a tax invoice, so it creates no tax
                // point — the VAT waits in OUTPUT_VAT_DEFERRED for each instalment's
                // (spec 2026-09-24 §1). CONTRACT: a lease posted before that model,
                // whose VAT has always been declared on the contract date.
                AccountRole vatRole = lease.getVatTiming() == VatTiming.CONTRACT
                        ? AccountRole.OUTPUT_VAT : AccountRole.OUTPUT_VAT_DEFERRED;
                pairs.add(PostingRequest.pair(
                        LeaseChequeRegistrar.drReceivable(lease, lineVat).withNarration(vatNarration),
                        PostingRequest.cr(vatRole, lineVat).withNarration(vatNarration)));
            }
        }
        return new LinePlan(pairs, net, gross, errors);
    }

    /** What {@link #planLines} found: the entry to write, its totals, and its complaints. */
    record LinePlan(List<Pair> pairs, BigDecimal net, BigDecimal gross, List<String> errors) {
    }

    /**
     * The roles {@link #requiredRoles} asks for that the property has no account
     * for. Package-visible for the same reason as {@link #planLines}: an extension
     * needs exactly this question answered about its own lines and rows.
     */
    Set<AccountRole> unmappedRoles(Lease lease, List<LeaseLine> lines, List<Cheque> cheques) {
        Set<AccountRole> missing = EnumSet.noneOf(AccountRole.class);
        UUID propertyId = propertyIdOf(lease);
        for (AccountRole role : requiredRoles(lease, lines, cheques)) {
            if (accountResolver.resolveOrNull(role, propertyId) == null) {
                missing.add(role);
            }
        }
        return missing;
    }

    /**
     * The lease's own receivable account, when it overrides the property's
     * (spec §6.3). Both the TCO's debits and every PDR's credit go to it, so a bad
     * one poisons the whole posting.
     *
     * <p>Loaded through {@code findByIdScopedToTenant} — JPQL, so the Hibernate
     * tenant filter applies. Spring Data's {@code findById} bypasses filters in
     * Hibernate 7, and this id reached the row from a request body: handing it
     * straight to {@code PostingService}, which looks accounts up by id, would let a
     * lease in one tenant raise its receivable against another tenant's leaf.</p>
     */
    private List<String> receivableOverrideErrors(Lease lease) {
        UUID id = lease.getReceivableAccountId();
        if (id == null) return List.of();
        String where = "The lease's receivable account ";
        Account account = accountRepository.findByIdScopedToTenant(id).orElse(null);
        if (account == null) {
            return List.of(where + id + " does not exist.");
        }
        if (account.isGroup()) {
            return List.of(where + account.getCode() + " is a group account.");
        }
        if (!account.isActive()) {
            return List.of(where + account.getCode() + " is inactive.");
        }
        if (account.getAccountType() != AccountType.ASSET) {
            return List.of(where + account.getCode() + " must be an ASSET account.");
        }
        return List.of();
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
        return periodLockErrors(lease.getContractDate(), cheques);
    }

    /**
     * The same check against an explicit entry date. An extension's TCO carries the
     * <em>extension's</em> contract date, not the lease's original one, and its
     * rows carry their own posting dates; asking the lease would check a period
     * nothing is being written into.
     */
    List<String> periodLockErrors(LocalDate entryDate, List<Cheque> cheques) {
        UUID tenantId = TenantContextHolder.getTenantId();
        LocalDate locked = tenantId == null ? null : fiscalSettingsRepository.findById(tenantId)
                .map(TenantFiscalSettings::getBooksLockedThrough).orElse(null);
        if (locked == null) return List.of();

        List<String> errors = new ArrayList<>();
        if (entryDate != null && !entryDate.isAfter(locked)) {
            errors.add("Cannot post on " + entryDate + ": books are locked through " + locked + ".");
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
     * What stops an INSTALMENT lease's VAT from being declared instalment by
     * instalment (spec 2026-09-24 §1):
     * <ul>
     *   <li>{@code Σ row.vat_amount} must equal the contract's VAT exactly — the
     *       rows are what the tax points will move, so a fils short strands it in
     *       the deferred account for ever. A grid that was never allocated (every
     *       row zero) is not refused: {@link #allocateVatIfNeverAllocated} spreads
     *       it at post, and the dry run reports what that post would see;</li>
     *   <li>the organisation needs a TRN — every tax point issues a tax invoice
     *       (product decision 2026-09-24) and one without the supplier's TRN is not
     *       a tax invoice;</li>
     *   <li>no row's tax point may fall inside the period lock: the nightly job skips
     *       a locked date, so the VAT would never be declared.</li>
     * </ul>
     * A legacy CONTRACT lease and a lease with no VAT are exempt from all three.
     */
    private List<String> instalmentVatErrors(Lease lease, List<LeaseLine> lines, List<Cheque> cheques,
                                             boolean periodLockApplies) {
        BigDecimal contractVat = InstalmentVat.contractVat(lines);
        if (lease.getVatTiming() != VatTiming.INSTALMENT || contractVat.signum() == 0) return List.of();
        List<String> errors = new ArrayList<>();
        BigDecimal rowVat = InstalmentVat.rowVat(cheques);
        if (rowVat.signum() != 0 && rowVat.compareTo(contractVat) != 0) {
            errors.add("The cheque grid carries VAT of " + money(rowVat) + " but the contract charges "
                    + money(contractVat) + "; the instalments' VAT must add up to the contract's.");
        }
        UUID tenantId = lease.getTenantId();
        String trn = tenantId == null ? null : landlordOrgs.findById(tenantId).map(o -> o.getTrn()).orElse(null);
        if (trn == null || trn.isBlank()) {
            errors.add("This contract charges VAT, so each instalment issues a tax invoice, and the"
                    + " organisation has no TRN. Add the TRN to the organisation's details first.");
        }
        if (periodLockApplies) {
            LocalDate locked = tenantId == null ? null : fiscalSettingsRepository.findById(tenantId)
                    .map(TenantFiscalSettings::getBooksLockedThrough).orElse(null);
            if (locked != null) {
                for (Cheque c : cheques) {
                    boolean carriesVat = rowVat.signum() == 0 || (c.getVatAmount() != null && c.getVatAmount().signum() > 0);
                    if (carriesVat && c.getChequeDate() != null && !c.getChequeDate().isAfter(locked)) {
                        errors.add("Cheque " + label(c) + "'s VAT tax point (" + c.getChequeDate()
                                + ") falls in a locked period: books are locked through " + locked + ".");
                    }
                }
            }
        }
        return errors;
    }

    /**
     * A grid saved before its lease's VAT was spread over it — every row still at
     * zero while the contract charges VAT — is allocated pro rata here, at post,
     * rather than refused: nothing about it is wrong except that nobody asked.
     * Only an INSTALMENT lease's DRAFT rows are touched.
     */
    private void allocateVatIfNeverAllocated(Lease lease, List<LeaseLine> lines, List<Cheque> cheques) {
        if (lease.getVatTiming() != VatTiming.INSTALMENT || cheques.isEmpty()) return;
        BigDecimal contractVat = InstalmentVat.contractVat(lines);
        if (contractVat.signum() == 0 || InstalmentVat.rowVat(cheques).signum() != 0) return;
        if (cheques.stream().anyMatch(c -> c.getStatus() != ChequeStatus.DRAFT)) return;
        InstalmentVat.fill(cheques, java.util.Set.of(), contractVat, InstalmentVat.contractTaxable(lines));
        chequeRepository.saveAll(cheques);
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
                               List<String> accountErrors,
                               List<String> otherErrors) {

        List<String> errors(UUID propertyId) {
            List<String> all = new ArrayList<>(accountErrors);
            if (!missingRoles.isEmpty()) {
                all.add(new UnmappedAccountRoleException(missingRoles, propertyId).getMessage());
            }
            all.addAll(otherErrors);
            return all;
        }

        void throwIfRefused(UUID propertyId) {
            if (accountErrors.isEmpty() && otherErrors.isEmpty() && !missingRoles.isEmpty()) {
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
        return postTco(lease, pairs, lease.getContractDate(), contractNarration(lease));
    }

    JournalEntry postTco(Lease lease, List<Pair> pairs, LocalDate entryDate, String narration) {
        return postTco(lease, pairs, entryDate, narration, null);
    }

    /**
     * A {@code TCO} on this lease with a date and a narration of the caller's
     * choosing — what an extension posts (spec §6.7).
     *
     * <p>Same doc type, same source, same dimensions as the contract's own
     * posting, because in the ledger an extension <em>is</em> more of the same
     * contract: the renter owes more against the same receivable under the same
     * lease. It is found by {@code sourceType LEASE / sourceId leaseId} alongside
     * the original, which is why nothing new is written onto the lease to point at
     * it — {@code lease.postingJournalId} keeps naming the first TCO, the one an
     * amendment would reverse.</p>
     */
    JournalEntry postTco(Lease lease, List<Pair> pairs, LocalDate entryDate, String narration,
                         UUID importBatchId) {
        return postingService.post(PostingRequest.ofPairs(
                JournalDocType.TCO,
                entryDate,
                narration,
                LeaseChequeRegistrar.dimensions(lease, null),
                JournalSourceType.LEASE,
                lease.getId(),
                importBatchId,
                pairs));
    }

    /**
     * One PDR per row, whatever the mode: a bank transfer promised for March is a
     * receivable in exactly the way a cheque dated March is, and giving cash rows
     * their own treatment is how the register and the ledger came to disagree about
     * what was outstanding.
     *
     * <p>Delegated row by row to {@link LeaseChequeRegistrar} rather than posted
     * here. A lease posts its whole grid at once; {@code ChequeService} registers a
     * replacement, a penalty row or a late receipt one at a time on a lease already
     * on the books. Both have to raise the identical entry — same pair, same
     * dimensions, same date rule, same receivable override — and two copies of that
     * would eventually differ on exactly the detail nobody re-reads.</p>
     */
    private void registerCheques(Lease lease, List<Cheque> cheques, UUID importBatchId, Set<UUID> numberExempt) {
        for (Cheque c : cheques) {
            if (numberExempt.contains(c.getId())) {
                chequeRegistrar.registerGeneratedByImport(lease, c);
            } else {
                chequeRegistrar.register(lease, c, importBatchId);
            }
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
        if (predecessor == null) return;

        // The three statuses LeaseRenewalService will renew from. An EXPIRED or
        // NOTICE_GIVEN predecessor is retired here too: RENEWED is not merely "was
        // active and stopped", it is "handed its unit, and possibly its deposit, to
        // the next contract", and that is exactly what has just happened to it.
        if (RENEWABLE_PREDECESSOR.contains(predecessor.getStatus())) {
            LeaseStatus previous = predecessor.getStatus();
            predecessor.setStatus(LeaseStatus.RENEWED);
            leaseRepository.saveAndFlush(predecessor);
            leaseService.recordLeaseEvent(predecessor, previous, LeaseStatus.RENEWED,
                    "Renewed by " + entryNumber);
        }

        // The renewal funnel closes when the successor is on the books, not when
        // somebody remembers to tick it. markRenewedIfOpen, not markRenewed: this
        // is a @Transactional proxy, and the NotFoundException the throwing variant
        // raises for a lease with no open opportunity — an early renewal, or one
        // already closed by hand — would mark this whole post rollback-only.
        renewalOpportunities.markRenewedIfOpen(predecessor.getId());
    }

    /** What a lease must be for a successor's post to retire it (spec §6.6). */
    static final Set<LeaseStatus> RENEWABLE_PREDECESSOR =
            EnumSet.of(LeaseStatus.ACTIVE, LeaseStatus.EXPIRED, LeaseStatus.NOTICE_GIVEN);

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * The lease row, locked, tenant-checked and access-checked.
     *
     * <p>A NOWAIT conflict is a 400 that says "try again", not a 500: the other
     * caller is almost certainly the same accountant double-clicking Post.</p>
     */
    Lease lockLease(UUID leaseId) {
        Lease lease;
        try {
            lease = leaseRepository.findByIdForUpdate(leaseId)
                    .orElseThrow(() -> new NotFoundException("Lease not found"));
        } catch (PessimisticLockingFailureException e) {
            throw new RowLockedException(
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

    static UUID propertyIdOf(Lease lease) {
        Unit unit = lease.getUnit();
        return unit != null && unit.getProperty() != null ? unit.getProperty().getId() : null;
    }

    /** How a refusal names a cheque: by its number when it has one, else by its row. */
    private static String label(Cheque c) {
        return c.getChequeNumber() != null && !c.getChequeNumber().isBlank()
                ? c.getChequeNumber() : "row " + c.getSeqNo();
    }

    PostLeaseResponse response(Lease lease, JournalEntry tco, List<Cheque> cheques) {
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
