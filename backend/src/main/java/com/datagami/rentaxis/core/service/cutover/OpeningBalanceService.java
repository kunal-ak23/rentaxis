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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * exactly that reason, at both ends (posting and reversing).</p>
 *
 * <p><b>What is manual and what is not.</b> Step 1 — the active-contract import —
 * already writes the rent receivable, PDC, advance-rent, deposit, income, admin-fee
 * and penalty balances from the contracts themselves. Entering those by hand as
 * well would count them twice, so an account mapped to one of {@link #DERIVED_ROLES}
 * is refused outright in the grid and skipped when the journal is built. It is still
 * <em>shown</em>, marked derived, because the accountant needs to see that it has
 * been accounted for — and PACT's trial balance still carries its figure, which is
 * what the reconciliation report compares against.</p>
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
 * enabled on a session the query never runs on.</p>
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

    public record OpeningBalanceRow(UUID accountId, String code, String name, String accountType, UUID propertyId,
                                    boolean derived, AccountRole derivedRole,
                                    BigDecimal derivedDebit, BigDecimal derivedCredit,
                                    BigDecimal enteredDebit, BigDecimal enteredCredit) {}

    /**
     * {@code difference} is {@code totalDebit − totalCredit} over the entered figures;
     * the OB journal closes it against OPENING_BALANCE_DIFFERENCE. {@code problems}
     * are configuration faults the accountant has to fix before posting will work —
     * a role with no usable account behind it.
     */
    public record OpeningBalanceGrid(LocalDate asOf, boolean posted, UUID journalId, String journalNumber,
                                     List<OpeningBalanceRow> rows, BigDecimal totalDebit,
                                     BigDecimal totalCredit, BigDecimal difference,
                                     List<String> problems) {}

    public record SnapshotUploadResult(int stored, List<String> unmatchedCodes, List<String> problems) {}

    /**
     * One line of the reconciliation report. Balances are signed debit-positive,
     * matching {@code TrialBalanceRowDTO.balance}, so a credit-balance account reads
     * negative; {@code difference = derivedBalance − pactBalance}. {@code accountId}
     * is null on a PACT code our chart has no account for.
     */
    public record ReconciliationRow(UUID accountId, String code, String name, boolean derived,
                                    BigDecimal derivedBalance, BigDecimal pactBalance, BigDecimal difference) {}

    /** accountId → derived role, plus the mappings that point at nothing postable. */
    record DerivedRoles(Map<UUID, AccountRole> byAccount, List<String> problems) {}

    // ------------------------------------------------------------------
    // grid
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public OpeningBalanceGrid grid() {
        LocalDate asOf = asOf();
        DerivedRoles derived = derived();
        Map<String, OpeningBalanceSnapshotRow> byCode = new HashMap<>();
        for (OpeningBalanceSnapshotRow r : snapshots.findAllByOrderByAccountCodeAsc()) {
            byCode.put(r.getAccountCode(), r);
        }

        List<OpeningBalanceRow> rows = new ArrayList<>();
        BigDecimal totalDebit = ZERO, totalCredit = ZERO;
        for (Account a : accounts.findAll()) {
            if (a.isGroup() || !a.isActive()) continue;
            AccountRole role = derived.byAccount().get(a.getId());
            OpeningBalanceSnapshotRow s = byCode.get(a.getCode());
            BigDecimal enteredDebit = s == null ? ZERO : s.getDebit();
            BigDecimal enteredCredit = s == null ? ZERO : s.getCredit();
            // Totals cover what the journal will actually post, so a derived account's
            // PACT figure does not move the difference the accountant is chasing.
            if (role == null) {
                totalDebit = totalDebit.add(enteredDebit);
                totalCredit = totalCredit.add(enteredCredit);
            }
            rows.add(new OpeningBalanceRow(a.getId(), a.getCode(), a.getName(),
                    a.getAccountType() == null ? null : a.getAccountType().name(), a.getPropertyId(),
                    role != null, role, ZERO, ZERO, enteredDebit, enteredCredit));
        }
        rows.sort(Comparator.comparing(OpeningBalanceRow::code, Comparator.nullsLast(String::compareTo)));

        List<String> problems = new ArrayList<>(derived.problems());
        if (resolver.resolveOrNull(AccountRole.OPENING_BALANCE_DIFFERENCE, null) == null) {
            problems.add(noDifferenceAccountMessage());
        }

        Optional<OpeningBalancePosting> live = livePosting();
        JournalEntry e = live.map(p -> journals.findById(p.getJournalId()).orElse(null)).orElse(null);
        return new OpeningBalanceGrid(asOf, e != null, e == null ? null : e.getId(),
                e == null ? null : e.getEntryNumber(), rows, totalDebit, totalCredit,
                totalDebit.subtract(totalCredit), problems);
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
        Set<String> knownCodes = new HashSet<>();
        for (Account a : accounts.findAll()) knownCodes.add(a.getCode());

        snapshots.deleteAllForTenant(requireTenant());

        List<String> problems = new ArrayList<>(parsed.problems());
        List<String> unmatched = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int stored = 0;
        for (TrialBalanceCsvParser.CsvRow r : parsed.rows()) {
            // The snapshot is unique per (tenant, code); a file that repeats one would
            // otherwise come back as a constraint violation with no line number on it.
            if (!seen.add(r.code())) {
                problems.add("line " + r.lineNo() + ": account code " + r.code()
                        + " appears more than once; the later row was ignored");
                continue;
            }
            if (!knownCodes.contains(r.code())) unmatched.add(r.code());
            OpeningBalanceSnapshotRow row = new OpeningBalanceSnapshotRow();
            row.setAccountCode(r.code());
            row.setAccountName(r.name());
            row.setDebit(r.debit());
            row.setCredit(r.credit());
            row.setUploadedAt(Instant.now());
            snapshots.save(row);
            stored++;
        }
        log.info("Opening-balance snapshot stored {} rows, {} unmatched codes, {} problems",
                stored, unmatched.size(), problems.size());
        return new SnapshotUploadResult(stored, unmatched, problems);
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
     */
    @Transactional
    public JournalEntry repost(String reason) {
        LocalDate asOf = asOf();
        OpeningBalancePosting marker = lockMarker(asOf);
        JournalEntry live = liveJournal(marker);
        if (live != null) {
            // PostingService.reverse, not JournalService.reverse: the HTTP-facing one
            // only reverses MANUAL journals and would refuse this by design.
            posting.reverse(live.getId(), asOf,
                    reason == null || reason.isBlank() ? "Opening balances re-posted" : reason);
            marker.setJournalId(null);
        }
        return postFresh(marker, asOf);
    }

    /** Take the opening journal off the books, leaving the grid's figures in place. */
    @Transactional
    public JournalEntry reverse(LocalDate date, String reason) {
        LocalDate asOf = asOf();
        OpeningBalancePosting marker = lockMarker(asOf);
        JournalEntry live = liveJournal(marker);
        if (live == null) {
            throw new BusinessRuleViolationException("There is no posted opening-balance journal to reverse");
        }
        JournalEntry mirror = posting.reverse(live.getId(), date == null ? asOf : date,
                reason == null || reason.isBlank() ? "Opening balances reversed" : reason);
        marker.setJournalId(null);
        marker.setPostedAt(null);
        postings.save(marker);
        log.info("Opening balances reversed: {} mirrored by {}", live.getEntryNumber(), mirror.getEntryNumber());
        return mirror;
    }

    /** Builds and posts the journal. The caller holds the marker's lock and has checked it is free. */
    private JournalEntry postFresh(OpeningBalancePosting marker, LocalDate asOf) {
        // Resolved defensively and BEFORE anything is built: the default-account seed
        // is guarded by count() == 0 and can be left partial (issue #299), and an
        // unmapped role surfacing from inside PostingService would name the role
        // without saying which screen has to fix it.
        Account difference = resolver.resolveOrNull(AccountRole.OPENING_BALANCE_DIFFERENCE, null);
        if (difference == null) throw new BusinessRuleViolationException(noDifferenceAccountMessage());

        Map<UUID, AccountRole> derived = derived().byAccount();
        Map<String, Account> byCode = new LinkedHashMap<>();
        for (Account a : accounts.findAll()) byCode.put(a.getCode(), a);

        List<PostingRequest.Line> lines = new ArrayList<>();
        BigDecimal totalDebit = ZERO, totalCredit = ZERO;
        for (OpeningBalanceSnapshotRow r : snapshots.findAllByOrderByAccountCodeAsc()) {
            Account a = byCode.get(r.getAccountCode());
            // Unknown codes, group accounts and derived accounts are all skipped
            // rather than refused: PACT's trial balance legitimately carries all
            // three, and the reconciliation report is where they are answered for.
            if (a == null || a.isGroup() || !a.isActive() || derived.containsKey(a.getId())) continue;
            if (a.getId().equals(difference.getId())) continue;    // folded into the balancing line below
            if (r.getDebit().signum() > 0) {
                lines.add(PostingRequest.dr(a.getId(), r.getDebit()).withNarration(narration(asOf)));
                totalDebit = totalDebit.add(r.getDebit());
            } else if (r.getCredit().signum() > 0) {
                lines.add(PostingRequest.cr(a.getId(), r.getCredit()).withNarration(narration(asOf)));
                totalCredit = totalCredit.add(r.getCredit());
            }
        }

        BigDecimal gap = totalDebit.subtract(totalCredit);
        if (gap.signum() > 0) {
            lines.add(PostingRequest.cr(difference.getId(), gap).withNarration("Opening balance difference"));
        } else if (gap.signum() < 0) {
            lines.add(PostingRequest.dr(difference.getId(), gap.negate()).withNarration("Opening balance difference"));
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
                entry.getEntryNumber(), lines.size(), gap);
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
     * <p><b>No paging.</b> The row count is bounded by the tenant's own chart of
     * accounts plus the codes in one uploaded file — hundreds, the same order the
     * accounts screen already renders whole. The ledger's {@code MAX_ROWS} exists
     * because its rows scale with transactions; these do not.</p>
     */
    @Transactional(readOnly = true)
    public List<ReconciliationRow> reconcile() {
        LocalDate asOf = asOf();
        Map<UUID, AccountRole> derived = derived().byAccount();

        Map<UUID, BigDecimal> derivedBalances = new HashMap<>();
        Map<UUID, String[]> identity = new HashMap<>();                       // accountId -> {code, name}
        for (var row : ledger.trialBalance(asOf, null)) {
            derivedBalances.merge(row.accountId(), row.balance(), BigDecimal::add);
            identity.put(row.accountId(), new String[]{row.code(), row.name()});
        }
        for (JournalLine l : openingJournalLines()) {
            // trialBalance is debit-positive, so removing this line's contribution
            // means ADDING the negation of (debit − credit). Written out rather than
            // as a subtract so the sign convention is visible at the call site.
            derivedBalances.merge(l.getAccount().getId(),
                    l.getDebit().subtract(l.getCredit()).negate(), BigDecimal::add);
        }

        Map<String, Account> byCode = new HashMap<>();
        for (Account a : accounts.findAll()) {
            byCode.put(a.getCode(), a);
            identity.putIfAbsent(a.getId(), new String[]{a.getCode(), a.getName()});
        }

        List<ReconciliationRow> rows = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (OpeningBalanceSnapshotRow s : snapshots.findAllByOrderByAccountCodeAsc()) {
            Account a = byCode.get(s.getAccountCode());
            BigDecimal pact = s.getDebit().subtract(s.getCredit());
            if (a == null) {
                rows.add(new ReconciliationRow(null, s.getAccountCode(), s.getAccountName(), false,
                        ZERO, pact, ZERO.subtract(pact)));
                continue;
            }
            seen.add(a.getId());
            BigDecimal d = derivedBalances.getOrDefault(a.getId(), ZERO);
            rows.add(new ReconciliationRow(a.getId(), a.getCode(), a.getName(),
                    derived.containsKey(a.getId()), d, pact, d.subtract(pact)));
        }
        for (Map.Entry<UUID, BigDecimal> e : derivedBalances.entrySet()) {
            if (seen.contains(e.getKey()) || e.getValue().signum() == 0) continue;
            String[] id = identity.getOrDefault(e.getKey(), new String[]{null, null});
            rows.add(new ReconciliationRow(e.getKey(), id[0], id[1],
                    derived.containsKey(e.getKey()), e.getValue(), ZERO, e.getValue()));
        }
        rows.sort(Comparator.comparing(ReconciliationRow::code, Comparator.nullsLast(String::compareTo)));
        return rows;
    }

    // ------------------------------------------------------------------
    // internals shared with the reconciliation report
    // ------------------------------------------------------------------

    /**
     * The live opening journal's own lines.
     *
     * <p>The reconciliation report is dated the same day the OB journal is, so a raw
     * trial balance would count the opening entry in the column that is supposed to
     * show what the <em>contract import</em> derived. These are what it subtracts
     * back out; once the journal has been reversed the original and its mirror
     * already net to zero, which is why only a LIVE posting is considered.</p>
     */
    List<JournalLine> openingJournalLines() {
        return livePosting()
                .map(p -> journalLines.findByEntry_IdOrderByLineNoAsc(p.getJournalId()))
                .orElseGet(List::of);
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /** The opening balance is the balance as at the day before the books open (spec §10.3). */
    LocalDate asOf() {
        LocalDate booksStart = fiscal.booksStartDate();
        if (booksStart == null) {
            throw new BusinessRuleViolationException(
                    "Set the books start date in Settings → Fiscal before working on opening balances");
        }
        return booksStart.minusDays(1);
    }

    /** The POSTED opening-balance marker, if the tenant has one that has not been reversed. */
    Optional<OpeningBalancePosting> livePosting() {
        return postings.findFirstByOrderByCreatedAtAsc().filter(p -> liveJournal(p) != null);
    }

    /** The marker's journal, if it is still on the books. */
    private JournalEntry liveJournal(OpeningBalancePosting marker) {
        if (marker == null || marker.getJournalId() == null) return null;
        return journals.findById(marker.getJournalId())
                .filter(e -> e.getStatus() == JournalStatus.POSTED)
                .orElse(null);
    }

    /** accountId → the derived role it is mapped to, across property mappings and tenant defaults. */
    Map<UUID, AccountRole> derivedAccountRoles() {
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
    DerivedRoles derived() {
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
