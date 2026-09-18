package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.OnlinePayment;
import com.datagami.rentaxis.domain.entity.PaymentGateway;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.TenantGatewayConfig;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.WebhookLog;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.OnlinePaymentRepository;
import com.datagami.rentaxis.domain.repository.PaymentGatewayRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.TenantGatewayConfigRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.WebhookLogRepository;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression coverage for the webhook audit trail surviving a failed
 * schedule-clear.
 *
 * <p>{@code WebhookService.processRazorpayWebhook} is {@code @Transactional}
 * and, in its {@code finally}, writes a {@link WebhookLog} recording the
 * outcome of the delivery. {@code OnlinePaymentService.clearPaymentOnline}
 * can throw (a NOWAIT lock conflict, or a schedule that is not
 * ONLINE_PENDING) — exactly the anomalies the audit row exists to record. If
 * the clear ran in the handler's own transaction, that throw would mark the
 * shared transaction rollback-only, discarding the very {@code WebhookLog}
 * meant to capture it and turning the handler's clean catch-and-continue into
 * an {@code UnexpectedRollbackException} at commit — so the operator would be
 * left with a 500 and no record of what happened.
 *
 * <p>{@code clearPaymentFromWebhook} runs {@code REQUIRES_NEW} so a clearing
 * failure is confined to its own rollback. This test drives the real handler
 * against a schedule in an unexpected status and asserts the delivery
 * completes without throwing, the {@code WebhookLog} error record persists,
 * and the ledger is untouched.
 */
@SpringBootTest
@Testcontainers
class WebhookAuditDurabilityIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired WebhookService webhookService;
    @Autowired WebhookLogRepository webhookLogRepository;
    @Autowired OnlinePaymentRepository onlinePaymentRepository;
    @Autowired PaymentGatewayRepository paymentGatewayRepository;
    @Autowired TenantGatewayConfigRepository tenantGatewayConfigRepository;
    @Autowired PaymentScheduleRepository paymentScheduleRepository;
    @Autowired LandlordOrgRepository landlordOrgRepository;
    @Autowired PropertyRepository propertyRepository;
    @Autowired UnitRepository unitRepository;
    @Autowired RenterRepository renterRepository;
    @Autowired LeaseRepository leaseRepository;
    @Autowired AccountRepository accountRepository;

    private UUID tenantId;
    private PaymentSchedule schedule;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Webhook-IT-Tenant-" + UUID.randomUUID());
        org = landlordOrgRepository.save(org);
        this.tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);

        // A-02-02 "Bank Accounts" is what the real chart-of-accounts seeder
        // creates and what clearPaymentOnline now falls back to. This used to
        // hand-seed A-01-01, a code seedDefaultAccounts never creates.
        seedAccount("A-02-02", "Bank Accounts", AccountType.ASSET);
        seedAccount("C-01-01", "Rental Income", AccountType.INCOME);

        Property property = new Property();
        property.setNameEn("Webhook-IT-Property");
        property.setEmirate(Emirate.DUBAI);
        property = propertyRepository.save(property);

        Unit unit = new Unit();
        unit.setProperty(property);
        unit.setUnitNumber("U-1");
        unit = unitRepository.save(unit);

        Renter renter = new Renter();
        renter.setNameEn("Webhook-IT-Renter");
        renter = renterRepository.save(renter);

        Lease lease = new Lease();
        lease.setUnit(unit);
        lease.setRenter(renter);
        lease.setStartDate(LocalDate.now().minusMonths(1));
        lease.setEndDate(LocalDate.now().plusMonths(11));
        lease.setStatus(LeaseStatus.ACTIVE);
        lease.setRentAmount(new BigDecimal("60000"));
        lease = leaseRepository.save(lease);

        // Schedule in an UNEXPECTED status: not ONLINE_PENDING (so the clear
        // will not proceed) and not CLEARED (so it is not treated as an
        // idempotent duplicate) — clearPaymentOnline will throw.
        PaymentSchedule s = new PaymentSchedule();
        s.setLease(lease);
        s.setUnit(unit);
        s.setProperty(property);
        s.setInstallmentNumber(1);
        s.setDueDate(LocalDate.now());
        s.setAmount(new BigDecimal("5000"));
        s.setStatus(PaymentStatus.PENDING);
        schedule = paymentScheduleRepository.save(s);

        // RAZORPAY is a global (non-tenant) gateway seeded by Liquibase and its
        // code is UNIQUE — reuse the existing row rather than inserting a
        // duplicate. Fall back to creating one only if the seed is absent.
        PaymentGateway gateway = paymentGatewayRepository.findByCode("RAZORPAY")
                .orElseGet(() -> {
                    PaymentGateway g = new PaymentGateway();
                    g.setCode("RAZORPAY");
                    g.setName("Razorpay");
                    g.setIsActive(true);
                    return paymentGatewayRepository.save(g);
                });

        TenantGatewayConfig config = new TenantGatewayConfig();
        config.setGateway(gateway);
        config.setApiKeyEncrypted("test-key");
        config.setApiSecretEncrypted("test-secret");
        config.setWebhookSecretEncrypted(null);
        config.setIsActive(true);
        tenantGatewayConfigRepository.save(config);

        OnlinePayment onlinePayment = new OnlinePayment();
        onlinePayment.setPaymentSchedule(schedule);
        onlinePayment.setGateway(gateway);
        onlinePayment.setGatewayOrderId("order_webhook_audit_it");
        onlinePayment.setAmount(new BigDecimal("5000"));
        onlinePayment.setCurrency("INR");
        onlinePayment.setStatus(OnlinePaymentStatus.CREATED);
        onlinePayment.setCreatedAt(Instant.now());
        onlinePaymentRepository.save(onlinePayment);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void processWebhook_whenClearFails_stillPersistsAuditLogAndDoesNotCorruptLedger() {
        String payload = "{"
                + "\"event\":\"payment.captured\","
                + "\"payload\":{\"payment\":{\"entity\":{"
                + "\"order_id\":\"order_webhook_audit_it\",\"id\":\"pay_webhook_audit_it\"}}}}";

        // The handler must swallow the clearing failure, record it, and return
        // normally — NOT propagate an UnexpectedRollbackException from a
        // rollback-only transaction.
        assertThatCode(() -> webhookService.processRazorpayWebhook(payload, null))
                .doesNotThrowAnyException();

        // The handler clears the tenant context in its finally; re-establish
        // it for the tenant-scoped reads below.
        TenantContextHolder.setTenantId(tenantId);

        // The audit row for this delivery must survive — this is the whole
        // point: the anomaly is recorded, not lost with a rolled-back tx.
        List<WebhookLog> logs = webhookLogRepository.findAll().stream()
                .filter(l -> tenantId.equals(l.getTenantId()))
                .toList();
        assertThat(logs)
                .as("the webhook delivery must leave exactly one audit record")
                .hasSize(1);
        WebhookLog log = logs.get(0);
        assertThat(log.getProcessed()).as("a failed clear must not be marked processed").isFalse();
        assertThat(log.getProcessingResult())
                .as("the audit record must capture the failure reason")
                .contains("Error");

        // The ledger is untouched: the schedule never left its original status.
        PaymentSchedule reloaded = paymentScheduleRepository.findById(schedule.getId()).orElseThrow();
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
