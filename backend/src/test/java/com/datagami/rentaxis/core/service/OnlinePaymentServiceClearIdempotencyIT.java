package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
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
 * Regression coverage for {@link OnlinePaymentService}'s webhook-triggered
 * clear path having NO status precondition at all (unlike the cheque
 * {@code clearPayment}/{@code markFailed} pair, which require DEPOSITED
 * under a pessimistic lock). Razorpay webhooks are commonly retried/
 * redelivered — a duplicate "payment.captured" delivery for an
 * already-cleared schedule must be a safe no-op, not a second posting of
 * the same financial transaction pair.
 */
@SpringBootTest
@Testcontainers
class OnlinePaymentServiceClearIdempotencyIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired OnlinePaymentService onlinePaymentService;
    @Autowired PaymentScheduleRepository paymentScheduleRepository;
    @Autowired LandlordOrgRepository landlordOrgRepository;
    @Autowired PropertyRepository propertyRepository;
    @Autowired UnitRepository unitRepository;
    @Autowired RenterRepository renterRepository;
    @Autowired LeaseRepository leaseRepository;
    @Autowired AccountRepository accountRepository;

    private UUID tenantId;
    private PaymentSchedule onlinePendingSchedule;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Online-IT-Tenant-" + UUID.randomUUID());
        org = landlordOrgRepository.save(org);
        this.tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);

        // A-02-02 "Bank Accounts" is what the real chart-of-accounts seeder
        // creates and what clearPaymentOnline now falls back to. This used to
        // hand-seed A-01-01, a code seedDefaultAccounts never creates.
        seedAccount("A-02-02", "Bank Accounts", AccountType.ASSET);
        seedAccount("C-01-01", "Rental Income", AccountType.INCOME);

        Property property = new Property();
        property.setNameEn("Online-IT-Property");
        property.setEmirate(Emirate.DUBAI);
        property = propertyRepository.save(property);

        Unit unit = new Unit();
        unit.setProperty(property);
        unit.setUnitNumber("U-1");
        unit = unitRepository.save(unit);

        Renter renter = new Renter();
        renter.setNameEn("Online-IT-Renter");
        renter = renterRepository.save(renter);

        Lease lease = new Lease();
        lease.setUnit(unit);
        lease.setRenter(renter);
        lease.setStartDate(LocalDate.now().minusMonths(1));
        lease.setEndDate(LocalDate.now().plusMonths(11));
        lease.setStatus(LeaseStatus.ACTIVE);
        lease.setRentAmount(new BigDecimal("60000"));
        lease.setMonthlyRent(new BigDecimal("5000"));
        lease = leaseRepository.save(lease);

        PaymentSchedule schedule = new PaymentSchedule();
        schedule.setLease(lease);
        schedule.setUnit(unit);
        schedule.setProperty(property);
        schedule.setInstallmentNumber(1);
        schedule.setDueDate(LocalDate.now());
        schedule.setAmount(new BigDecimal("5000"));
        schedule.setStatus(PaymentStatus.ONLINE_PENDING);
        onlinePendingSchedule = paymentScheduleRepository.save(schedule);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void clearPaymentFromWebhook_duplicateDelivery_isIdempotentNoOp() {
        onlinePaymentService.clearPaymentFromWebhook(onlinePendingSchedule);

        PaymentSchedule afterFirst = paymentScheduleRepository.findById(onlinePendingSchedule.getId()).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(PaymentStatus.CLEARED);

        // Simulate a redelivered webhook for the same event — must not
        // double-post, and must not throw (a duplicate delivery is expected
        // gateway behavior, not an application error).
        onlinePaymentService.clearPaymentFromWebhook(afterFirst);

        PaymentSchedule afterSecond = paymentScheduleRepository.findById(onlinePendingSchedule.getId()).orElseThrow();
        assertThat(afterSecond.getStatus())
                .as("duplicate webhook delivery must leave the schedule CLEARED and not throw")
                .isEqualTo(PaymentStatus.CLEARED);
    }

    @Test
    void clearPaymentFromWebhook_scheduleNotOnlinePending_throwsInsteadOfSilentlyClearing() {
        onlinePendingSchedule.setStatus(PaymentStatus.PENDING);
        paymentScheduleRepository.save(onlinePendingSchedule);

        assertThatThrownBy(() -> onlinePaymentService.clearPaymentFromWebhook(onlinePendingSchedule))
                .isInstanceOf(BusinessRuleViolationException.class);

        PaymentSchedule reloaded = paymentScheduleRepository.findById(onlinePendingSchedule.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    private void seedAccount(String code, String name, AccountType type) {
        Account a = new Account();
        a.setCode(code);
        a.setName(name);
        a.setAccountType(type);
        accountRepository.save(a);
    }
}
