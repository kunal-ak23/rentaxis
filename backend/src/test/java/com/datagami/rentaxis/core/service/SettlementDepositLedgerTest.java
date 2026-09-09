package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.FinancialTransaction;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseSettlement;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.SettlementStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.LeaseSettlementDeductionRepository;
import com.datagami.rentaxis.domain.repository.LeaseSettlementRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Finalizing a settlement must release the security-deposit liability.
 *
 * <p>Deposits are credited to B-01-02 when the deposit cheque clears. Before
 * this existed, settlement posted nothing to the ledger at all, so the liability
 * was never released and the books carried every deposit ever taken.
 *
 * <p>The invariant every test here checks is that the posted set balances:
 * total debits equal total credits. Since
 * {@code refund = deposit - deductions + additions}, releasing the full deposit
 * against the refund plus the retained remainder must come out even.
 */
class SettlementDepositLedgerTest {

    private static final UUID TENANT = UUID.randomUUID();

    private LeaseSettlementRepository settlementRepository;
    private AccountRepository accountRepository;
    private FinancialTransactionService financialTransactionService;
    private SettlementService service;

    private Account depositLiability;
    private Account bank;
    private Account otherIncome;

    private Account account(String code, AccountType type) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setCode(code);
        a.setAccountType(type);
        return a;
    }

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(TENANT);

        settlementRepository = mock(LeaseSettlementRepository.class);
        accountRepository = mock(AccountRepository.class);
        financialTransactionService = mock(FinancialTransactionService.class);

        depositLiability = account("B-01-02", AccountType.LIABILITY);
        bank = account("A-02-02", AccountType.ASSET);
        otherIncome = account("C-01-02", AccountType.INCOME);

        when(accountRepository.findByCodeAndTenantId("B-01-02", TENANT)).thenReturn(Optional.of(depositLiability));
        when(accountRepository.findByCodeAndTenantId("A-02-02", TENANT)).thenReturn(Optional.of(bank));
        when(accountRepository.findByCodeAndTenantId("C-01-02", TENANT)).thenReturn(Optional.of(otherIncome));

        LeaseRepository leaseRepository = mock(LeaseRepository.class);
        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setTenantId(TENANT);
        when(leaseRepository.findById(any())).thenReturn(Optional.of(lease));

        service = new SettlementService(
                settlementRepository,
                mock(LeaseSettlementDeductionRepository.class),
                leaseRepository,
                mock(PaymentScheduleRepository.class),
                mock(PenaltyService.class),
                mock(DeductionAttachmentService.class),
                mock(AccountMappingService.class),   // no mapping configured -> code fallback
                accountRepository,
                financialTransactionService);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private List<FinancialTransaction> finalizeWith(String deposit, String refund) {
        UUID leaseId = UUID.randomUUID();
        LeaseSettlement s = new LeaseSettlement();
        s.setId(UUID.randomUUID());
        s.setLeaseId(leaseId);
        s.setStatus(SettlementStatus.DRAFT);
        s.setDepositAmount(new BigDecimal(deposit));
        s.setRefundAmount(new BigDecimal(refund));

        when(settlementRepository.findByLeaseId(leaseId)).thenReturn(Optional.of(s));
        when(settlementRepository.save(any(LeaseSettlement.class))).thenAnswer(inv -> inv.getArgument(0));

        service.finalizeSettlement(leaseId, UUID.randomUUID());

        ArgumentCaptor<FinancialTransaction> captor = ArgumentCaptor.forClass(FinancialTransaction.class);
        verify(financialTransactionService, org.mockito.Mockito.atLeast(0)).createTransaction(captor.capture());
        return captor.getAllValues();
    }

    private static BigDecimal sum(List<FinancialTransaction> txns, boolean debit) {
        return txns.stream()
                .map(t -> debit ? t.getDebit() : t.getCredit())
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void fullRefundReleasesTheLiabilityAndPaysTheRenter() {
        List<FinancialTransaction> txns = finalizeWith("20000", "20000");

        assertThat(txns).hasSize(2);
        assertThat(txns).anySatisfy(t -> {
            assertThat(t.getAccount().getCode()).isEqualTo("B-01-02");
            assertThat(t.getDebit()).isEqualByComparingTo("20000");
        });
        assertThat(txns).anySatisfy(t -> {
            assertThat(t.getAccount().getCode()).isEqualTo("A-02-02");
            assertThat(t.getCredit()).isEqualByComparingTo("20000");
        });
        assertThat(sum(txns, true)).isEqualByComparingTo(sum(txns, false));
    }

    @Test
    void partialRefundRoutesTheRetainedDeductionsToIncome() {
        List<FinancialTransaction> txns = finalizeWith("20000", "15000");

        // 20000 liability released = 15000 back to the renter + 5000 retained.
        assertThat(txns).anySatisfy(t -> {
            assertThat(t.getAccount().getCode()).isEqualTo("C-01-02");
            assertThat(t.getCredit()).isEqualByComparingTo("5000");
        });
        assertThat(sum(txns, true)).isEqualByComparingTo("20000");
        assertThat(sum(txns, false)).isEqualByComparingTo("20000");
    }

    @Test
    void refundLargerThanTheDepositDebitsTheExcess() {
        // Additions exceeded deductions: the landlord pays out more than it held.
        List<FinancialTransaction> txns = finalizeWith("10000", "12000");

        assertThat(txns).anySatisfy(t -> {
            assertThat(t.getAccount().getCode()).isEqualTo("C-01-02");
            assertThat(t.getDebit()).isEqualByComparingTo("2000");
        });
        assertThat(sum(txns, true)).isEqualByComparingTo(sum(txns, false));
    }

    @Test
    void everythingRetainedStillBalances() {
        List<FinancialTransaction> txns = finalizeWith("8000", "0");

        assertThat(sum(txns, true)).isEqualByComparingTo("8000");
        assertThat(sum(txns, false)).isEqualByComparingTo("8000");
    }

    @Test
    void aLeaseWithNoDepositPostsNothing() {
        assertThat(finalizeWith("0", "0")).isEmpty();
        verify(financialTransactionService, never()).createTransaction(any());
    }

    @Test
    void settlementStillSucceedsWhenTheTenantHasNoChartOfAccounts() {
        when(accountRepository.findByCodeAndTenantId("B-01-02", TENANT)).thenReturn(Optional.empty());

        // The settlement record is the source of truth; a missing chart of
        // accounts must not block a lease from being settled.
        assertThat(finalizeWith("20000", "20000")).isEmpty();
    }
}
