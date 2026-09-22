package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.cutover.OpeningBalanceService.ReconciliationRow;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.repository.TenantDefaultAccountMappingRepository;
import org.apache.poi.ss.usermodel.Workbook;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cut-over composed: import → bulk post → upload PACT's trial balance → open the
 * books. Review C2, ruling R17.
 *
 * <p><b>The gap this class exists to close.</b> Nothing in the suite used to run
 * both halves of the cut-over against one set of books — {@code OpeningBalanceIT}
 * never calls the bulk post, {@code CutoverReconciliationIT} never posts the opening
 * balances — so the one arithmetic that matters on the client's first day was
 * untested. It was also wrong: the opening journal posted PACT's figure <em>gross</em>
 * on every account outside the nine {@link OpeningBalanceService#DERIVED_ROLES}, and
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
@Testcontainers
class CutoverOpeningBalanceIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired CutoverFixture fixture;
    @Autowired ContractImportPersistService contractPersist;
    @Autowired ContractImportPostService postService;
    @Autowired OpeningBalanceService openingBalances;
    @Autowired AccountService accounts;
    @Autowired AccountResolver resolver;
    @Autowired LedgerQueryService ledger;
    @Autowired TenantDefaultAccountMappingRepository defaultMappings;
    @Autowired TransactionTemplate tx;

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

    private UUID importAndPost() throws Exception {
        UUID batchId;
        try (Workbook wb = fixture.template()) {
            batchId = contractPersist.persist(wb, fixture.newJob()).batchId();
        }
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
     * The grid says what the journal will do, before it does it: the bank's line is
     * the remainder, not PACT's figure, and the derived column beside it is the other
     * half of the subtraction.
     */
    @Test
    void theGridShowsTheFigureThatWillPostRatherThanPactsGrossBalance() throws Exception {
        importAndPost();
        uploadPactTrialBalance();

        var grid = openingBalances.grid();
        var bank = grid.rows().stream().filter(r -> "Sample Bank - ST1".equals(r.name()))
                .findFirst().orElseThrow();
        assertThat(bank.derived()).as("the bank is NOT one of the nine derived roles").isFalse();
        assertThat(bank.derivedDebit()).isEqualByComparingTo("31000.00");
        assertThat(bank.enteredDebit()).isEqualByComparingTo("219000.00");

        var vat = grid.rows().stream().filter(r -> outputVatName().equals(r.name()))
                .findFirst().orElseThrow();
        assertThat(vat.derived()).isFalse();
        assertThat(vat.derivedCredit()).isEqualByComparingTo("1050.00");
        assertThat(vat.enteredCredit()).isEqualByComparingTo("1950.00");

        // The totals and the difference are the journal's, so the number on the screen
        // the accountant presses Post from is the number posted.
        assertThat(grid.totalDebit()).isEqualByComparingTo("219000.00");
        assertThat(grid.totalCredit()).isEqualByComparingTo("220000.00");
        assertThat(grid.difference()).isEqualByComparingTo("-1000.00");
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
