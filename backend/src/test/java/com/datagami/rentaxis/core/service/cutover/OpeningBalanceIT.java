package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.cutover.GridProblemDTO;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.RowLockedException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.OpeningBalanceSnapshotRow;
import com.datagami.rentaxis.domain.entity.PropertyAccountMapping;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PropertyAccountMappingRepository;
import com.datagami.rentaxis.domain.repository.OpeningBalanceSnapshotRowRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.TenantDefaultAccountMappingRepository;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opening balances: the grid, the PACT trial-balance upload and the {@code OB}
 * journal (spec §10.3).
 *
 * <p><b>The books are locked over the date the OB journal carries</b>, and that is
 * the point rather than a fixture mistake: the opening balance is the balance as at
 * the day <em>before</em> the books open, which is by definition a closed period.
 * {@code PostingService} exempts {@code docType = OB} from the lock for exactly
 * this. {@link #aJournalThatIsNotAnOpeningBalanceCannotBePostedOnTheSameDate} pins
 * that the lock really is on in this fixture, so these pass because of the
 * exemption rather than because nothing was locked.</p>
 *
 * <p><b>The opening-balance difference account is resolved by role</b>, never by
 * code. The seeded chart already carries "Opening Balance Difference" as the leaf
 * {@code F-02} and {@code PropertyAccountService.seedDefaultTemplateAndDefaults}
 * maps the role to it; creating a second one in the fixture would test a chart no
 * tenant has.</p>
 *
 * <p>Read-backs go through {@link #tx}: the journal line's account is lazy, and
 * {@code TenantAspect} only enables the Hibernate tenant filter inside a
 * transaction.</p>
 */
@SpringBootTest
@Testcontainers
class OpeningBalanceIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired OpeningBalanceService ob;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired AccountResolver resolver;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired LedgerQueryService ledger;
    @Autowired JournalEntryRepository entries;
    @Autowired JournalLineRepository lines;
    @Autowired PropertyAccountMappingRepository propertyMappings;
    @Autowired TenantDefaultAccountMappingRepository defaultMappings;
    @Autowired OpeningBalanceSnapshotRowRepository snapshotRows;
    @Autowired TenantFiscalSettingsRepository fiscalRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired TransactionTemplate tx;

    static final LocalDate BOOKS_START = LocalDate.of(2026, 10, 1);
    static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);

    UUID tenantId, propertyId;
    Account cashInHand, vatPayable, obDifference, rentReceivable;

    @BeforeEach
    void setUp() {
        tenantId = newTenant("OB");
        TenantContextHolder.setTenantId(tenantId);
        seedChart();

        Property p = new Property();
        p.setNameEn("Tulip Oasis 7");
        p.setEmirate(Emirate.DUBAI);
        propertyId = propertyRepo.save(p).getId();

        cashInHand = accounts.createLeaf("Cash In Hand", accounts.getAccountByCode("A-02"), null);
        vatPayable = accounts.createLeaf("VAT Payable", accounts.getAccountByCode("B-01"), null);
        rentReceivable = accounts.createLeaf("Rent Receivable - Tulip 7", accounts.getAccountByCode("A-02-01"), propertyId);

        // By role, not by code: the seed already mapped OPENING_BALANCE_DIFFERENCE to F-02.
        obDifference = resolver.resolve(AccountRole.OPENING_BALANCE_DIFFERENCE, null);
        mapProperty(AccountRole.RENT_RECEIVABLE, rentReceivable);

        fiscal.setBooksStartDate(BOOKS_START);
        fiscal.lockThrough(AS_OF);
    }

    @AfterEach void clear() { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private UUID newTenant(String prefix) {
        LandlordOrg org = new LandlordOrg();
        org.setName(prefix + "-" + UUID.randomUUID());
        return orgRepo.save(org).getId();
    }

    private void seedChart() {
        accounts.seedDefaultAccounts();
        propertyAccounts.seedDefaultTemplateAndDefaults();
    }

    private void mapProperty(AccountRole role, Account a) {
        PropertyAccountMapping m = new PropertyAccountMapping();
        m.setPropertyId(propertyId);
        m.setRole(role);
        m.setAccount(a);
        propertyMappings.save(m);
    }

    private OpeningBalanceService.SnapshotUploadResult upload(String csv) {
        return ob.uploadSnapshot(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    /** The usual two-sided trial balance: 50,000 of cash against 12,000 of VAT. */
    private void uploadCashAndVat(String cash, String vat) {
        upload("""
                Account Code,Account Name,Debit,Credit
                %s,Cash In Hand,%s,0.00
                %s,VAT Payable,0.00,%s
                """.formatted(cashInHand.getCode(), cash, vatPayable.getCode(), vat));
    }

    private List<com.datagami.rentaxis.domain.entity.JournalLine> linesOf(UUID entryId) {
        return tx.execute(s -> {
            var out = lines.findByEntry_IdOrderByLineNoAsc(entryId);
            out.forEach(l -> l.getAccount().getId());   // touch the lazy account inside the transaction
            return out;
        });
    }

    private BigDecimal lineOn(UUID entryId, Account account, boolean debitSide) {
        return linesOf(entryId).stream()
                .filter(l -> l.getAccount().getId().equals(account.getId()))
                .map(l -> debitSide ? l.getDebit() : l.getCredit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void assertTrialBalanceBalances() {
        BigDecimal net = ledger.trialBalance(AS_OF, null).stream()
                .map(TrialBalanceRowDTO::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net).as("the trial balance as at the cut-over date").isEqualByComparingTo("0.00");
    }

    private long liveOpeningJournals() {
        return tx.execute(s -> entries.findAll().stream()
                .filter(e -> e.getDocType() == JournalDocType.OB)
                .filter(e -> e.getStatus() == JournalStatus.POSTED)
                .filter(e -> e.getReversalOfId() == null)
                .count());
    }

    /** What the books actually say about one account as at the cut-over date. */
    private BigDecimal balanceAt(Account account) {
        return ledger.trialBalance(AS_OF, null).stream()
                .filter(r -> r.accountId().equals(account.getId()))
                .map(TrialBalanceRowDTO::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ------------------------------------------------------------------
    // the grid
    // ------------------------------------------------------------------

    /**
     * Spec §10.3: accounts mapped to RENT_RECEIVABLE (and the other eight roles the
     * contract import derives) are excluded from manual entry. The grid still SHOWS
     * them so the accountant can see they are accounted for — it just marks them
     * derived, and post() ignores whatever figure a CSV put against them.
     */
    @Test
    void theGridMarksRoleMappedAccountsAsDerived() {
        var grid = ob.grid();
        assertThat(grid.asOf()).isEqualTo(AS_OF);
        assertThat(grid.rows()).filteredOn(r -> r.accountId().equals(rentReceivable.getId()))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.derived()).isTrue();
                    assertThat(r.derivedRole()).isEqualTo(AccountRole.RENT_RECEIVABLE);
                });
        assertThat(grid.rows()).filteredOn(r -> r.accountId().equals(cashInHand.getId()))
                .singleElement()
                .satisfies(r -> assertThat(r.derived()).isFalse());
        assertThat(grid.problems()).isEmpty();
    }

    /** Only leaves can carry a balance, so only leaves are offered. */
    @Test
    void theGridOffersLeavesAndNotGroupAccounts() {
        Account group = accounts.getAccountByCode("A-02");
        assertThat(ob.grid().rows()).noneMatch(r -> r.accountId().equals(group.getId()));
        assertThat(ob.grid().rows()).anyMatch(r -> r.accountId().equals(cashInHand.getId()));
    }

    /**
     * Issue #299: the default account seed is guarded by {@code count() == 0} and can
     * be left partial. A derived role mapped to something that cannot be posted to
     * would otherwise be silently treated as manual — and the contract import would
     * then post the same balance a second time.
     */
    @Test
    void aDerivedRoleMappedToAGroupAccountIsReportedRatherThanSilentlyTreatedAsManual() {
        mapProperty(AccountRole.SECURITY_DEPOSIT, accounts.getAccountByCode("B-01-02"));
        assertThat(ob.grid().problems())
                .anySatisfy(p -> {
                    assertThat(p.message()).contains("SECURITY_DEPOSIT").contains("B-01-02");
                    // Advisory: the grid can still be posted, it just cannot tell
                    // whether that account is derived or manual until somebody re-maps it.
                    assertThat(p.severity()).isEqualTo(GridProblemDTO.Severity.WARNING);
                });
    }

    // ------------------------------------------------------------------
    // the snapshot
    // ------------------------------------------------------------------

    @Test
    void uploadingTheTrialBalanceFillsTheGridAndReportsUnmatchedCodes() {
        var result = upload("""
                Account Code,Account Name,Debit,Credit
                %s,Cash In Hand,50000.00,0.00
                %s,VAT Payable,0.00,12000.00
                999999,Some PACT Account We Do Not Have,0.00,777.00
                """.formatted(cashInHand.getCode(), vatPayable.getCode()));

        assertThat(result.stored()).isEqualTo(3);
        assertThat(result.unmatchedCodes()).containsExactly("999999");
        assertThat(result.problems()).isEmpty();

        var grid = ob.grid();
        assertThat(grid.rows()).filteredOn(r -> r.accountId().equals(cashInHand.getId()))
                .singleElement().satisfies(r -> assertThat(r.enteredDebit()).isEqualByComparingTo("50000.00"));
        assertThat(grid.totalDebit()).isEqualByComparingTo("50000.00");
        assertThat(grid.totalCredit()).isEqualByComparingTo("12000.00");
        assertThat(grid.difference()).isEqualByComparingTo("38000.00");
    }

    /** A re-upload is a correction of the whole file, not an addition to it. */
    @Test
    void reUploadingReplacesTheSnapshotWholesale() {
        uploadCashAndVat("50000.00", "12000.00");
        var second = upload("""
                Account Code,Account Name,Debit,Credit
                %s,Cash In Hand,9000.00,0.00
                """.formatted(cashInHand.getCode()));

        assertThat(second.stored()).isEqualTo(1);
        var grid = ob.grid();
        assertThat(grid.rows()).filteredOn(r -> r.accountId().equals(cashInHand.getId()))
                .singleElement().satisfies(r -> assertThat(r.enteredDebit()).isEqualByComparingTo("9000.00"));
        assertThat(grid.rows()).filteredOn(r -> r.accountId().equals(vatPayable.getId()))
                .singleElement().satisfies(r -> assertThat(r.enteredCredit()).isEqualByComparingTo("0.00"));
    }

    /**
     * A code twice in one file would hit {@code ux_opening_balance_snapshots_tenant_code}
     * and come back as a 500 with a constraint name in it. The accountant gets a line
     * number instead.
     */
    @Test
    void aDuplicateAccountCodeIsReportedWithItsLineRatherThanBreakingTheUpload() {
        var result = upload("""
                Account Code,Account Name,Debit,Credit
                %s,Cash In Hand,50000.00,0.00
                %s,Cash In Hand again,1.00,0.00
                """.formatted(cashInHand.getCode(), cashInHand.getCode()));

        assertThat(result.stored()).isEqualTo(1);
        assertThat(result.problems()).singleElement().asString()
                .contains("line 3").contains(cashInHand.getCode());
        assertThat(ob.grid().rows()).filteredOn(r -> r.accountId().equals(cashInHand.getId()))
                .singleElement().satisfies(r -> assertThat(r.enteredDebit()).isEqualByComparingTo("50000.00"));
    }

    /** A hand-typed figure lands in the same snapshot the CSV fills. */
    @Test
    void aManualFigureIsStoredAndNettedToOneSide() {
        ob.setRow(cashInHand.getId(), new BigDecimal("50000.00"), new BigDecimal("2000.00"));
        assertThat(ob.grid().rows()).filteredOn(r -> r.accountId().equals(cashInHand.getId()))
                .singleElement().satisfies(r -> {
                    assertThat(r.enteredDebit()).isEqualByComparingTo("48000.00");
                    assertThat(r.enteredCredit()).isEqualByComparingTo("0.00");
                });
    }

    /**
     * Spec §10.3 excludes the derived roles from manual entry. A CSV carrying one is
     * ignored (PACT's trial balance legitimately contains them — that is what the
     * reconciliation screen compares against); typing one into the grid is a mistake
     * worth refusing by name.
     */
    @Test
    void aManualFigureAgainstADerivedAccountIsRefusedByName() {
        assertThatThrownBy(() -> ob.setRow(rentReceivable.getId(), new BigDecimal("15000.00"), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining(rentReceivable.getCode())
                .hasMessageContaining("Rent Receivable - Tulip 7");
    }

    // ------------------------------------------------------------------
    // posting
    // ------------------------------------------------------------------

    /**
     * The whole point of the OB journal: whatever the manual figures do not balance by
     * goes to OPENING_BALANCE_DIFFERENCE (equity) so the books open balanced. 50,000 Dr
     * of cash against 12,000 Cr of VAT leaves 38,000 that has to be credited somewhere.
     */
    @Test
    void postingClosesTheDifferenceAgainstEquityAndTheTrialBalanceBalances() {
        uploadCashAndVat("50000.00", "12000.00");

        JournalEntry e = ob.post();

        assertThat(e.getDocType()).isEqualTo(JournalDocType.OB);
        assertThat(e.getEntryDate()).isEqualTo(AS_OF);
        assertThat(e.getSourceType()).isEqualTo(JournalSourceType.OPENING_BALANCE);
        assertThat(lineOn(e.getId(), obDifference, false)).isEqualByComparingTo("38000.00");
        assertThat(ob.grid().posted()).isTrue();
        assertThat(ob.grid().journalNumber()).isEqualTo(e.getEntryNumber());
        assertTrialBalanceBalances();
    }

    /** The mirror case — more credits than debits — debits the difference account. */
    @Test
    void anExcessOfCreditsDebitsTheDifferenceAccount() {
        uploadCashAndVat("5000.00", "12000.00");
        JournalEntry e = ob.post();
        assertThat(lineOn(e.getId(), obDifference, true)).isEqualByComparingTo("7000.00");
        assertTrialBalanceBalances();
    }

    /** A figure typed against a derived account is ignored, not posted twice. */
    @Test
    void aFigureAgainstADerivedAccountIsNotPosted() {
        upload("""
                Account Code,Account Name,Debit,Credit
                %s,Cash In Hand,50000.00,0.00
                %s,Rent Receivable - Tulip 7,15000.00,0.00
                %s,VAT Payable,0.00,12000.00
                """.formatted(cashInHand.getCode(), rentReceivable.getCode(), vatPayable.getCode()));
        JournalEntry e = ob.post();
        assertThat(linesOf(e.getId())).noneMatch(l -> l.getAccount().getId().equals(rentReceivable.getId()));
        assertThat(lineOn(e.getId(), obDifference, false)).isEqualByComparingTo("38000.00");
    }

    @Test
    void postingTwiceIsBlockedUntilTheFirstOneIsReversed() {
        uploadCashAndVat("50000.00", "12000.00");
        ob.post();
        assertThatThrownBy(ob::post)
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already");

        ob.reverse("corrected trial balance");
        JournalEntry again = ob.post();
        assertThat(again).isNotNull();
        assertThat(ob.grid().posted()).isTrue();
        assertThat(liveOpeningJournals()).isEqualTo(1);
        assertTrialBalanceBalances();
    }

    /**
     * The books were reversed rather than corrected: nothing of the opening entry is
     * left on them, and the grid says so.
     */
    @Test
    void reversingTheOpeningJournalTakesItOffTheBooks() {
        uploadCashAndVat("50000.00", "12000.00");
        ob.post();
        ob.reverse("wrong file");

        assertThat(ob.grid().posted()).isFalse();
        assertThat(ob.grid().journalId()).isNull();
        assertThat(liveOpeningJournals()).isZero();
        assertTrialBalanceBalances();
    }

    @Test
    void thereIsNothingToReverseBeforeAnythingIsPosted() {
        assertThatThrownBy(() -> ob.reverse("nope"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("no posted opening-balance journal");
    }

    /**
     * Re-posting a corrected trial balance is one act: the live opening journal comes
     * off and the new one goes on inside a single transaction, so the books are never
     * between two opening balances.
     */
    @Test
    void repostingReplacesTheOpeningJournalInOneAct() {
        uploadCashAndVat("50000.00", "12000.00");
        JournalEntry first = ob.post();

        uploadCashAndVat("60000.00", "12000.00");
        JournalEntry second = ob.repost("corrected trial balance");

        assertThat(second.getId()).isNotEqualTo(first.getId());
        JournalStatus firstStatus = tx.execute(s -> entries.findById(first.getId()).orElseThrow().getStatus());
        assertThat(firstStatus).isEqualTo(JournalStatus.REVERSED);
        assertThat(lineOn(second.getId(), obDifference, false)).isEqualByComparingTo("48000.00");
        assertThat(liveOpeningJournals()).isEqualTo(1);
        assertThat(ob.grid().journalId()).isEqualTo(second.getId());
        assertTrialBalanceBalances();
    }

    @Test
    void postingWithNoFiguresIsRejected() {
        assertThatThrownBy(ob::post)
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("at least two");
    }

    @Test
    void postingWithoutABooksStartDateIsRejected() {
        fiscal.setBooksStartDate(null);
        assertThatThrownBy(ob::post)
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("books start date");
    }

    /**
     * Issue #299 again, on the one role the posting cannot do without. Unmapped, the
     * balancing line would come back as an unmapped-role error from deep inside
     * PostingService with the snapshot half-walked; the accountant is told which role
     * to map before anything is written.
     */
    @Test
    void postingWithNoOpeningBalanceDifferenceMappingNamesTheRole() {
        unmapOpeningBalanceDifference();
        uploadCashAndVat("50000.00", "12000.00");
        assertThatThrownBy(ob::post)
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("OPENING_BALANCE_DIFFERENCE");
        assertThat(liveOpeningJournals()).isZero();
    }

    /**
     * The grid warns about the same missing mapping before the accountant presses
     * Post — and says it is the kind of problem that will refuse the post, not one of
     * the advisories beside it (ruling R26). The test above is the refusal itself, so
     * the two are one statement made twice.
     */
    @Test
    void theGridWarnsWhenTheDifferenceAccountIsNotMapped() {
        unmapOpeningBalanceDifference();
        assertThat(ob.grid().problems())
                .anySatisfy(p -> {
                    assertThat(p.message()).contains("OPENING_BALANCE_DIFFERENCE");
                    assertThat(p.severity()).isEqualTo(GridProblemDTO.Severity.ERROR);
                });
    }

    /** Stands in for a tenant whose default-account seed ran before F-02 existed. */
    private void unmapOpeningBalanceDifference() {
        tx.executeWithoutResult(s -> defaultMappings.findAllByOrderByRoleAsc().stream()
                .filter(m -> m.getRole() == AccountRole.OPENING_BALANCE_DIFFERENCE)
                .forEach(defaultMappings::delete));
    }

    /**
     * The fixture's period lock is real: an ordinary journal on the same date is
     * refused, so the OB journal above posts because {@code docType = OB} is exempt.
     */
    @Test
    void aJournalThatIsNotAnOpeningBalanceCannotBePostedOnTheSameDate() {
        assertThatThrownBy(() -> fiscal.assertOpen(AS_OF))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("locked through");
    }

    /**
     * Two clerks (or one double-click) opening the books must open them once.
     *
     * <p>On a <em>first</em> post it is the conflict-safe marker insert that decides
     * it — the loser blocks on {@code ux_opening_balance_postings_tenant} until the
     * winner commits and then reads a live journal. That is deliberate, and it is why
     * {@link #twoSimultaneousPostsOverAnExistingMarkerLeaveOneOpeningJournal} exists:
     * once the marker is there the insert is a no-op and the row lock is the only
     * thing left holding the line.</p>
     */
    @Test
    void twoSimultaneousPostsLeaveOneOpeningJournal() throws Exception {
        uploadCashAndVat("50000.00", "12000.00");
        assertOnePostWinsARace();
    }

    /**
     * The same race with the marker already on the table — the books were opened and
     * reversed once. Nothing blocks on the unique index any more, so this is the case
     * the pessimistic lock in {@code lockMarker} is actually for.
     */
    @Test
    void twoSimultaneousPostsOverAnExistingMarkerLeaveOneOpeningJournal() throws Exception {
        uploadCashAndVat("50000.00", "12000.00");
        ob.post();
        ob.reverse("start again");
        assertOnePostWinsARace();
    }

    private void assertOnePostWinsARace() throws Exception {
        UUID tenant = tenantId;

        CyclicBarrier bothReady = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<Object>> racers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            racers.add(() -> {
                TenantContextHolder.setTenantId(tenant);
                try {
                    bothReady.await(10, TimeUnit.SECONDS);
                    return ob.post();
                } catch (RuntimeException ex) {
                    return ex;
                } finally {
                    TenantContextHolder.clear();
                }
            });
        }
        List<Object> outcomes = new ArrayList<>();
        try {
            for (Future<Object> f : pool.invokeAll(racers, 60, TimeUnit.SECONDS)) outcomes.add(f.get());
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes).filteredOn(JournalEntry.class::isInstance).as("winners").hasSize(1);
        // Tightened: RowLockedException is a BusinessRuleViolationException too, and
        // "the row was locked, try again" is a different outcome from "already posted".
        // The lock is held for the length of the transaction rather than NOWAIT, so the
        // loser blocks and then reads the winner's journal — it must never time out.
        assertThat(outcomes).filteredOn(o -> !(o instanceof JournalEntry)).as("losers")
                .allSatisfy(o -> assertThat(o)
                        .isInstanceOf(BusinessRuleViolationException.class)
                        .isNotInstanceOf(RowLockedException.class)
                        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.THROWABLE)
                        .hasMessageContaining("already"));
        assertThat(liveOpeningJournals()).isEqualTo(1);
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // fix round 1 — C1: the reversal's date
    // ------------------------------------------------------------------

    /**
     * C1. The opening journal is reversed on its <em>own</em> date, never on one a
     * caller or a later settings change supplies.
     *
     * <p>A mirror dated anywhere else leaves the whole opening balance standing as at
     * D − 1 — {@code balancesAsOf} has no status predicate, so a REVERSED entry still
     * counts — while the marker says "not posted". The next Post then writes a second
     * OB journal on the same day and the books carry the opening balances twice, still
     * balanced, so no invariant in this suite would notice.
     */
    @Test
    void reversingIsDatedOnTheOpeningJournalsOwnDateSoTheNextPostDoesNotDoubleTheBalances() {
        uploadCashAndVat("50000.00", "12000.00");
        JournalEntry first = ob.post();
        assertThat(balanceAt(cashInHand)).isEqualByComparingTo("50000.00");

        JournalEntry mirror = ob.reverse("corrected trial balance");

        assertThat(mirror.getEntryDate()).as("the mirror belongs where the entry is")
                .isEqualTo(first.getEntryDate());
        assertThat(balanceAt(cashInHand)).as("nothing of the opening balance is left at D-1")
                .isEqualByComparingTo("0.00");
        assertThat(balanceAt(vatPayable)).isEqualByComparingTo("0.00");
        assertThat(balanceAt(obDifference)).isEqualByComparingTo("0.00");

        ob.post();
        assertThat(balanceAt(cashInHand)).as("posted once, not twice").isEqualByComparingTo("50000.00");
        assertThat(liveOpeningJournals()).isEqualTo(1);
        assertTrialBalanceBalances();
    }

    /** The same pin on the replace path: the reversal half of a repost is dated on the original. */
    @Test
    void aRepostReversesOnTheOriginalsOwnDate() {
        uploadCashAndVat("50000.00", "12000.00");
        JournalEntry first = ob.post();
        uploadCashAndVat("60000.00", "12000.00");
        ob.repost("corrected");

        JournalEntry mirror = tx.execute(s -> entries.findAll().stream()
                .filter(e -> first.getId().equals(e.getReversalOfId()))
                .findFirst().orElseThrow());
        assertThat(mirror.getEntryDate()).isEqualTo(first.getEntryDate());
        assertThat(balanceAt(cashInHand)).isEqualByComparingTo("60000.00");
        assertTrialBalanceBalances();
    }

    /**
     * The pin itself, with the two dates forced apart.
     *
     * <p>{@link #theBooksStartDateCannotMoveWhileTheOpeningBalancesArePosted} closes
     * the supported path, so in ordinary use {@code asOf()} and the entry's own date
     * are the same day and nothing can tell which one the reversal used. This moves
     * the field <em>around</em> that guard — the way a restore, a migration or a
     * future writer would — and pins that the mirror still lands on the entry, not on
     * the recomputed cut-over date.</p>
     */
    @Test
    void aReversalIsDatedOnTheEntryEvenIfTheBooksStartDateMovedBehindOurBack() {
        uploadCashAndVat("50000.00", "12000.00");
        JournalEntry first = ob.post();

        tx.executeWithoutResult(s -> {
            TenantFiscalSettings fs = fiscalRepo.findById(tenantId).orElseThrow();
            fs.setBooksStartDate(LocalDate.of(2026, 11, 1));      // not through the guarded setter
            fiscalRepo.save(fs);
        });

        JournalEntry mirror = ob.reverse("corrected");
        assertThat(mirror.getEntryDate())
                .as("the mirror follows the entry, not the recomputed cut-over date")
                .isEqualTo(first.getEntryDate())
                .isEqualTo(AS_OF);
        assertThat(balanceAt(cashInHand)).isEqualByComparingTo("0.00");
    }

    /**
     * C1, the other root. If the books start date moves after the opening balances are
     * posted, {@code asOf()} returns a different day from the entry's own date and the
     * entry and its mirror drift apart on their own, with no caller input at all.
     */
    @Test
    void theBooksStartDateCannotMoveWhileTheOpeningBalancesArePosted() {
        uploadCashAndVat("50000.00", "12000.00");
        ob.post();

        assertThatThrownBy(() -> fiscal.setBooksStartDate(LocalDate.of(2026, 11, 1)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Reverse or replace the opening balances");

        // Setting it to the value it already has is not a change, so it is allowed.
        fiscal.setBooksStartDate(BOOKS_START);

        ob.reverse("done with them");
        fiscal.setBooksStartDate(LocalDate.of(2026, 11, 1));
        assertThat(ob.grid().asOf()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    // ------------------------------------------------------------------
    // fix round 1 — I1 and the difference account
    // ------------------------------------------------------------------

    /**
     * I1. The number the accountant presses Post from must be the number posted. A PACT
     * file carrying a figure on the difference account itself is the case where the two
     * used to disagree: the grid counted it into the totals, the posting skipped it.
     */
    @Test
    void theGridsDifferenceIsExactlyWhatIsPostedToTheDifferenceAccount() {
        upload("""
                Account Code,Account Name,Debit,Credit
                %s,Cash In Hand,50000.00,0.00
                %s,VAT Payable,0.00,12000.00
                %s,Opening Balance Difference,0.00,5000.00
                """.formatted(cashInHand.getCode(), vatPayable.getCode(), obDifference.getCode()));

        var grid = ob.grid();
        assertThat(grid.difference()).isEqualByComparingTo("38000.00");
        assertThat(grid.problems())
                .anySatisfy(p -> {
                    assertThat(p.message()).contains(obDifference.getCode()).contains("recomputed");
                    assertThat(p.severity()).isEqualTo(GridProblemDTO.Severity.WARNING);
                });

        JournalEntry e = ob.post();
        assertThat(lineOn(e.getId(), obDifference, false))
                .as("the posted balancing figure equals the grid's difference")
                .isEqualByComparingTo(grid.difference());
        assertTrialBalanceBalances();
    }

    /** The web renders that row read-only; the API has to agree rather than discard the figure silently. */
    @Test
    void aManualFigureAgainstTheDifferenceAccountIsRefusedByName() {
        assertThatThrownBy(() -> ob.setRow(obDifference.getId(), null, new BigDecimal("5000.00")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining(obDifference.getCode())
                .hasMessageContaining("recomputed");
    }

    @Test
    void theDifferenceAccountRowIsMarkedComputed() {
        assertThat(ob.grid().rows()).filteredOn(r -> r.accountId().equals(obDifference.getId()))
                .singleElement().satisfies(r -> {
                    assertThat(r.computed()).isTrue();
                    assertThat(r.derived()).isFalse();
                });
        assertThat(ob.grid().rows()).filteredOn(r -> r.accountId().equals(cashInHand.getId()))
                .singleElement().satisfies(r -> assertThat(r.computed()).isFalse());
    }

    // ------------------------------------------------------------------
    // fix round 1 — I3: matching by code, then by name
    // ------------------------------------------------------------------

    /**
     * I3. A tenant whose chart is not keyed by PACT's six-digit codes would otherwise
     * see every uploaded row unmatched: the code lookup misses and nothing else was
     * ever tried. The name is the fallback the spec's property-mapping sheet uses.
     */
    @Test
    void aRowWhoseCodeIsUnknownIsMatchedByItsAccountName() {
        var result = upload("""
                Account Code,Account Name,Debit,Credit
                999999,  cash in hand  ,50000.00,0.00
                888888,VAT Payable,0.00,12000.00
                """);
        assertThat(result.unmatchedCodes()).isEmpty();
        assertThat(result.problems()).isEmpty();

        assertThat(ob.grid().rows()).filteredOn(r -> r.accountId().equals(cashInHand.getId()))
                .singleElement().satisfies(r -> assertThat(r.enteredDebit()).isEqualByComparingTo("50000.00"));

        JournalEntry e = ob.post();
        assertThat(lineOn(e.getId(), cashInHand, true)).isEqualByComparingTo("50000.00");
    }

    /** Two accounts with the same name is a guess we will not make. */
    @Test
    void aNameMatchingMoreThanOneAccountIsReportedAsAmbiguous() {
        accounts.createLeaf("Cash In Hand", accounts.getAccountByCode("A-02-05"), null);
        var result = upload("""
                Account Code,Account Name,Debit,Credit
                999999,Cash In Hand,50000.00,0.00
                """);
        assertThat(result.problems()).singleElement().asString()
                .contains("line 2").contains("ambiguous").contains("Cash In Hand");
        assertThat(result.unmatchedCodes()).containsExactly("999999");
    }

    @Test
    void aRowMatchingNeitherCodeNorNameIsUnmatched() {
        var result = upload("""
                Account Code,Account Name,Debit,Credit
                999999,Nothing We Have,0.00,777.00
                """);
        assertThat(result.unmatchedCodes()).containsExactly("999999");
        assertThat(result.problems()).isEmpty();
    }

    // ------------------------------------------------------------------
    // fix round 1 — the web's additive fields
    // ------------------------------------------------------------------

    @Test
    void theUploadResultCarriesTheFilesTotalsAndWhetherItBalances() {
        var unbalanced = upload("""
                Account Code,Account Name,Debit,Credit
                %s,Cash In Hand,50000.00,0.00
                %s,VAT Payable,0.00,12000.00
                """.formatted(cashInHand.getCode(), vatPayable.getCode()));
        assertThat(unbalanced.totalDebit()).isEqualByComparingTo("50000.00");
        assertThat(unbalanced.totalCredit()).isEqualByComparingTo("12000.00");
        assertThat(unbalanced.balanced()).isFalse();
        assertThat(unbalanced.stored()).isEqualTo(2);

        var balanced = upload("""
                Account Code,Account Name,Debit,Credit
                %s,Cash In Hand,12000.00,0.00
                %s,VAT Payable,0.00,12000.00
                """.formatted(cashInHand.getCode(), vatPayable.getCode()));
        assertThat(balanced.balanced()).isTrue();
    }

    /**
     * The snapshot stays editable after posting — that is the Replace workflow — but
     * nothing used to tell the accountant that the grid and the live journal had
     * drifted apart.
     */
    @Test
    void theGridSaysWhenTheSnapshotHasChangedSinceTheJournalWasPosted() {
        uploadCashAndVat("50000.00", "12000.00");
        assertThat(ob.grid().changedSincePosted()).as("nothing posted yet").isFalse();

        ob.post();
        assertThat(ob.grid().changedSincePosted()).isFalse();

        ob.setRow(cashInHand.getId(), new BigDecimal("60000.00"), null);
        assertThat(ob.grid().changedSincePosted()).isTrue();

        ob.repost("corrected");
        assertThat(ob.grid().changedSincePosted()).isFalse();
    }

    /** Who typed a figure is a question changeset 88 left a column for. */
    @Test
    void theSnapshotRecordsWhoUploadedAndWhoTyped() {
        UUID user = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.toString(), null, List.of()));
        try {
            uploadCashAndVat("50000.00", "12000.00");
            ob.setRow(cashInHand.getId(), new BigDecimal("60000.00"), null);
            List<UUID> who = tx.execute(s -> snapshotRows.findAllByOrderByAccountCodeAsc().stream()
                    .map(OpeningBalanceSnapshotRow::getUploadedBy).toList());
            assertThat(who).isNotEmpty().allMatch(user::equals);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ------------------------------------------------------------------
    // fix round 1 — I4: the published helpers need a transaction
    // ------------------------------------------------------------------

    /**
     * I4. These four are the integration surface Tasks 10–11 were handed. Called with
     * no transaction of their own, {@code TenantAspect} enables the Hibernate tenant
     * filter on one session while the query runs on another — an unfiltered,
     * cross-tenant read. They refuse loudly instead.
     */
    @Test
    void thePublishedHelpersRefuseToRunWithoutATransaction() {
        assertThatThrownBy(() -> ob.asOf()).hasMessageContaining("transaction");
        assertThatThrownBy(() -> ob.livePosting()).hasMessageContaining("transaction");
        assertThatThrownBy(() -> ob.derived()).hasMessageContaining("transaction");
        assertThatThrownBy(() -> ob.openingJournalLines()).hasMessageContaining("transaction");

        // …and work normally inside one.
        LocalDate inATransaction = tx.execute(s -> ob.asOf());
        assertThat(inATransaction).isEqualTo(AS_OF);
    }

    /** Minor 6: a repost that fails leaves the books exactly as they were. */
    @Test
    void aFailedRepostLeavesTheOriginalOpeningJournalLive() {
        uploadCashAndVat("50000.00", "12000.00");
        JournalEntry original = ob.post();

        // An empty snapshot cannot produce two lines, so postFresh refuses — after the
        // reversal half of the repost has already run inside the same transaction.
        upload("Account Code,Account Name,Debit,Credit\n");
        assertThatThrownBy(() -> ob.repost("corrected"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("at least two");

        JournalStatus status = tx.execute(s -> entries.findById(original.getId()).orElseThrow().getStatus());
        assertThat(status).isEqualTo(JournalStatus.POSTED);
        assertThat(ob.grid().posted()).isTrue();
        assertThat(ob.grid().journalId()).isEqualTo(original.getId());
        assertThat(balanceAt(cashInHand)).isEqualByComparingTo("50000.00");
    }

    // ------------------------------------------------------------------
    // tenancy
    // ------------------------------------------------------------------

    /**
     * Another organisation's opening balances are not this one's business: not its
     * snapshot, not its marker, and not its accounts. Tenant B's own upload must also
     * leave A's rows alone — the snapshot is cleared with a bulk delete, which
     * Hibernate does not apply the tenant filter to.
     */
    @Test
    void tenantBCannotSeeOrClearTenantAsSnapshotOrOpenItsBooks() {
        uploadCashAndVat("50000.00", "12000.00");
        ob.post();
        UUID aCashAccount = cashInHand.getId();
        Account aRentReceivable = rentReceivable;

        UUID other = newTenant("OB-B");
        TenantContextHolder.setTenantId(other);
        try {
            seedChart();
            fiscal.setBooksStartDate(BOOKS_START);

            assertThat(ob.grid().posted()).as("B must not see A's opening journal").isFalse();
            assertThat(ob.grid().rows()).noneMatch(r -> r.accountId().equals(aCashAccount));

            assertThatThrownBy(() -> ob.setRow(aCashAccount, new BigDecimal("1.00"), null))
                    .isInstanceOf(NotFoundException.class);

            // B's own upload clears B's snapshot only.
            ob.uploadSnapshot(new ByteArrayInputStream("code,name,debit,credit\n".getBytes(StandardCharsets.UTF_8)));
        } finally {
            TenantContextHolder.setTenantId(tenantId);
        }

        assertThat(ob.grid().rows()).filteredOn(r -> r.accountId().equals(aCashAccount))
                .singleElement().satisfies(r -> assertThat(r.enteredDebit()).isEqualByComparingTo("50000.00"));
        assertThat(ob.grid().posted()).isTrue();
        assertThat(aRentReceivable.getId()).isNotNull();
    }
}
