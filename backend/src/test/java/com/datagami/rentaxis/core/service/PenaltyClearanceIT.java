package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.FinancialTransaction;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.PenaltyPayment;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.FinancialTransactionRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.PenaltyPaymentRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration test for the penalty-clearance flow:
 *
 * <ul>
 *   <li>partial pay → penalty stays open, outstanding decreases</li>
 *   <li>final pay → penalty clears, second FT pair posted</li>
 *   <li>accrual-aware clearance → daysOverdue extends the total owed</li>
 *   <li>overpayment → BusinessRuleViolationException + rollback</li>
 *   <li>waiver → terminal, blocks subsequent receipts</li>
 * </ul>
 *
 * <p>Boots a real Postgres via Testcontainers; mark-failed first to seed an
 * open penalty, then drives {@link PenaltyPaymentService#recordReceipt} +
 * {@link PenaltyService#waivePenalty} through the full @Transactional +
 * tenant-filter chain. Requires Docker on the host.</p>
 */
@SpringBootTest
@Testcontainers
class PenaltyClearanceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired PaymentScheduleService paymentScheduleService;
    @Autowired PenaltyPaymentService penaltyPaymentService;
    @Autowired PenaltyService penaltyService;
    @Autowired PaymentPenaltyRepository paymentPenaltyRepository;
    @Autowired PenaltyPaymentRepository penaltyPaymentRepository;
    @Autowired PaymentScheduleRepository paymentScheduleRepository;
    @Autowired FinancialTransactionRepository financialTransactionRepository;
    @Autowired LandlordOrgRepository landlordOrgRepository;
    @Autowired PropertyRepository propertyRepository;
    @Autowired UnitRepository unitRepository;
    @Autowired RenterRepository renterRepository;
    @Autowired LeaseRepository leaseRepository;
    @Autowired AccountRepository accountRepository;

    private UUID tenantId;
    private UUID adminUserId;
    private Property property;
    private Unit unit;
    private Renter renter;
    private Lease lease;
    private PaymentSchedule depositedSchedule;
    private PaymentPenalty openPenalty;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("IT-Tenant-" + UUID.randomUUID());
        org = landlordOrgRepository.save(org);
        this.tenantId = org.getId();
        this.adminUserId = UUID.randomUUID();
        TenantContextHolder.setTenantId(tenantId);

        // Seed accounts: C-01-01 (income, for cheque-bounce reversal),
        // A-02-02 (bank), C-01-02 (other income for penalty receipts).
        seedAccount("C-01-01", "Rental Income", AccountType.INCOME);
        seedAccount("A-02-02", "Bank Accounts", AccountType.ASSET);
        seedAccount("C-01-02", "Other Income", AccountType.INCOME);

        property = new Property();
        property.setNameEn("IT-Property");
        property.setEmirate(Emirate.DUBAI);
        property = propertyRepository.save(property);

        unit = new Unit();
        unit.setProperty(property);
        unit.setUnitNumber("U-1");
        unit = unitRepository.save(unit);

        renter = new Renter();
        renter.setNameEn("IT-Renter");
        // userId left null — renters.user_id has a FK to users; not seeded in
        // this IT. The notification call sites guard on null.
        renter = renterRepository.save(renter);

        lease = new Lease();
        lease.setUnit(unit);
        lease.setRenter(renter);
        lease.setStartDate(LocalDate.now().minusMonths(1));
        lease.setEndDate(LocalDate.now().plusMonths(11));
        lease.setStatus(LeaseStatus.ACTIVE);
        lease.setRentAmount(new BigDecimal("60000"));
        lease.setMonthlyRent(new BigDecimal("5000"));
        lease = leaseRepository.save(lease);

        depositedSchedule = newDepositedSchedule();

        // Mark the schedule failed (BOUNCE) to seed the open penalty.
        paymentScheduleService.markFailed(
                depositedSchedule.getId(), ChequeFailureReason.BOUNCE, "seed");
        openPenalty = paymentPenaltyRepository
                .findByLeaseIdOrderByCreatedAtAsc(lease.getId())
                .get(0);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void partialPay_keepsPenaltyOpen_decreasesOutstanding() {
        UUID penaltyId = openPenalty.getId();

        var input = new PenaltyPaymentService.RecordReceiptInput(
                new BigDecimal("200"), "BANK_TRANSFER", "UTR-1", LocalDate.now(), null);
        PenaltyPayment receipt = penaltyPaymentService.recordReceipt(penaltyId, input, adminUserId);

        assertThat(receipt).isNotNull();
        assertThat(receipt.getAmount()).isEqualByComparingTo("200");
        assertThat(receipt.getPaymentMethod()).isEqualTo("BANK_TRANSFER");
        assertThat(receipt.getPaymentReference()).isEqualTo("UTR-1");
        assertThat(receipt.getFinancialTransactionId()).isNotNull();

        PaymentPenalty reloaded = paymentPenaltyRepository.findById(penaltyId).orElseThrow();
        assertThat(reloaded.getClearedAt()).isNull();
        assertThat(penaltyPaymentService.outstanding(reloaded)).isEqualByComparingTo("300");

        List<FinancialTransaction> penaltyTxns = financialTransactionRepository.findAll().stream()
                .filter(t -> tenantId.equals(t.getTenantId()))
                .filter(t -> t.getDescription() != null && t.getDescription().startsWith("Penalty payment"))
                .toList();
        assertThat(penaltyTxns).hasSize(2); // debit + credit pair
    }

    @Test
    void finalPay_clearsAndPostsSecondFinancialTransaction() {
        UUID penaltyId = openPenalty.getId();

        penaltyPaymentService.recordReceipt(penaltyId, new PenaltyPaymentService.RecordReceiptInput(
                new BigDecimal("200"), "BANK_TRANSFER", "UTR-1", LocalDate.now(), null), adminUserId);
        PenaltyPayment second = penaltyPaymentService.recordReceipt(
                penaltyId,
                new PenaltyPaymentService.RecordReceiptInput(
                        new BigDecimal("300"), "CASH", null, LocalDate.now(), null),
                adminUserId);

        assertThat(second).isNotNull();

        List<PenaltyPayment> rows = penaltyPaymentRepository
                .findByPaymentPenaltyIdOrderByReceivedAtAscCreatedAtAsc(penaltyId);
        assertThat(rows).hasSize(2);
        BigDecimal sum = rows.stream()
                .map(PenaltyPayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("500");

        PaymentPenalty cleared = paymentPenaltyRepository.findById(penaltyId).orElseThrow();
        assertThat(cleared.getClearedAt()).isNotNull();

        // 4 PENALTY_INCOME journal entries (2 pairs).
        long penaltyTxnCount = financialTransactionRepository.findAll().stream()
                .filter(t -> tenantId.equals(t.getTenantId()))
                .filter(t -> t.getDescription() != null && t.getDescription().startsWith("Penalty payment"))
                .count();
        assertThat(penaltyTxnCount).isEqualTo(4);
    }

    @Test
    void accrualAware_clearsWhenSumMeetsAccruedTotal() {
        UUID penaltyId = openPenalty.getId();

        // Simulate 10 days past the grace period — daily accrual would have
        // bumped daysOverdue to 10. currentTotal becomes 500 + 10*25 = 750.
        PaymentPenalty p = paymentPenaltyRepository.findById(penaltyId).orElseThrow();
        p.setDaysOverdue(10);
        paymentPenaltyRepository.saveAndFlush(p);

        // 500 alone doesn't clear an outstanding of 750.
        penaltyPaymentService.recordReceipt(
                penaltyId,
                new PenaltyPaymentService.RecordReceiptInput(
                        new BigDecimal("500"), "BANK_TRANSFER", "UTR-1", LocalDate.now(), null),
                adminUserId);
        PaymentPenalty afterFirst = paymentPenaltyRepository.findById(penaltyId).orElseThrow();
        assertThat(afterFirst.getClearedAt()).isNull();
        assertThat(penaltyPaymentService.outstanding(afterFirst)).isEqualByComparingTo("250");

        // The remaining 250 closes it out.
        penaltyPaymentService.recordReceipt(
                penaltyId,
                new PenaltyPaymentService.RecordReceiptInput(
                        new BigDecimal("250"), "CASH", null, LocalDate.now(), null),
                adminUserId);
        PaymentPenalty afterSecond = paymentPenaltyRepository.findById(penaltyId).orElseThrow();
        assertThat(afterSecond.getClearedAt()).isNotNull();
    }

    @Test
    void overpayRejected() {
        UUID penaltyId = openPenalty.getId();

        long penaltyTxnsBefore = countPenaltyTransactions();

        // Outstanding is 500 — try to pay 600.
        assertThatThrownBy(() ->
                penaltyPaymentService.recordReceipt(
                        penaltyId,
                        new PenaltyPaymentService.RecordReceiptInput(
                                new BigDecimal("600"), "BANK_TRANSFER", "UTR-1", LocalDate.now(), null),
                        adminUserId))
                .isInstanceOf(BusinessRuleViolationException.class);

        // No PenaltyPayment row should have been created.
        List<PenaltyPayment> rows = penaltyPaymentRepository
                .findByPaymentPenaltyIdOrderByReceivedAtAscCreatedAtAsc(penaltyId);
        assertThat(rows).isEmpty();

        // No additional FT rows should have been posted.
        long penaltyTxnsAfter = countPenaltyTransactions();
        assertThat(penaltyTxnsAfter).isEqualTo(penaltyTxnsBefore);

        PaymentPenalty stillOpen = paymentPenaltyRepository.findById(penaltyId).orElseThrow();
        assertThat(stillOpen.getClearedAt()).isNull();
    }

    @Test
    void waivePenalty_setsClearedAtAndIsTerminal() {
        UUID penaltyId = openPenalty.getId();

        PaymentPenalty waived = penaltyService.waivePenalty(penaltyId, "Goodwill", adminUserId);

        assertThat(waived.isWaived()).isTrue();
        assertThat(waived.getClearedAt()).isNotNull();
        assertThat(waived.getWaivedReason()).isEqualTo("Goodwill");

        // Subsequent receipt is rejected — the penalty is terminal.
        assertThatThrownBy(() ->
                penaltyPaymentService.recordReceipt(
                        penaltyId,
                        new PenaltyPaymentService.RecordReceiptInput(
                                new BigDecimal("100"), "CASH", null, LocalDate.now(), null),
                        adminUserId))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    private long countPenaltyTransactions() {
        return financialTransactionRepository.findAll().stream()
                .filter(t -> tenantId.equals(t.getTenantId()))
                .filter(t -> t.getDescription() != null && t.getDescription().startsWith("Penalty payment"))
                .count();
    }

    private void seedAccount(String code, String name, AccountType type) {
        Account a = new Account();
        a.setCode(code);
        a.setName(name);
        a.setAccountType(type);
        accountRepository.save(a);
    }

    private PaymentSchedule newDepositedSchedule() {
        PaymentSchedule s = new PaymentSchedule();
        s.setLease(lease);
        s.setUnit(unit);
        s.setProperty(property);
        s.setInstallmentNumber(1);
        s.setDueDate(LocalDate.now());
        s.setAmount(new BigDecimal("5000"));
        s.setStatus(PaymentStatus.DEPOSITED);
        return paymentScheduleRepository.save(s);
    }
}
