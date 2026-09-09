package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.UpdatePaymentStatusDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.AccountMapping;
import com.datagami.rentaxis.domain.entity.FinancialTransaction;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.TransactionNature;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LeaseChargeRepository;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A refundable security deposit is a liability, not income.
 *
 * <p>{@code clearPayment} resolved {@link TransactionNature#RENT_PAYMENT_CLEARED}
 * unconditionally, so a cleared deposit cheque was credited to Rental Income —
 * overstating income by the whole deposit book (typically one month's rent per
 * active lease) and leaving B-01-02 "Security Deposits" permanently at zero. The
 * SECURITY_DEPOSIT_RECEIVED mapping was seeded and editable in the admin UI the
 * whole time; nothing read it.
 */
class PaymentScheduleServiceDepositPostingTest {

    private static final UUID TENANT = UUID.randomUUID();

    private PaymentScheduleRepository paymentScheduleRepository;
    private AccountMappingService accountMappingService;
    private AccountRepository accountRepository;
    private FinancialTransactionService financialTransactionService;
    private PaymentScheduleService service;

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
        paymentScheduleRepository = mock(PaymentScheduleRepository.class);
        accountMappingService = mock(AccountMappingService.class);
        accountRepository = mock(AccountRepository.class);
        financialTransactionService = mock(FinancialTransactionService.class);

        service = new PaymentScheduleService(
                paymentScheduleRepository,
                mock(LeaseChargeRepository.class),
                mock(LeaseRepository.class),
                accountRepository,
                financialTransactionService,
                accountMappingService,
                mock(RentCollectionSettingsRepository.class),
                mock(NotificationService.class),
                mock(FineConfigResolver.class),
                mock(PaymentPenaltyRepository.class),
                mock(LeaseEventRepository.class),
                mock(ApplicationEventPublisher.class),
                new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private void stubMapping(TransactionNature nature, String debitCode, String creditCode) {
        AccountMapping mapping = new AccountMapping();
        mapping.setDebitAccount(account(debitCode, AccountType.ASSET));
        mapping.setCreditAccount(account(creditCode, AccountType.LIABILITY));
        when(accountMappingService.resolveMapping(nature)).thenReturn(mapping);
    }

    private PaymentSchedule depositedRow(String amount, boolean securityDeposit) {
        Lease lease = new Lease();
        lease.setRenter(new Renter()); // null userId → notification side effect skipped

        PaymentSchedule ps = new PaymentSchedule();
        ps.setId(UUID.randomUUID());
        ps.setLease(lease);
        ps.setProperty(new Property());
        ps.setUnit(new Unit());
        ps.setAmount(new BigDecimal(amount));
        ps.setInstallmentNumber(1);
        ps.setStatus(PaymentStatus.DEPOSITED);
        ps.setSecurityDeposit(securityDeposit);
        return ps;
    }

    private List<FinancialTransaction> clearAndCapture(PaymentSchedule ps) {
        when(paymentScheduleRepository.findByIdForUpdate(ps.getId())).thenReturn(Optional.of(ps));
        when(paymentScheduleRepository.save(any(PaymentSchedule.class))).thenAnswer(inv -> inv.getArgument(0));

        service.clearPayment(ps.getId(), new UpdatePaymentStatusDTO());

        ArgumentCaptor<FinancialTransaction> captor = ArgumentCaptor.forClass(FinancialTransaction.class);
        verify(financialTransactionService, times(2)).createTransaction(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void clearedDepositCreditsTheLiabilityAccountNotRentalIncome() {
        stubMapping(TransactionNature.SECURITY_DEPOSIT_RECEIVED, "A-02-02", "B-01-02");

        List<FinancialTransaction> txns = clearAndCapture(depositedRow("20000", true));

        FinancialTransaction credit = txns.get(1);
        assertThat(credit.getAccount().getCode()).isEqualTo("B-01-02");
        assertThat(credit.getCredit()).isEqualByComparingTo("20000");
        assertThat(credit.getDescription()).contains("Security deposit held");
    }

    @Test
    void clearedRentStillCreditsRentalIncome() {
        stubMapping(TransactionNature.RENT_PAYMENT_CLEARED, "A-02-02", "C-01-01");

        List<FinancialTransaction> txns = clearAndCapture(depositedRow("5000", false));

        FinancialTransaction credit = txns.get(1);
        assertThat(credit.getAccount().getCode()).isEqualTo("C-01-01");
        assertThat(credit.getDescription()).contains("Rental income");
    }

    @Test
    void depositResolvesTheDepositMappingNotTheRentMapping() {
        stubMapping(TransactionNature.SECURITY_DEPOSIT_RECEIVED, "A-02-02", "B-01-02");

        clearAndCapture(depositedRow("20000", true));

        verify(accountMappingService).resolveMapping(TransactionNature.SECURITY_DEPOSIT_RECEIVED);
        verify(accountMappingService, times(0)).resolveMapping(TransactionNature.RENT_PAYMENT_CLEARED);
    }

    @Test
    void depositFallsBackToTheSeededLiabilityCodeWhenNoMappingExists() {
        // No mapping configured — the import-onboarded tenant case.
        when(accountMappingService.resolveMapping(any())).thenReturn(null);
        when(accountRepository.findByCodeAndTenantId("A-02-02", TENANT))
                .thenReturn(Optional.of(account("A-02-02", AccountType.ASSET)));
        when(accountRepository.findByCodeAndTenantId("B-01-02", TENANT))
                .thenReturn(Optional.of(account("B-01-02", AccountType.LIABILITY)));

        List<FinancialTransaction> txns = clearAndCapture(depositedRow("15000", true));

        assertThat(txns.get(1).getAccount().getCode()).isEqualTo("B-01-02");
    }

    @Test
    void bothLegsCarryTheSameAmountSoTheEntryBalances() {
        stubMapping(TransactionNature.SECURITY_DEPOSIT_RECEIVED, "A-02-02", "B-01-02");

        List<FinancialTransaction> txns = clearAndCapture(depositedRow("20000", true));

        assertThat(txns.get(0).getDebit()).isEqualByComparingTo(txns.get(1).getCredit());
    }
}
