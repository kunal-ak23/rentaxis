package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.cutover.OpeningBalanceService.OpeningBalanceGrid;
import com.datagami.rentaxis.core.service.cutover.OpeningBalanceService.OpeningBalanceRow;
import com.datagami.rentaxis.core.service.cutover.OpeningBalanceService.ReconciliationRow;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cut-over's last question: do our books, built out of the imported contracts,
 * agree with the trial balance the landlord is leaving PACT with?
 *
 * <p>The expected figures below are <b>derived by hand</b> from the template's two
 * sample contracts, not recomputed from the code. That is the point of the test: a
 * change in the posting rules that moves one of them has to move a number somebody
 * wrote down and explained, not a number the test asked the code for.</p>
 *
 * <h2>The portfolio, and what it is worth at 30 Sep 2026</h2>
 *
 * <p><b>SAMPLE-0001</b> — contract dated 11 Sep 2026, term 24 Sep 2026 – 23 Sep
 * 2027 (365 days). Lines: rent 51,000 (no VAT) and a security deposit of 5,000.
 * Two post-dated cheques: 31,000 cleared on 25 Sep, 25,000 still registered.</p>
 * <p><b>SAMPLE-0002</b> — contract dated 11 Sep 2026, term 1 Oct 2026 – 30 Sep
 * 2027. One rent line of 21,000, VAT-applicable, so 1,050 of output VAT and a
 * gross of 22,050 — its single cheque. Its term starts on the day the books open,
 * so it has earned nothing by 30 Sep.</p>
 *
 * <pre>
 *   TCO 0001   Dr rent receivable 51,000   Cr advance rent      51,000
 *              Dr rent receivable  5,000   Cr security deposit   5,000
 *   TCO 0002   Dr rent receivable 21,000   Cr advance rent      21,000
 *              Dr rent receivable  1,050   Cr output VAT         1,050
 *   PDR ×3     Dr PDC receivable  31,000 / 25,000 / 22,050      Cr rent receivable
 *   CRT        Dr bank            31,000                        Cr PDC receivable
 *   CIL        Dr advance rent       978.08                     Cr rental income
 * </pre>
 *
 * <p>The CIL is the only recognition that lands before the books open: 51,000 / 365
 * = 139.726027 a day, and 24–30 Sep is 7 days, so 139.726027 × 7 = 978.082189 →
 * <b>978.08</b>.</p>
 *
 * <p>Rent receivable nets to zero, and that is not a coincidence: the import
 * requires Σ cheques to equal the contract value including VAT, so every dirham the
 * contracts raise is matched by an instrument the landlord holds. At the cut-over
 * the debt lives in PDC receivable, not in the receivable.</p>
 */
@SpringBootTest
@Testcontainers
class CutoverReconciliationIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired CutoverFixture fixture;
    @Autowired ContractImportPersistService contractPersist;
    @Autowired ContractImportPostService postService;
    @Autowired OpeningBalanceService openingBalances;
    @Autowired AccountService accounts;
    @Autowired com.datagami.rentaxis.domain.repository.TenantDefaultAccountMappingRepository defaultMappings;
    @Autowired TransactionTemplate tx;

    UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = fixture.newCutOverTenant("RECON");
        fixture.authenticateAsTenantAdmin();
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
        fixture.clearAuthentication();
    }

    private UUID importAndPost() throws Exception {
        UUID batchId;
        try (Workbook wb = fixture.template()) {
            batchId = contractPersist.persist(wb, fixture.newJob()).batchId();
        }
        assertThat(postService.post(batchId).leasesFailed()).isZero();
        return batchId;
    }

    /** The row for an account, by the name the workbook gave it — never by position. */
    private static OpeningBalanceRow row(OpeningBalanceGrid grid, String name) {
        return grid.rows().stream().filter(r -> name.equals(r.name())).findFirst()
                .orElseThrow(() -> new AssertionError("No grid row named " + name));
    }

    private static ReconciliationRow recon(List<ReconciliationRow> rows, String name) {
        return rows.stream().filter(r -> name.equals(r.name())).findFirst()
                .orElseThrow(() -> new AssertionError("No reconciliation row named " + name));
    }

    // ------------------------------------------------------------------

    /**
     * The derived column, account by account, against figures written out above.
     * It was always {@code 0.00} until the bulk post existed to fill it.
     */
    @Test
    void theDerivedColumnIsWhatTheImportedPortfolioIsWorthOnTheDayBeforeTheBooksOpen() throws Exception {
        importAndPost();

        OpeningBalanceGrid grid = openingBalances.grid();
        assertThat(grid.asOf()).isEqualTo(CutoverFixture.AS_OF);

        // Every dirham the contracts raised is covered by an instrument, so the
        // receivable is flat and the debt sits in PDC receivable.
        assertThat(row(grid, "Rent Receivable - ST1").derivedDebit()).isEqualByComparingTo("0.00");
        assertThat(row(grid, "Rent Receivable - ST1").derivedCredit()).isEqualByComparingTo("0.00");

        // 31,000 + 25,000 + 22,050 raised, 31,000 cleared away.
        assertThat(row(grid, "PDC Receivable ST1").derivedDebit()).isEqualByComparingTo("47050.00");
        // The one cheque that actually reached the bank.
        assertThat(row(grid, "Sample Bank - ST1").derivedDebit()).isEqualByComparingTo("31000.00");
        // 51,000 + 21,000 deferred, less the 978.08 already earned.
        assertThat(row(grid, "Advance Rent - ST1").derivedCredit()).isEqualByComparingTo("71021.92");
        assertThat(row(grid, "Rental Income ST1").derivedCredit()).isEqualByComparingTo("978.08");
        assertThat(row(grid, "Security Deposit ST1").derivedCredit()).isEqualByComparingTo("5000.00");

        // Each of those is a role the grid refuses to take a figure for by hand.
        assertThat(row(grid, "PDC Receivable ST1").derived()).isTrue();
        assertThat(row(grid, "Advance Rent - ST1").derivedRole()).isEqualTo(AccountRole.ADVANCE_RENT);

        // The whole thing balances: 47,050 + 31,000 = 71,021.92 + 978.08 + 5,000 + 1,050.
        BigDecimal net = grid.rows().stream()
                .map(r -> r.derivedDebit().subtract(r.derivedCredit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net).isEqualByComparingTo("0.00");
    }

    /** Before the bulk post there is nothing derived, which is the honest zero. */
    @Test
    void theDerivedColumnIsEmptyWhileTheBatchIsStillADraft() throws Exception {
        try (Workbook wb = fixture.template()) {
            contractPersist.persist(wb, fixture.newJob());
        }

        assertThat(openingBalances.grid().rows())
                .allSatisfy(r -> {
                    assertThat(r.derivedDebit()).isEqualByComparingTo("0.00");
                    assertThat(r.derivedCredit()).isEqualByComparingTo("0.00");
                });
    }

    /**
     * PACT's trial balance against ours: the six derived accounts agree to the fils,
     * and the one account the landlord's old system had that ours does not is
     * reported rather than dropped.
     */
    @Test
    void anImportedPortfolioReconcilesAgainstPactsTrialBalance() throws Exception {
        importAndPost();
        uploadPactTrialBalance();

        List<ReconciliationRow> rows = openingBalances.reconcile();

        assertThat(recon(rows, "PDC Receivable ST1").derivedBalance()).isEqualByComparingTo("47050.00");
        assertThat(recon(rows, "PDC Receivable ST1").difference()).isEqualByComparingTo("0.00");
        assertThat(recon(rows, "Sample Bank - ST1").difference()).isEqualByComparingTo("0.00");
        assertThat(recon(rows, "Advance Rent - ST1").difference()).isEqualByComparingTo("0.00");
        assertThat(recon(rows, "Rental Income ST1").difference()).isEqualByComparingTo("0.00");
        assertThat(recon(rows, "Security Deposit ST1").difference()).isEqualByComparingTo("0.00");

        // Output VAT, which is the one account a VAT-bearing contract exists in this
        // template to produce (review I4). SAMPLE-0002's TCO raises 1,050 of it as a
        // second pair at the contract date, so a regression that dropped the pair or
        // credited the wrong leaf would show here as a 1,050 gap rather than passing
        // unnoticed.
        assertThat(recon(rows, outputVatName()).derivedBalance()).isEqualByComparingTo("-1050.00");
        assertThat(recon(rows, outputVatName()).difference()).isEqualByComparingTo("0.00");

        // A code PACT exported and our chart has no account for: kept and shown, with
        // its whole balance as the difference, rather than silently dropped.
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.accountId()).isNull();
            assertThat(r.code()).isEqualTo("999999");
            assertThat(r.difference()).isEqualByComparingTo("777.00");
        });
    }

    /** A figure PACT disagrees with shows up as exactly the gap, signed our way. */
    @Test
    void aFigurePactDisagreesWithIsTheDifference() throws Exception {
        importAndPost();
        // PACT thinks the bank holds 30,000; we say 31,000, because we replayed the
        // cheque that cleared on the 25th.
        uploadCsv("""
                Account Code,Account Name,Debit,Credit
                %s,Sample Bank - ST1,30000.00,0
                """.formatted(codeOf("Sample Bank - ST1")));

        assertThat(recon(openingBalances.reconcile(), "Sample Bank - ST1").difference())
                .isEqualByComparingTo("1000.00");
    }

    // ------------------------------------------------------------------
    // PACT's file
    // ------------------------------------------------------------------

    /**
     * The trial balance the landlord exports on the day they leave, keyed by our own
     * account codes so the match is by code rather than by name.
     */
    private void uploadPactTrialBalance() {
        uploadCsv("""
                Account Code,Account Name,Debit,Credit
                %s,PDC Receivable ST1,47050.00,0
                %s,Sample Bank - ST1,31000.00,0
                %s,Advance Rent - ST1,0,71021.92
                %s,Rental Income ST1,0,978.08
                %s,Security Deposit ST1,0,5000.00
                %s,Output VAT,0,1050.00
                999999,Directors Current Account,0,777.00
                """.formatted(
                codeOf("PDC Receivable ST1"), codeOf("Sample Bank - ST1"), codeOf("Advance Rent - ST1"),
                codeOf("Rental Income ST1"), codeOf("Security Deposit ST1"),
                codeOf(outputVatName())));
    }

    /**
     * The leaf the tenant's default OUTPUT_VAT mapping points at.
     *
     * <p>Resolved through the mapping rather than by a hard-coded name: the account
     * template seeds it (`B-01-03-001`), the Properties sheet has no column for it,
     * and a test that named the leaf directly would still pass if the mapping moved
     * to a different one.</p>
     */
    private String outputVatName() {
        return tx.execute(s -> accounts.getAccountById(
                defaultMappings.findAll().stream()
                        .filter(m -> m.getRole() == AccountRole.OUTPUT_VAT)
                        .findFirst().orElseThrow(() -> new AssertionError("OUTPUT_VAT is not mapped"))
                        .getAccount().getId()).getName());
    }

    private void uploadCsv(String csv) {
        openingBalances.uploadSnapshot(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    private String codeOf(String accountName) {
        return tx.execute(s -> accounts.getAllAccounts().stream()
                .filter(a -> accountName.equals(a.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("No account named " + accountName))
                .getCode());
    }
}
