package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.RowLockedException;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.OpeningBalancePosting;
import com.datagami.rentaxis.domain.entity.OpeningBalanceSnapshotRow;
import com.datagami.rentaxis.domain.entity.PropertyAccountMapping;
import com.datagami.rentaxis.domain.entity.TenantDefaultAccountMapping;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.OpeningBalancePostingRepository;
import com.datagami.rentaxis.domain.repository.OpeningBalanceSnapshotRowRepository;
import com.datagami.rentaxis.domain.repository.PropertyAccountMappingRepository;
import com.datagami.rentaxis.domain.repository.TenantDefaultAccountMappingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Step 2 of the cut-over (spec §10.3): the opening-balance grid, PACT's trial
 * balance, and the {@code OB} journal that opens the books balanced.
 *
 * <p><b>The date.</b> One {@code OB} document dated {@code books_start_date − 1}.
 * The opening balance is the balance <em>as at</em> the day before the books open,
 * so the journal lands inside the locked period by construction —
 * {@code PostingService} exempts {@code docType = OB} from the period lock for
 * exactly that reason, at both ends (posting and reversing). The reversal is always
 * dated on the entry it mirrors, never on a supplied or recomputed day: see
 * {@link #reverse}.</p>
 *
 * <p><b>What is manual and what is not.</b> Step 1 — the active-contract import —
 * already writes the rent receivable, PDC, advance-rent, deposit, income, admin-fee
 * and penalty balances from the contracts themselves. Entering those by hand as
 * well would count them twice, so an account mapped to one of {@link #DERIVED_ROLES}
 * is refused outright in the grid and skipped when the journal is built. It is still
 * <em>shown</em>, marked derived, because the accountant needs to see that it has
 * been accounted for — and PACT's trial balance still carries its figure, which is
 * what the reconciliation report compares against. The opening-balance difference
 * account is refused for a different reason: we compute it.
 *
 * <p><b>One definition of "what a post would write".</b> {@link #postable} is the
 * only place that decides which snapshot rows become journal lines and what the
 * balancing figure is; the grid's totals, its {@code difference}, its
 * {@code changedSincePosted} flag and {@link #postFresh}'s actual lines are all read
 * off it. The number on the screen the accountant presses Post from is therefore the
 * number posted, by construction rather than by agreement.</p>
 *
 * <p><b>Matching PACT's rows to our chart.</b> By code, then by exact name
 * (trimmed, case-insensitive) within the tenant — the same rule the spec's
 * property-mapping sheet uses ("mappings by account-name match; unmatched names
 * reported, not guessed", §10.3). A name carried by more than one account is
 * reported as ambiguous, never guessed.</p>
 *
 * <p><b>Opening the books is one act, and it happens once.</b> Every write path
 * takes a pessimistic lock on the tenant's single {@code opening_balance_postings}
 * row before it decides anything, so two clerks pressing Post leave one journal
 * rather than two sets of opening balances. {@link #repost} is the corrected-file
 * path: the live journal is reversed and a fresh one written inside one
 * transaction, so the books are never between two opening balances.</p>
 *
 * <p><b>Every method here is transactional</b>, reads included: {@code TenantAspect}
 * enables Hibernate's tenant filter on the session bound to the current transaction,
 * and without one each repository call gets a session of its own with the filter
 * enabled on a session the query never runs on. The helpers published for Tasks
 * 10–11 enforce that on their callers rather than assuming it — see
 * {@link #requireTransaction}.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OpeningBalanceService {

    private final AccountRepository accounts;
    private final PropertyAccountMappingRepository propertyMappings;
    private final TenantDefaultAccountMappingRepository defaultMappings;
    private final OpeningBalanceSnapshotRowRepository snapshots;
    private final OpeningBalancePostingRepository postings;
    private final JournalEntryRepository journals;
    private final JournalLineRepository journalLines;
    private final PostingService posting;
    private final LedgerQueryService ledger;
    private final AccountResolver resolver;
    private final TenantFiscalSettingsService fiscal;
    private final EntityManager entityManager;

    /**
     * Spec §10.3: "Accounts mapped to roles RENT_RECEIVABLE, PDC_RECEIVABLE,
     * ADVANCE_RENT, SECURITY_DEPOSIT, PARKING_DEPOSIT, RENTAL_INCOME, ADMIN_FEE,
     * *_PENALTY are excluded from manual entry (derived by step 1)." The two
     * {@code *_PENALTY} roles are RENT_PENALTY and CHEQUE_RETURN_PENALTY.
     */
    public static final Set<AccountRole> DERIVED_ROLES = Collections.unmodifiableSet(EnumSet.of(
            AccountRole.RENT_RECEIVABLE, AccountRole.PDC_RECEIVABLE, AccountRole.ADVANCE_RENT,
            AccountRole.SECURITY_DEPOSIT, AccountRole.PARKING_DEPOSIT, AccountRole.RENTAL_INCOME,
            AccountRole.ADMIN_FEE, AccountRole.RENT_PENALTY, AccountRole.CHEQUE_RETURN_PENALTY));

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    /**
     * One row of the grid.
     *
     * <p>{@code derived} means step 1 produces this account's balance;
     * {@code computed} means we do — it is the opening-balance difference account,
     * whose figure is the balancing gap and is recomputed on every post. Both are
     * read-only on the screen and both are refused by {@link #setRow}.</p>
     */
    public record OpeningBalanceRow(UUID accountId, String code, String name, String accountType, UUID propertyId,
                                    boolean derived, AccountRole derivedRole, boolean computed,
                                    BigDecimal derivedDebit, BigDecimal derivedCredit,
                                    BigDecimal enteredDebit, BigDecimal enteredCredit) {}

    /**
     * {@code difference} is {@code totalDebit − totalCredit} over the postable
     * figures and is <em>exactly</em> what the OB journal puts on
     * OPENING_BALANCE_DIFFERENCE — both come from {@link #postable}. The difference
     * account's own row carries that same figure and is excluded from the totals, so
     * the rows on screen sum to zero the way the journal does.
     *
     * <p>{@code changedSincePosted} is true when the grid's postable lines no longer
     * match the live OB journal's — the snapshot stays editable after posting (that
     * is the Replace workflow) and the screen has to say so.</p>
     *
     * <p>{@code problems} are faults the accountant has to fix, or facts they have to
     * know, before posting: a role with no usable account behind it, or a PACT figure
     * on the difference account that we do not carry over.</p>
     */
    public record OpeningBalanceGrid(LocalDate asOf, boolean posted, UUID journalId, String journalNumber,
                                     boolean changedSincePosted,
                                     List<OpeningBalanceRow> rows, BigDecimal totalDebit,
                                     BigDecimal totalCredit, BigDecimal difference,
                                     List<String> problems) {}

    /**
     * What one upload did. {@code totalDebit}/{@code totalCredit} are the file's own
     * sums and {@code balanced} says whether they agree — an unbalanced file is still
     * accepted, because that disagreement is what reconciliation is for, but the
     * upload panel should say so out loud.
     */
    public record SnapshotUploadResult(int stored, List<String> unmatchedCodes, List<String> problems,
                                       BigDecimal totalDebit, BigDecimal totalCredit, boolean balanced) {}

    /**
     * One line of the reconciliation report. Balances are signed debit-positive,
     * matching {@code TrialBalanceRowDTO.balance}, so a credit-balance account reads
     * negative; {@code difference = derivedBalance − pactBalance}. {@code accountId}
     * is null on a PACT row our chart matched by neither code nor name.
     */
    public record ReconciliationRow(UUID accountId, String code, String name, boolean derived,
                                    BigDecimal derivedBalance, BigDecimal pactBalance, BigDecimal difference) {}

    /** accountId → derived role, plus the mappings that point at nothing postable. */
    public record DerivedRoles(Map<UUID, AccountRole> byAccount, List<String> problems) {}

    // ------------------------------------------------------------------
    // grid
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public OpeningBalanceGrid grid() {
        LocalDate asOf = asOf();
        DerivedRoles derived = derived();
        Postable postable = postable(chartIndex(), derived.byAccount());
        Map<UUID, BigDecimal> ourBooks = derivedBalances(asOf);

        List<OpeningBalanceRow> rows = new ArrayList<>();
        for (Account a : accounts.findAll()) {
            if (a.isGroup() || !a.isActive()) continue;
            AccountRole role = derived.byAccount().get(a.getId());
            boolean computed = postable.isDifferenceAccount(a.getId());
            BigDecimal net = postable.byAccount().getOrDefault(a.getId(), ZERO);
            BigDecimal ours = ourBooks.getOrDefault(a.getId(), ZERO);
            rows.add(new OpeningBalanceRow(a.getId(), a.getCode(), a.getName(),
                    a.getAccountType() == null ? null : a.getAccountType().name(), a.getPropertyId(),
                    role != null, role, computed,
                    ours.signum() > 0 ? ours : ZERO,
                    ours.signum() < 0 ? ours.negate() : ZERO,
                    net.signum() > 0 ? net : ZERO,
                    net.signum() < 0 ? net.negate() : ZERO));
        }
        rows.sort(Comparator.comparing(OpeningBalanceRow::code, Comparator.nullsLast(String::compareTo)));

        List<String> problems = new ArrayList<>(derived.problems());
        problems.addAll(postable.problems());

        JournalEntry live = liveJournal(postings.findFirstByOrderByCreatedAtAsc().orElse(null));
        return new OpeningBalanceGrid(asOf, live != null,
                live == null ? null : live.getId(), live == null ? null : live.getEntryNumber(),
                changedSincePosted(live, postable), rows,
                postable.totalDebit(), postable.totalCredit(), postable.gap(), problems);
    }

    // ------------------------------------------------------------------
    // snapshot
    // ------------------------------------------------------------------

    /**
     * Replaces the stored snapshot wholesale: a re-upload is a correction of the whole
     * file, not an addition to it.
     *
     * <p>Nothing here refuses the file. A trial balance whose two sides do not agree,
     * or that names accounts we do not have, is <em>accepted and flagged</em> — that
     * disagreement is what the reconciliation report exists to show, and refusing the
     * upload would hide it. Only rows that cannot be read at all are dropped, each
     * with the line number the accountant has to look at.</p>
     */
    @Transactional
    public SnapshotUploadResult uploadSnapshot(InputStream csv) {
        TrialBalanceCsvParser.CsvParseResult parsed = TrialBalanceCsvParser.parse(csv);
        ChartIndex index = chartIndex();

        snapshots.deleteAllForTenant(requireTenant());

        List<String> problems = new ArrayList<>(parsed.problems());
        List<String> unmatched = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        BigDecimal totalDebit = ZERO, totalCredit = ZERO;
        UUID by = currentUserId();
        int stored = 0;
        for (TrialBalanceCsvParser.CsvRow r : parsed.rows()) {
            // The snapshot is unique per (tenant, code); a file that repeats one would
            // otherwise come back as a constraint violation with no line number on it.
            if (!seen.add(r.code())) {
                problems.add("line " + r.lineNo() + ": account code " + r.code()
                        + " appears more than once; the later row was ignored");
                continue;
            }
            Matched matched = index.match(r.code(), r.name());
            switch (matched.how()) {
                case AMBIGUOUS -> {
                    problems.add("line " + r.lineNo() + ": the account name \"" + r.name().trim()
                            + "\" is ambiguous — more than one account in this chart carries it, so the row"
                            + " was stored but not matched. Give the row this chart's account code.");
                    unmatched.add(r.code());
                }
                case NONE -> unmatched.add(r.code());
                case BY_CODE, BY_NAME -> { /* matched */ }
            }
            OpeningBalanceSnapshotRow row = new OpeningBalanceSnapshotRow();
            row.setAccountCode(r.code());
            row.setAccountName(r.name());
            row.setDebit(r.debit());
            row.setCredit(r.credit());
            row.setUploadedAt(Instant.now());
            row.setUploadedBy(by);
            snapshots.save(row);
            totalDebit = totalDebit.add(r.debit());
            totalCredit = totalCredit.add(r.credit());
            stored++;
        }
        boolean balanced = totalDebit.compareTo(totalCredit) == 0;
        log.info("Opening-balance snapshot stored {} rows ({} Dr / {} Cr, balanced={}), {} unmatched, {} problems",
                stored, totalDebit, totalCredit, balanced, unmatched.size(), problems.size());
        return new SnapshotUploadResult(stored, unmatched, problems, totalDebit, totalCredit, balanced);
    }

    /** Hand-edit one row of the grid. Writes into the same snapshot the CSV fills. */
    @Transactional
    public void setRow(UUID accountId, BigDecimal debit, BigDecimal credit) {
        if (accountId == null) throw new BusinessRuleViolationException("An opening balance needs an account");
        // findByIdScopedToTenant, not findById: the id arrives on a request path, and
        // this one goes through JPQL, which the tenant filter applies to.
        Account a = accounts.findByIdScopedToTenant(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found"));
        if (a.isGroup()) {
            throw new BusinessRuleViolationException(
                    a.getCode() + " " + a.getName() + " is a group account and carries no balance of its own");
        }
        AccountRole role = derived().byAccount().get(accountId);
        if (role != null) {
            throw new BusinessRuleViolationException(a.getCode() + " " + a.getName() + " is mapped to "
                    + role + " and is derived from the contract import, so it cannot be entered by hand");
        }
        Account difference = resolver.resolveOrNull(AccountRole.OPENING_BALANCE_DIFFERENCE, null);
        if (difference != null && difference.getId().equals(accountId)) {
            // Accepting it and then discarding it in postFresh was the same defect as
            // I1 from the other end: a figure the accountant typed that never posts.
            throw new BusinessRuleViolationException(a.getCode() + " " + a.getName()
                    + " carries the opening-balance difference, which is recomputed from the other rows"
                    + " every time the books are opened, so it cannot be entered by hand");
        }
        BigDecimal d = scale(debit);
        BigDecimal c = scale(credit);
        if (d.signum() < 0 || c.signum() < 0) {
            throw new BusinessRuleViolationException("An opening balance cannot be negative; use the other column");
        }
        if (d.signum() != 0 && c.signum() != 0) {
            // ck_ob_snapshots_one_side: a row carries one side, as a journal line does.
            BigDecimal net = d.subtract(c);
            d = net.signum() > 0 ? net : ZERO;
            c = net.signum() < 0 ? net.negate() : ZERO;
        }
        OpeningBalanceSnapshotRow row = snapshots.findByAccountCode(a.getCode())
                .orElseGet(OpeningBalanceSnapshotRow::new);
        row.setAccountCode(a.getCode());
        row.setAccountName(a.getName());
        row.setDebit(d);
        row.setCredit(c);
        row.setUploadedAt(Instant.now());
        row.setUploadedBy(currentUserId());
        snapshots.save(row);
    }

    // ------------------------------------------------------------------
    // post / re-post / reverse
    // ------------------------------------------------------------------

    /**
     * Open the books. Refuses when they are already open — a double-click must not
     * quietly replace a set of opening balances somebody has started reconciling
     * against; {@link #repost} is the deliberate way to do that.
     */
    @Transactional
    public JournalEntry post() {
        LocalDate asOf = asOf();
        OpeningBalancePosting marker = lockMarker(asOf);
        if (liveJournal(marker) != null) {
            throw new BusinessRuleViolationException(
                    "Opening balances have already been posted. Reverse the existing opening-balance journal first, "
                            + "or re-post to replace it.");
        }
        return postFresh(marker, asOf);
    }

    /**
     * Post a corrected trial balance over the existing one: the live opening journal
     * is reversed and a fresh one written, in one transaction under the marker's row
     * lock. Either both happen or neither does, so the books are never between two
     * opening balances — and two clerks re-posting at once still leave exactly one
     * live OB journal.
     *
     * <p>With nothing posted this is simply a first post, which is the right answer
     * for a screen whose button says "Replace": there is nothing to take off first.</p>
     */
    @Transactional
    public JournalEntry repost(String reason) {
        LocalDate asOf = asOf();
        OpeningBalancePosting marker = lockMarker(asOf);
        JournalEntry live = liveJournal(marker);
        if (live != null) {
            // PostingService.reverse, not JournalService.reverse: the HTTP-facing one
            // only reverses MANUAL journals and would refuse this by design. The date
            // is the ORIGINAL's, not asOf() — see reverse().
            posting.reverse(live.getId(), live.getEntryDate(),
                    reason == null || reason.isBlank() ? "Opening balances re-posted" : reason);
            marker.setJournalId(null);
        }
        return postFresh(marker, asOf);
    }

    /**
     * Take the opening journal off the books, leaving the grid's figures in place.
     *
     * <p><b>The mirror is always dated on the entry it reverses</b>, and the caller
     * does not get to choose. {@code PostingService.reverse} no longer applies the
     * period lock to an {@code OB} entry, so any date would be accepted — and
     * {@code JournalLineRepository.balancesAsOf} has no status predicate, so a
     * REVERSED entry still counts towards the balances at its own date. A mirror
     * dated later therefore leaves the whole opening balance standing as at D − 1
     * while this marker says "not posted", the reconciliation report stops
     * subtracting it, and the next {@link #post()} writes a second OB journal on the
     * same day: the books carry the opening balances twice, still balanced, so no
     * invariant in the suite notices. Pinning the date to the entry's own is what
     * keeps the exemption's blast radius to the one day it was meant to cover.</p>
     */
    @Transactional
    public JournalEntry reverse(String reason) {
        LocalDate asOf = asOf();
        OpeningBalancePosting marker = lockMarker(asOf);
        JournalEntry live = liveJournal(marker);
        if (live == null) {
            throw new BusinessRuleViolationException("There is no posted opening-balance journal to reverse");
        }
        JournalEntry mirror = posting.reverse(live.getId(), live.getEntryDate(),
                reason == null || reason.isBlank() ? "Opening balances reversed" : reason);
        marker.setJournalId(null);
        marker.setPostedAt(null);
        postings.save(marker);
        log.info("Opening balances reversed: {} mirrored by {} on {}",
                live.getEntryNumber(), mirror.getEntryNumber(), mirror.getEntryDate());
        return mirror;
    }

    /** Builds and posts the journal. The caller holds the marker's lock and has checked it is free. */
    private JournalEntry postFresh(OpeningBalancePosting marker, LocalDate asOf) {
        Postable postable = postable(chartIndex(), derived().byAccount());
        // Resolved defensively and BEFORE anything is built: the default-account seed
        // is guarded by count() == 0 and can be left partial (issue #299), and an
        // unmapped role surfacing from inside PostingService would name the role
        // without saying which screen has to fix it.
        if (postable.difference() == null) throw new BusinessRuleViolationException(noDifferenceAccountMessage());

        List<PostingRequest.Line> lines = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> e : postable.byAccount().entrySet()) {
            BigDecimal net = e.getValue();
            if (net.signum() == 0) continue;
            String narration = postable.isDifferenceAccount(e.getKey())
                    ? "Opening balance difference" : narration(asOf);
            lines.add(net.signum() > 0
                    ? PostingRequest.dr(e.getKey(), net).withNarration(narration)
                    : PostingRequest.cr(e.getKey(), net.negate()).withNarration(narration));
        }
        if (lines.size() < 2) {
            throw new BusinessRuleViolationException(
                    "Opening balances need at least two accounts with a figure before they can be posted");
        }

        // docType OB is exempt from the period lock in PostingService — the opening
        // journal is dated the day BEFORE the books open, which is by definition locked.
        JournalEntry entry = posting.post(new PostingRequest(
                JournalDocType.OB, asOf, narration(asOf), PostingRequest.Dimensions.none(),
                JournalSourceType.OPENING_BALANCE, marker.getId(), null, lines));

        marker.setJournalId(entry.getId());
        marker.setPostedAt(Instant.now());
        marker.setPostedBy(entry.getPostedBy());
        postings.save(marker);
        log.info("Opening balances posted as {} ({} lines, difference {})",
                entry.getEntryNumber(), lines.size(), postable.gap());
        return entry;
    }

    // ------------------------------------------------------------------
    // reconciliation (spec §10.3)
    // ------------------------------------------------------------------

    /**
     * Per account: the balance our books derive from the contract import, PACT's
     * figure from the uploaded trial balance, and the gap (spec §10.3).
     *
     * <p><b>Read-only, and it posts nothing.</b> It is the report an accountant
     * refreshes while chasing a difference, not a step in the cut-over.</p>
     *
     * <p><b>Why the opening journal is subtracted.</b> It is dated the same day as
     * this report, so a raw trial balance would count it in the "derived" column and
     * every manually entered account would trivially reconcile against itself. Its
     * own lines are therefore taken back out. Once it has been reversed the original
     * and its mirror already net to zero, so nothing is subtracted — which is why
     * only a LIVE posting is considered.</p>
     *
     * <p><b>Both halves of the question.</b> The report walks the uploaded snapshot
     * <em>and</em> the accounts we have a balance on, so a contract left out of the
     * import and a contract imported that PACT never had both produce a row. A row
     * that appeared on one side only would hide exactly one of the two mistakes a
     * cut-over makes.</p>
     *
     * <p><b>No paging</b> (accepted at review): the row count is bounded by the
     * tenant's own chart of accounts plus the codes in one uploaded file — hundreds,
     * the same order the accounts screen already renders whole, and the web paginates
     * it client-side. {@code LedgerQueryService.MAX_ROWS} exists because ledger rows
     * scale with transactions; these do not.</p>
     */
    @Transactional(readOnly = true)
    public List<ReconciliationRow> reconcile() {
        LocalDate asOf = asOf();
        Map<UUID, AccountRole> derived = derived().byAccount();
        ChartIndex index = chartIndex();

        Map<UUID, BigDecimal> derivedBalances = derivedBalances(asOf);
        Map<UUID, String[]> identity = new HashMap<>();                       // accountId -> {code, name}
        for (var row : ledger.trialBalance(asOf, null)) {
            identity.put(row.accountId(), new String[]{row.code(), row.name()});
        }
        for (Account a : index.all()) {
            identity.putIfAbsent(a.getId(), new String[]{a.getCode(), a.getName()});
        }

        List<ReconciliationRow> rows = new ArrayList<>();
        Map<UUID, BigDecimal> pactByAccount = new LinkedHashMap<>();
        for (OpeningBalanceSnapshotRow s : snapshots.findAllByOrderByAccountCodeAsc()) {
            Account a = index.match(s.getAccountCode(), s.getAccountName()).account();
            BigDecimal pact = s.getDebit().subtract(s.getCredit());
            if (a == null) {
                rows.add(new ReconciliationRow(null, s.getAccountCode(), s.getAccountName(), false,
                        ZERO, pact, ZERO.subtract(pact)));
                continue;
            }
            pactByAccount.merge(a.getId(), pact, BigDecimal::add);
        }
        for (Map.Entry<UUID, BigDecimal> e : pactByAccount.entrySet()) {
            String[] id = identity.getOrDefault(e.getKey(), new String[]{null, null});
            BigDecimal d = derivedBalances.getOrDefault(e.getKey(), ZERO);
            rows.add(new ReconciliationRow(e.getKey(), id[0], id[1],
                    derived.containsKey(e.getKey()), d, e.getValue(), d.subtract(e.getValue())));
        }
        for (Map.Entry<UUID, BigDecimal> e : derivedBalances.entrySet()) {
            if (pactByAccount.containsKey(e.getKey()) || e.getValue().signum() == 0) continue;
            String[] id = identity.getOrDefault(e.getKey(), new String[]{null, null});
            rows.add(new ReconciliationRow(e.getKey(), id[0], id[1],
                    derived.containsKey(e.getKey()), e.getValue(), ZERO, e.getValue()));
        }
        rows.sort(Comparator.comparing(ReconciliationRow::code, Comparator.nullsLast(String::compareTo)));
        return rows;
    }

    /**
     * What <em>our</em> books hold per account as at {@code asOf}, debit-positive,
     * with the live opening journal's own lines taken back out.
     *
     * <p>After the cut-over's step 1 this is, for the nine {@code DERIVED_ROLES},
     * exactly what the contract import produced: the receivable the {@code TCO}
     * raised, the PDC receivable the {@code PDR}s hold, the advance rent the
     * catch-up has not released yet, the income it has, the bank the cleared
     * cheques reached. It is the column the grid shows beside the accountant's own
     * figures and the column the reconciliation report compares with PACT's.</p>
     *
     * <p><b>Why the opening journal is removed.</b> It is dated the same day this is
     * read, so a raw trial balance would count it here and every manually entered
     * account would reconcile against itself. Once the journal has been reversed the
     * original and its mirror already net to zero, which is why only a LIVE posting
     * is subtracted.</p>
     *
     * <p>One definition, two readers — {@link #grid()} and {@link #reconcile()} —
     * because a grid whose derived column disagreed with the reconciliation report
     * would be two answers to one question.</p>
     */
    private Map<UUID, BigDecimal> derivedBalances(LocalDate asOf) {
        Map<UUID, BigDecimal> balances = new HashMap<>();
        for (var row : ledger.trialBalance(asOf, null)) {
            balances.merge(row.accountId(), row.balance(), BigDecimal::add);
        }
        for (JournalLine l : openingJournalLines()) {
            // trialBalance is debit-positive, so removing this line's contribution
            // means ADDING the negation of (debit − credit). Written out rather than
            // as a subtract so the sign convention is visible at the call site.
            balances.merge(l.getAccount().getId(),
                    l.getDebit().subtract(l.getCredit()).negate(), BigDecimal::add);
        }
        return balances;
    }

    // ------------------------------------------------------------------
    // what a post would write — the single definition
    // ------------------------------------------------------------------

    /**
     * The journal a post right now would produce: {@code byAccount} is
     * account id → signed amount (debit-positive) in a stable order, <em>including</em>
     * the balancing line against the opening-balance difference account.
     */
    private record Postable(LinkedHashMap<UUID, BigDecimal> byAccount, BigDecimal totalDebit,
                            BigDecimal totalCredit, BigDecimal gap, Account difference,
                            List<String> problems) {

        boolean isDifferenceAccount(UUID accountId) {
            return difference != null && difference.getId().equals(accountId);
        }
    }

    /**
     * Resolves the stored snapshot onto the chart and works out the lines and the
     * balancing figure — once, for the grid and the posting alike.
     *
     * <p>Skipped, every one of them deliberately rather than by omission: rows that
     * match no account (PACT's trial balance legitimately carries accounts we do not
     * have), group and inactive accounts, accounts step 1 derives, and the difference
     * account itself — PACT's own suspense figure is not carried over, because ours is
     * the gap between everything else, and posting both would count the same
     * unexplained balance twice. That last one is reported rather than dropped
     * quietly.</p>
     *
     * <p>Two PACT rows that resolve to the same account of ours (one by code, one by
     * name) are summed, so the grid and the journal agree on one line per account.</p>
     */
    private Postable postable(ChartIndex index, Map<UUID, AccountRole> derived) {
        Account difference = resolver.resolveOrNull(AccountRole.OPENING_BALANCE_DIFFERENCE, null);
        List<String> problems = new ArrayList<>();
        if (difference == null) problems.add(noDifferenceAccountMessage());

        LinkedHashMap<UUID, BigDecimal> byAccount = new LinkedHashMap<>();
        BigDecimal totalDebit = ZERO, totalCredit = ZERO;
        for (OpeningBalanceSnapshotRow r : snapshots.findAllByOrderByAccountCodeAsc()) {
            Account a = index.match(r.getAccountCode(), r.getAccountName()).account();
            if (a == null || a.isGroup() || !a.isActive() || derived.containsKey(a.getId())) continue;
            if (difference != null && difference.getId().equals(a.getId())) {
                if (r.getDebit().signum() != 0 || r.getCredit().signum() != 0) {
                    problems.add(a.getCode() + " " + a.getName() + ": PACT's own opening-balance difference"
                            + " is not carried over; ours is recomputed from the other rows.");
                }
                continue;
            }
            byAccount.merge(a.getId(), r.getDebit().subtract(r.getCredit()), BigDecimal::add);
        }
        for (BigDecimal net : byAccount.values()) {
            if (net.signum() > 0) totalDebit = totalDebit.add(net);
            else totalCredit = totalCredit.add(net.negate());
        }

        BigDecimal gap = totalDebit.subtract(totalCredit);
        if (difference != null && gap.signum() != 0) {
            // Negated because the line CLOSES the gap: an excess of debits is credited away.
            byAccount.put(difference.getId(), gap.negate());
        }
        return new Postable(byAccount, totalDebit, totalCredit, gap, difference, problems);
    }

    /** True when the grid's postable lines no longer match the live OB journal's. */
    private boolean changedSincePosted(JournalEntry live, Postable postable) {
        if (live == null) return false;
        Map<UUID, BigDecimal> onBooks = new HashMap<>();
        for (JournalLine l : journalLines.findByEntry_IdOrderByLineNoAsc(live.getId())) {
            onBooks.merge(l.getAccount().getId(), l.getDebit().subtract(l.getCredit()), BigDecimal::add);
        }
        onBooks.values().removeIf(v -> v.signum() == 0);
        Map<UUID, BigDecimal> wanted = new HashMap<>(postable.byAccount());
        wanted.values().removeIf(v -> v.signum() == 0);
        if (!wanted.keySet().equals(onBooks.keySet())) return true;
        for (Map.Entry<UUID, BigDecimal> e : wanted.entrySet()) {
            if (e.getValue().compareTo(onBooks.get(e.getKey())) != 0) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // matching PACT's rows onto our chart
    // ------------------------------------------------------------------

    /** How a snapshot row found its account — or why it did not. */
    public enum SnapshotMatch { BY_CODE, BY_NAME, AMBIGUOUS, NONE }

    private record Matched(Account account, SnapshotMatch how) {}

    /**
     * The tenant's chart, indexed by code and by normalised name.
     *
     * <p>Built once per operation rather than queried per row: a PACT trial balance
     * is hundreds of rows against hundreds of accounts.</p>
     */
    private record ChartIndex(List<Account> all, Map<String, Account> byCode, Map<String, List<Account>> byName) {

        /**
         * Code first, then exact name (trimmed, case-insensitive) — the same rule the
         * spec's property-mapping sheet uses. A name more than one account carries is
         * ambiguous and is never guessed: two accounts called "Cash In Hand" mean the
         * accountant has to say which, and silently taking the first would put a
         * balance on the wrong one.
         */
        Matched match(String code, String name) {
            Account exact = byCode.get(code == null ? "" : code.trim());
            if (exact != null) return new Matched(exact, SnapshotMatch.BY_CODE);
            String key = normalise(name);
            if (key.isEmpty()) return new Matched(null, SnapshotMatch.NONE);
            List<Account> named = byName.getOrDefault(key, List.of());
            if (named.size() == 1) return new Matched(named.get(0), SnapshotMatch.BY_NAME);
            if (named.size() > 1) return new Matched(null, SnapshotMatch.AMBIGUOUS);
            return new Matched(null, SnapshotMatch.NONE);
        }

        private static String normalise(String s) {
            return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        }
    }

    private ChartIndex chartIndex() {
        List<Account> all = accounts.findAll();
        Map<String, Account> byCode = new HashMap<>();
        Map<String, List<Account>> byName = new HashMap<>();
        for (Account a : all) {
            byCode.put(a.getCode(), a);
            byName.computeIfAbsent(ChartIndex.normalise(a.getName()), k -> new ArrayList<>()).add(a);
        }
        return new ChartIndex(all, byCode, byName);
    }

    // ------------------------------------------------------------------
    // the integration surface published for Tasks 10-11
    // ------------------------------------------------------------------

    /**
     * The opening balance is the balance as at the day before the books open
     * (spec §10.3).
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public LocalDate asOf() {
        requireTransaction("asOf");
        LocalDate booksStart = fiscal.booksStartDate();
        if (booksStart == null) {
            throw new BusinessRuleViolationException(
                    "Set the books start date in Settings → Fiscal before working on opening balances");
        }
        return booksStart.minusDays(1);
    }

    /** The POSTED opening-balance marker, if the tenant has one that has not been reversed. */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<OpeningBalancePosting> livePosting() {
        requireTransaction("livePosting");
        return postings.findFirstByOrderByCreatedAtAsc().filter(p -> liveJournal(p) != null);
    }

    /** accountId → the derived role it is mapped to, across property mappings and tenant defaults. */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<UUID, AccountRole> derivedAccountRoles() {
        return derived().byAccount();
    }

    /**
     * Which accounts step 1 will produce, and which mappings claim to but cannot.
     *
     * <p>A mapping row is not the same thing as a usable account: the account it
     * points at may be a group or deactivated, in which case {@code AccountResolver}
     * would not post to it either. Treating such a row as "derived" would exclude a
     * real balance from the manual grid and produce nothing to replace it; treating
     * it as manual would post a balance the contract import is about to post again.
     * Neither is silent — the mapping is reported so somebody fixes it.</p>
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public DerivedRoles derived() {
        requireTransaction("derived");
        Map<UUID, AccountRole> out = new HashMap<>();
        List<String> problems = new ArrayList<>();
        for (PropertyAccountMapping m : propertyMappings.findAll()) {
            if (DERIVED_ROLES.contains(m.getRole())) record(out, problems, m.getRole(), m.getAccount());
        }
        for (TenantDefaultAccountMapping m : defaultMappings.findAll()) {
            if (DERIVED_ROLES.contains(m.getRole())) record(out, problems, m.getRole(), m.getAccount());
        }
        return new DerivedRoles(out, problems);
    }

    /**
     * The live opening journal's own lines.
     *
     * <p>The reconciliation report is dated the same day the OB journal is, so a raw
     * trial balance would count the opening entry in the column that is supposed to
     * show what the <em>contract import</em> derived. These are what it subtracts
     * back out; once the journal has been reversed the original and its mirror
     * already net to zero, which is why only a LIVE posting is considered.</p>
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<JournalLine> openingJournalLines() {
        requireTransaction("openingJournalLines");
        return livePosting()
                .map(p -> journalLines.findByEntry_IdOrderByLineNoAsc(p.getJournalId()))
                .orElseGet(List::of);
    }

    /**
     * Refuses to run outside a transaction, naming the method.
     *
     * <p>The {@code MANDATORY} propagation above says the same thing and enforces it
     * for a caller that arrives through the proxy — but Spring cannot intercept a
     * <em>self-invocation</em>, and every internal caller here is one. This assertion
     * holds on both paths, which is the point: the class Javadoc's rule is that no
     * repository is touched without a transaction, and {@code TenantAspect} enables
     * the Hibernate tenant filter on the session bound to that transaction. With no
     * transaction the filter is enabled on one session and the query runs on another —
     * an unfiltered, cross-tenant read that nothing else would notice.</p>
     */
    private static void requireTransaction(String method) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OpeningBalanceService." + method
                    + "() reads the tenant's books and must be called inside a transaction");
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /** The marker's journal, if it is still on the books. */
    private JournalEntry liveJournal(OpeningBalancePosting marker) {
        if (marker == null || marker.getJournalId() == null) return null;
        return journals.findById(marker.getJournalId())
                .filter(e -> e.getStatus() == JournalStatus.POSTED)
                .orElse(null);
    }

    private static void record(Map<UUID, AccountRole> out, List<String> problems, AccountRole role, Account account) {
        if (account == null) {
            problems.add("The role " + role + " is mapped to an account that no longer exists; re-map it under "
                    + "Property → Accounts or Settings → Default accounts.");
            return;
        }
        if (account.isGroup() || !account.isActive()) {
            problems.add("The role " + role + " is mapped to " + account.getCode() + " " + account.getName()
                    + ", which cannot be posted to (" + (account.isGroup() ? "group account" : "inactive")
                    + "). Opening balances cannot tell whether that account is derived or manual until it is re-mapped.");
            return;
        }
        out.putIfAbsent(account.getId(), role);
    }

    private static String noDifferenceAccountMessage() {
        return "No account is mapped to the " + AccountRole.OPENING_BALANCE_DIFFERENCE + " role. "
                + "Map it under Settings → Default accounts (the seeded chart calls it F-02, "
                + "\"Opening Balance Difference\") before posting opening balances.";
    }

    private static String narration(LocalDate asOf) {
        return "Opening balances as at " + asOf;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static UUID requireTenant() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) throw new IllegalStateException("No tenant in context");
        return tenantId;
    }

    /** Same shape as {@code PostingService.currentUserId}: null for a system-run import. */
    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    /**
     * The tenant's opening-balance marker, created if absent and locked for the length
     * of this transaction.
     *
     * <p><b>Created first, then locked</b>: a row lock can only be taken on a row that
     * exists, and two first posts racing would otherwise both find nothing and both
     * insert, one dying on {@code ux_opening_balance_postings_tenant} with a raw
     * constraint violation. The conflict-safe insert makes the loser block on the
     * winner's row and then find it, which is the same serialisation one step
     * earlier.</p>
     *
     * <p><b>{@code refresh} under the lock, not a {@code @Lock} finder</b>, for the
     * reason documented on {@code VoucherService#lockForWrite} and
     * {@code ImportBatchService#lockForWrite}: a locking JPQL query hands back the
     * first-level-cache instance with its <em>stale</em> state, so the loser of the
     * race would take the lock and then decide on the pre-lock journal id — exactly
     * the bug the lock exists to prevent. {@code refresh} both takes the lock and
     * re-reads, which it can do here because this entity carries no {@code @Version}.</p>
     *
     * <p><b>The explicit tenant comparison is a guard, not decoration</b>:
     * {@code entityManager.refresh} bypasses {@code TenantAspect} altogether, which
     * only enables the filter around {@code domain.repository..*}.</p>
     */
    private OpeningBalancePosting lockMarker(LocalDate asOf) {
        UUID tenantId = requireTenant();
        postings.insertIfAbsent(tenantId, asOf);
        OpeningBalancePosting marker = postings.findFirstByOrderByCreatedAtAsc()
                .orElseThrow(() -> new IllegalStateException("Opening-balance marker missing after insert"));
        try {
            entityManager.refresh(marker, LockModeType.PESSIMISTIC_WRITE);
        } catch (PessimisticLockingFailureException | PessimisticLockException | LockTimeoutException e) {
            // Three types for one event: an EntityManager call is not put through
            // Spring Data's exception translation, so JPA's own types get out.
            throw new RowLockedException("The opening balances are being posted right now; try again");
        }
        if (!tenantId.equals(marker.getTenantId())) {
            throw new NotFoundException("Opening balances not found");
        }
        marker.setAsOf(asOf);
        return marker;
    }
}
