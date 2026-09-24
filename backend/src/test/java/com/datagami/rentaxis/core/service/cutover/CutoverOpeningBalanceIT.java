package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.cutover.OpeningBalanceService.ReconciliationRow;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;
import com.datagami.rentaxis.domain.repository.TenantDefaultAccountMappingRepository;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cut-over composed: import → bulk post → upload PACT's trial balance → open the
 * books. Review C2, ruling R17.
 *
 * <p><b>The gap this class exists to close.</b> Nothing in the suite used to run
 * both halves of the cut-over against one set of books — {@code OpeningBalanceIT}
 * never calls the bulk post, {@code CutoverReconciliationIT} never posts the opening
 * balances — so the one arithmetic that matters on the client's first day was
 * untested. It was also wrong: the opening journal posted PACT's figure <em>gross</em>
 * on every account outside the (then nine) {@link OpeningBalanceService#DERIVED_ROLES}, and
 * step 1 writes to two accounts that are not in that set and that the accountant does
 * type from PACT's file — the <b>bank</b> a cleared cheque reaches ({@code CRT}) and
 * <b>output VAT</b> a VAT-bearing contract raises ({@code TCO}). Both came out
 * counted twice, with the double count parked on the equity difference line: balanced
 * books, and millions of dirhams of phantom bank on a six-hundred-contract portfolio.
 * {@code CutoverReconciliationIT} could not see it because its PACT file says the bank
 * holds exactly the one cleared cheque; a real PACT bank balance holds everything the
 * landlord ever banked.</p>
 *
 * <h2>The figures, derived by hand</h2>
 *
 * <p>The portfolio is the shipped template's two sample contracts — the same two
 * {@code CutoverReconciliationIT} writes out in full. What the bulk post leaves on the
 * books at 30 Sep 2026 ({@code ours}, debit-positive):</p>
 *
 * <pre>
 *   PDC Receivable ST1      +47,050.00   derived (PDC_RECEIVABLE)
 *   Sample Bank - ST1       +31,000.00   NOT derived — the cheque that cleared on the 25th
 *   Advance Rent - ST1      -71,021.92   derived (ADVANCE_RENT)
 *   Rental Income ST1          -978.08   derived (RENTAL_INCOME)
 *   Security Deposit ST1     -5,000.00   derived (SECURITY_DEPOSIT)
 *   Output VAT on Sales      -1,050.00   NOT derived — SAMPLE-0002's VAT
 *   Rent Receivable - ST1         0.00   derived (RENT_RECEIVABLE)
 * </pre>
 *
 * <p>Output VAT is SAMPLE-0002's whole 1,050 because a cut-over contract is posted on
 * the CONTRACT VAT model (spec 2026-09-24 §1, cut-over ruling): PACT declared it on
 * the contract date, so the {@code TCO} credits Output VAT itself and nothing waits in
 * Output VAT – not yet due. {@link
 * #aLeasePostedHereBeforeTheCutOverKeepsItsDeferredVatAndPactsOutputVatCoversIt}
 * covers the other case.</p>
 *
 * <p>PACT's trial balance ({@code P}) is a real one: a bank balance of 250,000 that is
 * not just the cleared cheque, 3,000 of output VAT of which only 1,050 came from these
 * contracts, a capital account making the file balance — and a PDC figure 1,000 higher
 * than ours, which is the one genuine disagreement.</p>
 *
 * <p>The opening journal therefore posts {@code P − ours} on the non-derived accounts
 * and nothing at all on the derived ones:</p>
 *
 * <pre>
 *   Sample Bank - ST1     Dr 219,000.00   = 250,000.00 - 31,000.00
 *   Output VAT on Sales   Cr   1,950.00   =   3,000.00 -  1,050.00
 *   Capital Account       Cr 218,050.00   = 218,050.00 -      0.00
 *   ------------------------------------------------------------------
 *   Opening Balance Diff  Dr   1,000.00   the balancing gap
 * </pre>
 *
 * <p>and the books at D − 1 then read {@code P} on every non-derived account,
 * {@code ours} on every derived one, and {@code Σ_derived (P − ours)} = 1,000.00 on
 * the difference account — the true unreconciled gap, which the last test below
 * asserts as an identity rather than as a constant.</p>
 */
@SpringBootTest
class CutoverOpeningBalanceIT extends AbstractPostgresIT {

    @Autowired CutoverFixture fixture;
    @Autowired ContractImportPersistService contractPersist;
    @Autowired ContractImportPostService postService;
    @Autowired OpeningBalanceService openingBalances;
    @Autowired ImportBatchService batches;
    @Autowired AccountService accounts;
    @Autowired AccountResolver resolver;
    @Autowired LedgerQueryService ledger;
    @Autowired TenantDefaultAccountMappingRepository defaultMappings;
    @Autowired TransactionTemplate tx;
    @Autowired PostingService postingService;
    @Autowired TenantFiscalSettingsRepository fiscalSettings;
    @Autowired TenantFiscalSettingsService fiscal;

    UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = fixture.newCutOverTenant("OBCOMP");
        fixture.authenticateAsTenantAdmin();
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
        fixture.clearAuthentication();
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private UUID importTheTemplate() throws Exception {
        try (Workbook wb = fixture.template()) {
            return contractPersist.persist(wb, fixture.newJob()).batchId();
        }
    }

    private UUID importAndPost() throws Exception {
        UUID batchId = importTheTemplate();
        assertThat(postService.post(batchId).leasesFailed()).isZero();
        return batchId;
    }

    /** What the books hold for one account as at D − 1, debit-positive. By name, never by position. */
    private BigDecimal balanceOf(String accountName) {
        return tx.execute(s -> ledger.trialBalance(CutoverFixture.AS_OF, null).stream()
                .filter(r -> accountName.equals(r.name()))
                .map(TrialBalanceRowDTO::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private String codeOf(String accountName) {
        return tx.execute(s -> accounts.getAllAccounts().stream()
                .filter(a -> accountName.equals(a.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("No account named " + accountName))
                .getCode());
    }

    /**
     * The leaf the tenant's default OUTPUT_VAT mapping points at — resolved through
     * the mapping rather than by a hard-coded name, so a test that moved the mapping
     * could not still pass against the old leaf.
     */
    private String outputVatName() {
        return tx.execute(s -> accounts.getAccountById(
                defaultMappings.findAll().stream()
                        .filter(m -> m.getRole() == AccountRole.OUTPUT_VAT)
                        .findFirst().orElseThrow(() -> new AssertionError("OUTPUT_VAT is not mapped"))
                        .getAccount().getId()).getName());
    }

    /** The leaf the tenant's default OUTPUT_VAT_DEFERRED mapping points at. */
    private String deferredVatName() {
        return tx.execute(s -> defaultMappings.findAll().stream()
                .filter(m -> m.getRole() == AccountRole.OUTPUT_VAT_DEFERRED)
                .findFirst().orElseThrow(() -> new AssertionError("OUTPUT_VAT_DEFERRED is not mapped"))
                .getAccount().getName());
    }

    /**
     * A lease posted here, on the INSTALMENT model, before the cut-over date — stood
     * in for by the one pair of its {@code TCO} that matters to the opening balance:
     * {@code Dr rent receivable / Cr Output VAT – not yet due}. It was posted before
     * the books were locked, so the lock is lifted for it and put back.
     */
    private void postDeferredVatBeforeTheCutOver(String amount) {
        tx.executeWithoutResult(s -> {
            TenantFiscalSettings settings = fiscalSettings.findById(tenantId).orElseThrow();
            settings.setBooksLockedThrough(null);
            fiscalSettings.saveAndFlush(settings);
        });
        tx.executeWithoutResult(s -> {
            UUID receivable = accounts.getAllAccounts().stream()
                    .filter(a -> "Rent Receivable - ST1".equals(a.getName()))
                    .findFirst().orElseThrow().getId();
            BigDecimal value = new BigDecimal(amount);
            String narration = "VAT on a lease posted before the cut-over";
            postingService.post(PostingRequest.ofPairs(
                    JournalDocType.TCO, LocalDate.of(2026, 9, 10), narration, PostingRequest.Dimensions.none(),
                    JournalSourceType.LEASE, UUID.randomUUID(), null,
                    List.of(PostingRequest.pair(
                            PostingRequest.dr(receivable, value).withNarration(narration),
                            PostingRequest.cr(AccountRole.OUTPUT_VAT_DEFERRED, value).withNarration(narration)))));
        });
        fiscal.lockThrough(CutoverFixture.AS_OF);
    }

    private String differenceAccountName() {
        return tx.execute(s -> resolver.resolve(AccountRole.OPENING_BALANCE_DIFFERENCE, null).getName());
    }

    /** PACT's trial balance, keyed by our own codes so the match is by code. See the class Javadoc. */
    private void uploadPactTrialBalance() {
        String csv = """
                Account Code,Account Name,Debit,Credit
                %s,PDC Receivable ST1,48050.00,0
                %s,Sample Bank - ST1,250000.00,0
                %s,Advance Rent - ST1,0,71021.92
                %s,Rental Income ST1,0,978.08
                %s,Security Deposit ST1,0,5000.00
                %s,Output VAT,0,3000.00
                %s,Capital Account,0,218050.00
                """.formatted(
                codeOf("PDC Receivable ST1"), codeOf("Sample Bank - ST1"), codeOf("Advance Rent - ST1"),
                codeOf("Rental Income ST1"), codeOf("Security Deposit ST1"), codeOf(outputVatName()),
                codeOf("Capital Account"));
        openingBalances.uploadSnapshot(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * PACT's trial balance with the Output VAT row dropped — the account is one
     * PACT's file never names at all, rather than one it names at 0. See
     * {@link #anAccountPactNeverNamesIsZeroedOutRatherThanLeftCarryingWhatStep1Posted}.
     */
    private void uploadPactTrialBalanceWithoutOutputVat() {
        String csv = """
                Account Code,Account Name,Debit,Credit
                %s,PDC Receivable ST1,48050.00,0
                %s,Sample Bank - ST1,250000.00,0
                %s,Advance Rent - ST1,0,71021.92
                %s,Rental Income ST1,0,978.08
                %s,Security Deposit ST1,0,5000.00
                %s,Capital Account,0,218050.00
                """.formatted(
                codeOf("PDC Receivable ST1"), codeOf("Sample Bank - ST1"), codeOf("Advance Rent - ST1"),
                codeOf("Rental Income ST1"), codeOf("Security Deposit ST1"), codeOf("Capital Account"));
        openingBalances.uploadSnapshot(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------

    /**
     * The whole cut-over, in the order spec §10.3 puts it in, and the books at D − 1
     * afterwards. Every figure is the one written out in the class Javadoc.
     */
    @Test
    void theBooksOpenOnPactsFiguresWithoutCountingWhatTheContractImportAlreadyPosted() throws Exception {
        importAndPost();
        uploadPactTrialBalance();

        openingBalances.post();

        // Non-derived: exactly PACT's figure. Gross, the bank would read 281,000.00
        // (250,000 + the 31,000 cleared cheque, counted twice) and output VAT -4,050.00.
        assertThat(balanceOf("Sample Bank - ST1")).isEqualByComparingTo("250000.00");
        assertThat(balanceOf(outputVatName())).isEqualByComparingTo("-3000.00");
        assertThat(balanceOf("Capital Account")).isEqualByComparingTo("-218050.00");

        // Derived: exactly what step 1 posted, PACT's own figure ignored. PACT says the
        // PDC receivable is 48,050; ours is 47,050, and the gap belongs in the
        // difference line rather than on the account.
        assertThat(balanceOf("PDC Receivable ST1")).isEqualByComparingTo("47050.00");
        assertThat(balanceOf("Advance Rent - ST1")).isEqualByComparingTo("-71021.92");
        assertThat(balanceOf("Rental Income ST1")).isEqualByComparingTo("-978.08");
        assertThat(balanceOf("Security Deposit ST1")).isEqualByComparingTo("-5000.00");
        assertThat(balanceOf("Rent Receivable - ST1")).isEqualByComparingTo("0.00");

        // And the books balance.
        BigDecimal net = tx.execute(s -> ledger.trialBalance(CutoverFixture.AS_OF, null).stream()
                .map(TrialBalanceRowDTO::balance).reduce(BigDecimal.ZERO, BigDecimal::add));
        assertThat(net).isEqualByComparingTo("0.00");
    }

    /**
     * The grid says what the journal will do, before it does it: the bank's
     * <em>post</em> figure is the remainder, not PACT's figure — and all three
     * columns are on the row at once, so the subtraction is visible rather than
     * implied (rulings R17 and R25).
     */
    @Test
    void theGridShowsTheFigureThatWillPostRatherThanPactsGrossBalance() throws Exception {
        importAndPost();
        uploadPactTrialBalance();

        var grid = openingBalances.grid();
        var bank = grid.rows().stream().filter(r -> "Sample Bank - ST1".equals(r.name()))
                .findFirst().orElseThrow();
        assertThat(bank.derived()).as("the bank is NOT one of the derived roles").isFalse();
        // entered = PACT's file, untouched; derived = what step 1 left; post = the two subtracted.
        assertThat(bank.enteredDebit()).isEqualByComparingTo("250000.00");
        assertThat(bank.derivedDebit()).isEqualByComparingTo("31000.00");
        assertThat(bank.postDebit()).isEqualByComparingTo("219000.00");

        var vat = grid.rows().stream().filter(r -> outputVatName().equals(r.name()))
                .findFirst().orElseThrow();
        assertThat(vat.derived()).isFalse();
        assertThat(vat.enteredCredit()).isEqualByComparingTo("3000.00");
        assertThat(vat.derivedCredit()).isEqualByComparingTo("1050.00");
        assertThat(vat.postCredit()).isEqualByComparingTo("1950.00");
        // A cut-over contract is CONTRACT-timed, so nothing is deferred and the Output
        // VAT line is measured against Output VAT alone — no pairing, no warning.
        assertThat(grid.problems()).noneMatch(p -> p.message().contains("not yet due"));
        assertThat(balanceOf(deferredVatName())).isEqualByComparingTo("0.00");

        // A derived row still shows PACT's figure — the reconciliation screen compares
        // against it — but nothing will be posted to it.
        var pdc = grid.rows().stream().filter(r -> "PDC Receivable ST1".equals(r.name()))
                .findFirst().orElseThrow();
        assertThat(pdc.derived()).isTrue();
        assertThat(pdc.enteredDebit()).isEqualByComparingTo("48050.00");
        assertThat(pdc.derivedDebit()).isEqualByComparingTo("47050.00");
        assertThat(pdc.postDebit()).isEqualByComparingTo("0.00");
        assertThat(pdc.postCredit()).isEqualByComparingTo("0.00");

        // The computed difference row carries the balancing figure on post*, and PACT's
        // file named no difference figure at all, so entered* is empty.
        var diff = grid.rows().stream().filter(r -> differenceAccountName().equals(r.name()))
                .findFirst().orElseThrow();
        assertThat(diff.computed()).isTrue();
        assertThat(diff.enteredDebit()).isEqualByComparingTo("0.00");
        assertThat(diff.postDebit()).isEqualByComparingTo("1000.00");

        // The totals and the difference are the journal's — sums of post*, not of
        // entered* — so the number on the screen the accountant presses Post from is
        // the number posted.
        assertThat(grid.totalDebit()).isEqualByComparingTo("219000.00");
        assertThat(grid.totalCredit()).isEqualByComparingTo("220000.00");
        assertThat(grid.difference()).isEqualByComparingTo("-1000.00");
    }

    /**
     * The feedback loop ruling R25 closes, asserted end to end.
     *
     * <p>The screen seeds its edit inputs from {@code entered*}. Save a row back
     * untouched — which is what happens the moment an accountant opens the bank row,
     * changes something else and presses Save — and the stored snapshot must still
     * say 250,000, so the post figure must still be 219,000. When {@code entered*}
     * carried the delta instead, the round trip stored 219,000 as the new PACT figure
     * and the next post would have written 188,000, one subtraction further away
     * every time.</p>
     */
    @Test
    void savingABankRowBackExactlyAsItWasReadChangesNothing() throws Exception {
        importAndPost();
        uploadPactTrialBalance();

        var before = bankRow(openingBalances.grid());
        openingBalances.setRow(before.accountId(), before.enteredDebit(), before.enteredCredit());

        var after = bankRow(openingBalances.grid());
        assertThat(after.enteredDebit()).isEqualByComparingTo(before.enteredDebit());
        assertThat(after.derivedDebit()).isEqualByComparingTo(before.derivedDebit());
        assertThat(after.postDebit())
                .as("the figure that will post is the same one the screen was read from")
                .isEqualByComparingTo(before.postDebit())
                .isEqualByComparingTo("219000.00");

        // And the whole grid agrees: totals and difference unmoved.
        var grid = openingBalances.grid();
        assertThat(grid.totalDebit()).isEqualByComparingTo("219000.00");
        assertThat(grid.totalCredit()).isEqualByComparingTo("220000.00");
        assertThat(grid.difference()).isEqualByComparingTo("-1000.00");

        // The books still open on PACT's figure, which is the thing the loop broke.
        openingBalances.post();
        assertThat(balanceOf("Sample Bank - ST1")).isEqualByComparingTo("250000.00");
    }

    private OpeningBalanceService.OpeningBalanceRow bankRow(OpeningBalanceService.OpeningBalanceGrid grid) {
        return grid.rows().stream().filter(r -> "Sample Bank - ST1".equals(r.name()))
                .findFirst().orElseThrow();
    }

    /**
     * The difference line is the real unreconciled gap on the derived roles, asserted
     * as the identity rather than as a constant: {@code difference = Σ_derived (PACT −
     * ours)}. Gross, it carried the bank and VAT double count instead and meant
     * nothing at all.
     */
    @Test
    void theDifferenceLineIsExactlyTheUnreconciledGapOnTheDerivedAccounts() throws Exception {
        importAndPost();
        uploadPactTrialBalance();

        openingBalances.post();

        BigDecimal gapOnDerivedRoles = tx.execute(s -> openingBalances.reconcile().stream()
                .filter(ReconciliationRow::derived)
                // difference() is ours − PACT; the difference line closes the gap, so it
                // is the negation.
                .map(ReconciliationRow::difference)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .negate());

        assertThat(gapOnDerivedRoles).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(differenceAccountName())).isEqualByComparingTo(gapOnDerivedRoles);
    }

    /**
     * The other case of the Output VAT pairing (spec 2026-09-24 §1): a lease posted
     * here on the INSTALMENT model before the cut-over date, with 500.00 of its VAT
     * still waiting in Output VAT – not yet due at D − 1. PACT booked that lease's VAT
     * into Output VAT on its contract date, so PACT's 3,000 already includes the 500.
     *
     * <pre>
     *   ours at D − 1:  Output VAT −1,050.00 (SAMPLE-0002, CONTRACT-timed)
     *                   Output VAT – not yet due −500.00 (derived)
     *                   Rent Receivable − ST1   +500.00 (derived; PACT names none)
     *
     *   Sample Bank - ST1     Dr 219,000.00   = 250,000.00 − 31,000.00
     *   Output VAT on Sales   Cr   1,450.00   =   3,000.00 − (1,050.00 + 500.00)
     *   Capital Account       Cr 218,050.00
     *   Opening Balance Diff  Dr     500.00   = PDC gap 1,000.00 − receivable 500.00
     * </pre>
     *
     * The two VAT accounts end at −2,500 and −500: PACT's 3,000 exactly, once. The
     * deferred leaf is derived, so it is never posted to, and its own "gap" against
     * PACT (which names no such account) is the one the Output VAT line absorbed —
     * so it is left out of the identity.
     */
    @Test
    void aLeasePostedHereBeforeTheCutOverKeepsItsDeferredVatAndPactsOutputVatCoversIt() throws Exception {
        importAndPost();
        postDeferredVatBeforeTheCutOver("500.00");
        uploadPactTrialBalance();

        var grid = openingBalances.grid();
        var vat = grid.rows().stream().filter(r -> outputVatName().equals(r.name()))
                .findFirst().orElseThrow();
        assertThat(vat.enteredCredit()).isEqualByComparingTo("3000.00");
        assertThat(vat.derivedCredit()).isEqualByComparingTo("1050.00");
        assertThat(vat.postCredit()).isEqualByComparingTo("1450.00");
        assertThat(grid.problems()).anySatisfy(p -> assertThat(p.message())
                .contains("500.00 of it is already on the books as output VAT not yet due"));
        var deferred = grid.rows().stream().filter(r -> deferredVatName().equals(r.name()))
                .findFirst().orElseThrow();
        assertThat(deferred.derived()).as("OUTPUT_VAT_DEFERRED is derived").isTrue();
        assertThat(deferred.derivedCredit()).isEqualByComparingTo("500.00");
        assertThat(deferred.postDebit()).isEqualByComparingTo("0.00");
        assertThat(deferred.postCredit()).isEqualByComparingTo("0.00");

        openingBalances.post();

        assertThat(balanceOf(outputVatName())).isEqualByComparingTo("-2500.00");
        assertThat(balanceOf(deferredVatName())).as("the deferred VAT is left intact")
                .isEqualByComparingTo("-500.00");
        assertThat(balanceOf(outputVatName()).add(balanceOf(deferredVatName())))
                .as("PACT's output VAT, on the books once").isEqualByComparingTo("-3000.00");
        assertThat(balanceOf(differenceAccountName())).isEqualByComparingTo("500.00");

        BigDecimal gapOnDerivedRoles = tx.execute(s -> openingBalances.reconcile().stream()
                .filter(ReconciliationRow::derived)
                .filter(r -> !deferredVatName().equals(r.name()))
                .map(ReconciliationRow::difference)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .negate());
        assertThat(balanceOf(differenceAccountName())).isEqualByComparingTo(gapOnDerivedRoles);

        BigDecimal net = tx.execute(s -> ledger.trialBalance(CutoverFixture.AS_OF, null).stream()
                .map(TrialBalanceRowDTO::balance).reduce(BigDecimal.ZERO, BigDecimal::add));
        assertThat(net).isEqualByComparingTo("0.00");
    }

    /**
     * The {@code −ours} branch of {@link OpeningBalanceService#postable} (review C2,
     * finding N3): an account our books hold a balance on that PACT's file never
     * names at all, rather than one it names at 0.
     *
     * <p>This suite's PACT file otherwise names all seven accounts {@code ours}
     * touches, so that branch never ran. Here Output VAT is dropped from the CSV
     * entirely — PACT(Output VAT) is undefined rather than 0 — and the class
     * Javadoc's hand-derivation says {@code ours} on that account is a credit of
     * 1,050.00 (SAMPLE-0002's VAT). The line posts {@code −ours} — a debit of
     * 1,050.00, closing out the credit step 1 left — which zeroes the account out
     * at D − 1 rather than leaving it carrying what step 1 posted.</p>
     */
    @Test
    void anAccountPactNeverNamesIsZeroedOutRatherThanLeftCarryingWhatStep1Posted() throws Exception {
        importAndPost();
        uploadPactTrialBalanceWithoutOutputVat();

        var grid = openingBalances.grid();
        var vat = grid.rows().stream().filter(r -> outputVatName().equals(r.name()))
                .findFirst().orElseThrow();
        assertThat(vat.derived()).as("output VAT is NOT one of the derived roles").isFalse();
        // PACT never named it at all, so entered* is empty — unlike the difference
        // row, which is empty for the same reason but computed rather than skipped.
        assertThat(vat.enteredDebit()).isEqualByComparingTo("0.00");
        assertThat(vat.enteredCredit()).isEqualByComparingTo("0.00");
        // ours: SAMPLE-0002's VAT, a credit of 1,050.00 (class Javadoc).
        assertThat(vat.derivedDebit()).isEqualByComparingTo("0.00");
        assertThat(vat.derivedCredit()).isEqualByComparingTo("1050.00");
        // The post figure is −ours: a debit of 1,050.00 closes out the credit step 1
        // left, rather than leaving the account carrying it forever.
        assertThat(vat.postDebit()).isEqualByComparingTo("1050.00");
        assertThat(vat.postCredit()).isEqualByComparingTo("0.00");

        openingBalances.post();

        assertThat(balanceOf(outputVatName()))
                .as("the account PACT never named ends the cut-over at zero, not at what step 1 posted")
                .isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------
    // the order rule: opening balances are the LAST step
    // ------------------------------------------------------------------
    //
    // Ruling R17, spec §10.3 "Amendment 2026-09-22". Each of the three acts below
    // would move `ours` under an opening journal that has already netted the old
    // value out, leaving the books holding neither PACT's figure nor ours. All three
    // are refused with one sentence, and none of them writes anything first.

    @Test
    void aBulkPostIsRefusedWhileTheOpeningBalancesAreLive() throws Exception {
        UUID batchId = importTheTemplate();
        uploadPactTrialBalance();
        openingBalances.post();

        assertThatThrownBy(() -> postService.post(batchId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage(ImportBatchService.OPENING_BALANCES_ARE_LIVE);

        assertThat(batches.get(batchId).getStatus()).isEqualTo(ImportBatchStatus.DRAFT);
        assertThat(balanceOf("PDC Receivable ST1")).isEqualByComparingTo("0.00");
    }

    @Test
    void aBatchReverseIsRefusedWhileTheOpeningBalancesAreLive() throws Exception {
        UUID batchId = importAndPost();
        uploadPactTrialBalance();
        openingBalances.post();

        assertThatThrownBy(() -> batches.reverse(batchId, "corrected workbook"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage(ImportBatchService.OPENING_BALANCES_ARE_LIVE);

        assertThat(batches.get(batchId).getStatus()).isEqualTo(ImportBatchStatus.POSTED);
        assertThat(balanceOf("PDC Receivable ST1")).isEqualByComparingTo("47050.00");
    }

    /**
     * Post again — the successor-batch path — is the third door into the same
     * problem, and the one an accountant is most likely to walk through: reverse,
     * correct, open the books while waiting, then press Post.
     */
    @Test
    void postingAReversedBatchAgainIsRefusedWhileTheOpeningBalancesAreLive() throws Exception {
        UUID batchId = importAndPost();
        batches.reverse(batchId, "corrected workbook");
        uploadPactTrialBalance();
        openingBalances.post();

        assertThatThrownBy(() -> postService.post(batchId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage(ImportBatchService.OPENING_BALANCES_ARE_LIVE);

        assertThat(batches.get(batchId).getStatus()).isEqualTo(ImportBatchStatus.REVERSED);
        assertThat(batches.successorOf(batchId)).as("no successor batch was made either").isEmpty();
    }

    /**
     * The accountant who opened the books before importing the contracts, and the
     * cycle the order rule sends them round: reverse the opening balances, do the
     * cut-over, post them again. The books land on the same figures either way,
     * because the second journal is computed against what step 1 left behind.
     *
     * <p>Note what the first post writes: against empty books {@code ours} is zero
     * everywhere, so the delta <em>is</em> PACT's figure — the rule costs nothing in
     * the ordinary case and only bites where our books already hold something.</p>
     */
    @Test
    void openingTheBooksBeforeTheCutOverAndReplacingAfterwardsLandsOnTheSameFigures() throws Exception {
        uploadPactTrialBalance();
        openingBalances.post();
        assertThat(openingBalances.grid().changedSincePosted()).isFalse();
        assertThat(balanceOf("Sample Bank - ST1")).isEqualByComparingTo("250000.00");

        openingBalances.reverse("making room for the cut-over");
        importAndPost();
        openingBalances.repost("after the cut-over");

        assertThat(balanceOf("Sample Bank - ST1")).isEqualByComparingTo("250000.00");
        assertThat(balanceOf(outputVatName())).isEqualByComparingTo("-3000.00");
        assertThat(balanceOf("PDC Receivable ST1")).isEqualByComparingTo("47050.00");
        assertThat(balanceOf(differenceAccountName())).isEqualByComparingTo("1000.00");
        assertThat(openingBalances.grid().changedSincePosted()).isFalse();
    }
}
