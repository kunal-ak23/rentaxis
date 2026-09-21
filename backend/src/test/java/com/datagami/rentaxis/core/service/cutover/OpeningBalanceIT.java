package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
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
import com.datagami.rentaxis.domain.entity.PropertyAccountMapping;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PropertyAccountMappingRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.TenantDefaultAccountMappingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

    @AfterEach void clear() { TenantContextHolder.clear(); }

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
                .anySatisfy(p -> assertThat(p).contains("SECURITY_DEPOSIT").contains("B-01-02"));
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

        ob.reverse(AS_OF, "corrected trial balance");
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
        ob.reverse(AS_OF, "wrong file");

        assertThat(ob.grid().posted()).isFalse();
        assertThat(ob.grid().journalId()).isNull();
        assertThat(liveOpeningJournals()).isZero();
        assertTrialBalanceBalances();
    }

    @Test
    void thereIsNothingToReverseBeforeAnythingIsPosted() {
        assertThatThrownBy(() -> ob.reverse(AS_OF, "nope"))
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

    /** The grid warns about the same missing mapping before the accountant presses Post. */
    @Test
    void theGridWarnsWhenTheDifferenceAccountIsNotMapped() {
        unmapOpeningBalanceDifference();
        assertThat(ob.grid().problems())
                .anySatisfy(p -> assertThat(p).contains("OPENING_BALANCE_DIFFERENCE"));
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
        ob.reverse(AS_OF, "start again");
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
        assertThat(outcomes).filteredOn(o -> !(o instanceof JournalEntry)).as("losers")
                .allMatch(BusinessRuleViolationException.class::isInstance);
        assertThat(liveOpeningJournals()).isEqualTo(1);
        assertTrialBalanceBalances();
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
