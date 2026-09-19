package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.gateway.PaymentGatewayFactory;
import com.datagami.rentaxis.core.service.gateway.PaymentGatewayProvider;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.OnlinePayment;
import com.datagami.rentaxis.domain.entity.PaymentGateway;
import com.datagami.rentaxis.domain.entity.TenantGatewayConfig;
import com.datagami.rentaxis.domain.entity.WebhookLog;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.OnlinePaymentRepository;
import com.datagami.rentaxis.domain.repository.PaymentGatewayRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.TenantGatewayConfigRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.domain.repository.WebhookLogRepository;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the webhook audit trail surviving a failed capture.
 *
 * <p>{@code WebhookService.processRazorpayWebhook} is {@code @Transactional} and,
 * in its {@code finally}, writes a {@link WebhookLog} recording the outcome of the
 * delivery. Clearing the register row can throw — a NOWAIT lock conflict, or a row
 * in a status the register refuses to clear from — which are exactly the anomalies
 * the audit row exists to record. If the clear ran in the handler's own
 * transaction, that throw would mark the shared transaction rollback-only,
 * discarding the very {@code WebhookLog} meant to capture it and turning the
 * handler's clean catch-and-continue into an {@code UnexpectedRollbackException}
 * at commit — so the operator would be left with a 500 and no record of what
 * happened.
 *
 * <p>{@code captureFromWebhook} runs {@code REQUIRES_NEW} so a clearing failure is
 * confined to its own rollback. This test drives the real handler, with a
 * <em>valid</em> signature, at a cheque that is sitting at the bank rather than in
 * a gateway session, and asserts the delivery completes without throwing, the
 * error record persists, and the register and the ledger are untouched.
 */
@SpringBootTest
@Testcontainers
class WebhookAuditDurabilityIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired WebhookService webhookService;
    @Autowired ChequeService chequeService;
    @Autowired LeaseService leaseService;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService generation;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired EncryptionService encryptionService;

    @Autowired WebhookLogRepository webhookLogRepository;
    @Autowired OnlinePaymentRepository onlinePaymentRepository;
    @Autowired PaymentGatewayRepository paymentGatewayRepository;
    @Autowired TenantGatewayConfigRepository tenantGatewayConfigRepository;
    @Autowired ChequeRepository chequeRepository;
    @Autowired LeaseRepository leaseRepository;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean PaymentGatewayFactory gatewayFactory;

    private static final String ORDER_ID = "order_webhook_audit_it";
    private static final LocalDate TODAY = LocalDate.now();

    private LeaseTestFixtures fixtures;
    private UUID chequeId;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);

        PaymentGatewayProvider provider = org.mockito.Mockito.mock(PaymentGatewayProvider.class);
        when(provider.getGatewayCode()).thenReturn("RAZORPAY");
        // The signature is genuine: the point of this test is a failure AFTER the
        // gate, not at it.
        when(provider.verifyWebhookSignature(anyString(), any(), anyString())).thenReturn(true);
        when(gatewayFactory.getProvider(anyString())).thenReturn(provider);

        PostLeaseResponse posted = fixtures.postedLease(
                TODAY.minusMonths(3), TODAY.minusMonths(2), TODAY.plusMonths(10).minusDays(1),
                List.of(line("RENT", "48000")), 4, "500010");
        chequeId = posted.cheques().get(0).id();

        // A register row on a lease that is no longer on the books. The cheque
        // itself is REGISTERED and the money matches, so the capture is applicable
        // as far as OnlinePaymentService can tell — and then the register refuses
        // it outright, because a cheque may not move on an unposted lease. That
        // throw is the anomaly the audit row has to survive.
        //
        // Not a DEPOSITED row any more: since fix round 1 that is a recognised,
        // recorded outcome (CAPTURED_UNAPPLIED) rather than an exception, so it no
        // longer exercises the REQUIRES_NEW rollback boundary at all.
        tx.executeWithoutResult(s -> {
            Lease lease = leaseRepository.findById(posted.lease().getId()).orElseThrow();
            lease.setStatus(LeaseStatus.DRAFT);
            leaseRepository.save(lease);
        });

        PaymentGateway gateway = paymentGatewayRepository.findByCode("RAZORPAY")
                .orElseGet(() -> {
                    PaymentGateway g = new PaymentGateway();
                    g.setCode("RAZORPAY");
                    g.setName("Razorpay");
                    g.setIsActive(true);
                    return paymentGatewayRepository.save(g);
                });

        tx.executeWithoutResult(s -> {
            TenantGatewayConfig config = new TenantGatewayConfig();
            config.setGateway(gateway);
            config.setApiKeyEncrypted(encryptionService.encrypt("test-key"));
            config.setApiSecretEncrypted(encryptionService.encrypt("test-secret"));
            config.setWebhookSecretEncrypted(encryptionService.encrypt("test-webhook-secret"));
            config.setIsActive(true);
            tenantGatewayConfigRepository.save(config);

            OnlinePayment onlinePayment = new OnlinePayment();
            onlinePayment.setCheque(chequeRepository.findById(chequeId).orElseThrow());
            onlinePayment.setGateway(gateway);
            onlinePayment.setGatewayOrderId(ORDER_ID);
            onlinePayment.setAmount(posted.cheques().get(0).amount());
            onlinePayment.setCurrency("AED");
            onlinePayment.setStatus(OnlinePaymentStatus.CREATED);
            onlinePayment.setCreatedAt(Instant.now());
            onlinePaymentRepository.save(onlinePayment);
        });
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @Test
    void processWebhook_whenClearFails_stillPersistsAuditLogAndDoesNotCorruptLedger() {
        String payload = "{"
                + "\"event\":\"payment.captured\","
                + "\"payload\":{\"payment\":{\"entity\":{"
                + "\"order_id\":\"" + ORDER_ID + "\",\"id\":\"pay_webhook_audit_it\"}}}}";

        UUID tenantId = fixtures.tenantId();
        LeaseTestFixtures.clearAuth();

        // The handler must swallow the clearing failure, record it, and return
        // normally — NOT propagate an UnexpectedRollbackException from a
        // rollback-only transaction.
        assertThatCode(() -> webhookService.processRazorpayWebhook(payload, "sig"))
                .doesNotThrowAnyException();

        // The handler clears the tenant context in its finally; re-establish it for
        // the tenant-scoped reads below.
        TenantContextHolder.setTenantId(tenantId);
        LeaseTestFixtures.authenticateAsTenantAdmin();

        List<WebhookLog> logs = tx.execute(s -> webhookLogRepository.findAll().stream()
                .filter(l -> tenantId.equals(l.getTenantId()))
                .toList());
        assertThat(logs)
                .as("the webhook delivery must leave exactly one audit record")
                .hasSize(1);
        WebhookLog log = logs.get(0);
        assertThat(log.getProcessed()).as("a failed capture must not be marked processed").isFalse();
        assertThat(log.getProcessingResult())
                .as("the audit record must capture the failure reason")
                .contains("Error");

        // The register never moved and nothing posted.
        Cheque reloaded = tx.execute(s -> chequeRepository.findById(chequeId).orElseThrow());
        assertThat(reloaded.getStatus()).isEqualTo(ChequeStatus.REGISTERED);
        assertThat(reloaded.getCrtJournalId()).isNull();
        Long crts = jdbc.queryForObject(
                "select count(*) from journal_entries where tenant_id = ? and doc_type = 'CRT' and source_id = ?",
                Long.class, tenantId, chequeId);
        assertThat(crts).isZero();
    }
}
