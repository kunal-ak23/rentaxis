package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.GenerateChequesRequest;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeRowKind;
import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;

import java.math.BigDecimal;

/**
 * Owner request (2026-09-29): with 4 cheques and a one-time admin fee there are
 * still 4 cheques — cheque 1 carries rent instalment 1 plus the fee — unless the
 * user asks for a separate cheque for one-time charges.
 *
 * <p>The generator already folds one-time charges into cheque 1 by default
 * ({@code foldDepositsAndFeesIntoFirst}, a MIXED row, PACT's own practice), and the
 * ledger carries it without change: the TCO debits the lease's one receivable for
 * the whole contract (rent and fee), each cheque's PDR credits that receivable for
 * its full amount and debits PDC, clearing moves PDC to the bank, and a bounce puts
 * the whole cheque — rent part and fee part — back on the receivable. This pins it.</p>
 */
@SpringBootTest
class CombinedFirstChequeIT extends AbstractPostgresIT {

    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService cheques;
    @Autowired ChequeService chequeService;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired UnitRepository unitRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LedgerQueryService ledger;
    @Autowired AccountResolver resolver;
    @Autowired TransactionTemplate tx;

    private static final LocalDate TODAY = LocalDate.now(java.time.ZoneId.of("Asia/Dubai"));
    private static final LocalDate START = TODAY.minusMonths(2).withDayOfMonth(1);
    private static final LocalDate END = START.plusYears(1).minusDays(1);

    private LeaseTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, cheques, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private static GenerateChequesRequest request(boolean fold) {
        return new GenerateChequesRequest(4, START, InstallmentDistribution.LAST_LARGER, "Emirates NBD", null, fold, null);
    }

    private UUID draft() {
        return fixtures.draftLease(START, START, END, List.of(line("RENT", "60000"), line("ADMIN_FEE", "1050")));
    }

    private static BigDecimal total(List<ChequeDTO> rows) {
        return rows.stream().map(ChequeDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal balanceOf(AccountRole role, UUID leaseId) {
        UUID account = tx.execute(s -> resolver.resolve(role, fixtures.property().getId()).getId());
        return tx.execute(s -> ledger.accountLedger(account,
                new LedgerQueryService.LedgerFilter(null, null, null, null, leaseId, null)).closingBalance());
    }

    private void assertTrialBalanceBalances() {
        List<TrialBalanceRowDTO> rows = tx.execute(s -> ledger.trialBalance(TODAY.plusYears(2), null));
        assertThat(rows).isNotEmpty();
        BigDecimal debit = rows.stream().map(TrialBalanceRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = rows.stream().map(TrialBalanceRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).as("trial balance").isEqualByComparingTo(credit);
    }

    @Test
    void fourChequesAndAnAdminFeeAreFourChequesTheFirstCarryingTheFee() {
        UUID id = draft();
        List<ChequeDTO> rows = cheques.generate(id, request(true));

        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("16050");     // 15,000 + 1,050
        assertThat(rows.get(0).narration()).contains("Admin");
        assertThat(rows.subList(1, 4)).allSatisfy(r -> assertThat(r.amount()).isEqualByComparingTo("15000"));
        assertThat(total(rows)).isEqualByComparingTo("61050");
        assertThat(cheques.preview(id, request(true))).extracting(ChequeGenerationService.PreviewRow::kind)
                .containsExactly(ChequeRowKind.MIXED, ChequeRowKind.RENT, ChequeRowKind.RENT, ChequeRowKind.RENT);
    }

    @Test
    void aSeparateChequeForOneTimeChargesWhenAskedFor() {
        UUID id = draft();
        List<ChequeDTO> rows = cheques.generate(id, request(false));
        assertThat(rows).hasSize(5);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("15000");
        assertThat(rows.get(4).amount()).isEqualByComparingTo("1050");
        assertThat(total(rows)).isEqualByComparingTo("61050");
    }

    @Test
    void aCombinedChequePostsClearsAndBouncesWithTheLedgerBalanced() {
        UUID a = draft();
        cheques.generate(a, request(true));
        fixtures.numberGrid(a, LeaseTestFixtures.nextChequeBook());
        posting.post(a);
        // TCO debits the one receivable for rent + fee; the four PDRs credit it in full.
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, a)).isEqualByComparingTo("0");
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, a)).isEqualByComparingTo("61050");
        assertTrialBalanceBalances();

        ChequeDTO first = cheques.list(a).get(0);
        chequeService.deposit(first.id(), ChequeActionRequest.on(TODAY));
        chequeService.bounce(first.id(), new ChequeActionRequest(TODAY, "Returned unpaid", ChequeFailureReason.BOUNCE, null));
        // The whole cheque is owed again: the rent instalment and the admin fee.
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, a)).isEqualByComparingTo("16050");
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, a)).isEqualByComparingTo("45000");
        assertTrialBalanceBalances();

        // A second contract on another unit: the combined cheque clears.
        UUID b = fixtures.draftLease(fixtures.createUnit(fixtures.property(), "CF-2"), fixtures.renter(),
                START, START, END, List.of(line("RENT", "60000"), line("ADMIN_FEE", "1050")));
        cheques.generate(b, request(true));
        fixtures.numberGrid(b, LeaseTestFixtures.nextChequeBook());
        posting.post(b);
        ChequeDTO combined = cheques.list(b).get(0);
        chequeService.deposit(combined.id(), ChequeActionRequest.on(TODAY));
        chequeService.clear(combined.id(), ChequeActionRequest.on(TODAY));
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, b)).isEqualByComparingTo("0");
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, b)).isEqualByComparingTo("45000");
        assertTrialBalanceBalances();
    }
}
