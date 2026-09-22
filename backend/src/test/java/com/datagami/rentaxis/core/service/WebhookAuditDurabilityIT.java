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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @Autowired OnlinePaymentService onlinePaymentService;
    /** A second connection, for holding the cheque row lock a concurrent caller would. */
    @Autowired DataSource dataSource;

    @MockitoBean PaymentGatewayFactory gatewayFactory;

    private static final LocalDate TODAY = LocalDate.now();

    /**
     * Per test, not a constant: the handler looks a delivery up by order id through
     * an unfiltered native query, so two methods sharing one id make that lookup
     * ambiguous and every case in here would be measuring that instead.
     */
    private String orderId;

    private LeaseTestFixtures fixtures;
    private UUID chequeId;
    private UUID leaseId;

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
        leaseId = posted.lease().getId();
        orderId = "order_webhook_audit_it_" + UUID.randomUUID();

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
            onlinePayment.setGatewayOrderId(orderId);
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

    /**
     * A capture that throws is money the gateway took, so it lands on the refund
     * list — it is never swallowed with a 200 and nothing to show for it.
     *
     * <p>This used to assert the opposite: "completes without throwing",
     * {@code processed = false}, result "Error: …", and a payment still sitting at
     * CREATED. Every one of those was true and none of them was enough. Razorpay
     * treats 2xx as delivered and stops; the renter was charged, the payment was not
     * on {@code /online-payments/unapplied}, the cheque had not moved, no CRT
     * existed, and with the modal closed the {@code /verify} callback would never
     * come. The only trace was a {@code webhook_logs} row nobody has a screen
     * for.</p>
     *
     * <p>The failure here is deterministic — the lease was reverted to DRAFT, so the
     * register refuses to move the row and will refuse it again tomorrow — so 200 is
     * still the right answer to the gateway. What changes is that the capture is
     * recorded as {@code CAPTURED_UNAPPLIED} with the reason, in its own
     * transaction, which is where finance finds the refund they owe.</p>
     */
    @Test
    void processWebhook_whenPostingThrows_recordsTheCaptureAsARefundOwed() {
        String payload = "{"
                + "\"event\":\"payment.captured\","
                + "\"payload\":{\"payment\":{\"entity\":{"
                + "\"order_id\":\"" + orderId + "\",\"id\":\"pay_webhook_audit_it\"}}}}";

        UUID tenantId = fixtures.tenantId();
        LeaseTestFixtures.clearAuth();

        // A deterministic refusal is still a 200: redelivering it forever would
        // neither un-draft the lease nor issue the refund.
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
        assertThat(log.getProcessed())
                .as("the outcome is recorded and final; there is nothing to redeliver")
                .isTrue();
        assertThat(log.getProcessingResult())
                .as("the audit record must name the refusal")
                .contains("Captured but not applied")
                .contains("could not be posted");

        // The money is findable: on finance's refund list, with the reason on it.
        OnlinePayment payment = tx.execute(s ->
                onlinePaymentRepository.findByGatewayOrderId(orderId).orElseThrow());
        assertThat(payment.getStatus())
                .as("money the gateway took must never be left at CREATED")
                .isEqualTo(OnlinePaymentStatus.CAPTURED_UNAPPLIED);
        assertThat(payment.getFailureReason()).contains("could not be posted");
        assertThat(payment.getGatewayPaymentId()).isEqualTo("pay_webhook_audit_it");
        long owed = tx.execute(s -> onlinePaymentService.unappliedTotals().count());
        assertThat(owed).as("and on the worklist finance actually opens").isEqualTo(1L);

        // The register never moved and nothing posted.
        Cheque reloaded = tx.execute(s -> chequeRepository.findById(chequeId).orElseThrow());
        assertThat(reloaded.getStatus()).isEqualTo(ChequeStatus.REGISTERED);
        assertThat(reloaded.getCrtJournalId()).isNull();
        Long crts = jdbc.queryForObject(
                "select count(*) from journal_entries where tenant_id = ? and doc_type = 'CRT' and source_id = ?",
                Long.class, tenantId, chequeId);
        assertThat(crts).isZero();
    }

    /**
     * A redelivery of the same unpostable capture restates nothing.
     *
     * <p>The reason and the capture time are the first delivery's. Rewriting them on
     * every retry would move the row to the top of a worklist ordered by capture
     * time for as long as the gateway keeps trying.
     */
    @Test
    void aRedeliveredUnpostableCaptureDoesNotRestateTheRecord() throws Exception {
        String payload = "{"
                + "\"event\":\"payment.captured\","
                + "\"payload\":{\"payment\":{\"entity\":{"
                + "\"order_id\":\"" + orderId + "\",\"id\":\"pay_webhook_audit_it\"}}}}";
        UUID tenantId = fixtures.tenantId();
        LeaseTestFixtures.clearAuth();

        webhookService.processRazorpayWebhook(payload, "sig");
        TenantContextHolder.setTenantId(tenantId);
        OnlinePayment first = tx.execute(s ->
                onlinePaymentRepository.findByGatewayOrderId(orderId).orElseThrow());
        Thread.sleep(20);

        LeaseTestFixtures.clearAuth();
        webhookService.processRazorpayWebhook(payload, "sig");
        TenantContextHolder.setTenantId(tenantId);

        OnlinePayment after = tx.execute(s ->
                onlinePaymentRepository.findByGatewayOrderId(orderId).orElseThrow());
        assertThat(after.getStatus()).isEqualTo(OnlinePaymentStatus.CAPTURED_UNAPPLIED);
        assertThat(after.getUpdatedAt()).isEqualTo(first.getUpdatedAt());
        assertThat(after.getFailureReason()).isEqualTo(first.getFailureReason());
    }

    /**
     * A <em>transient</em> failure gets the opposite answer: the delivery is refused
     * so the gateway redelivers, and the audit row survives the rollback that goes
     * with it.
     *
     * <p>The cheque's NOWAIT lock lost to a concurrent caller is the case the
     * capture's own javadoc describes — the renter closing the modal at the moment
     * Razorpay captures. Recording a refund for that would be wrong: the same
     * delivery a second later posts cleanly. Razorpay backs off for about a day,
     * which is far longer than any lock conflict.</p>
     *
     * <p>The audit row is the reason this is an assertion and not a comment: the
     * rethrow rolls the handler's transaction back, so a log written through the
     * handler's own session would vanish with it — losing the record of exactly the
     * delivery an operator would go looking for.</p>
     */
    @Test
    void processWebhook_whenTheFailureIsTransient_refusesTheDeliveryAndKeepsTheAuditRow() throws Exception {
        // Put the lease back on the books: the refusal under test is the lock, not
        // the lease's status.
        tx.executeWithoutResult(s -> {
            Lease lease = leaseRepository.findById(leaseId).orElseThrow();
            lease.setStatus(LeaseStatus.ACTIVE);
            leaseRepository.save(lease);
        });
        String payload = "{"
                + "\"event\":\"payment.captured\","
                + "\"payload\":{\"payment\":{\"entity\":{"
                + "\"order_id\":\"" + orderId + "\",\"id\":\"pay_webhook_audit_it\"}}}}";
        UUID tenantId = fixtures.tenantId();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "select id from cheques where id = ? for update")) {
                lock.setObject(1, chequeId);
                lock.executeQuery();
            }

            LeaseTestFixtures.clearAuth();
            assertThatThrownBy(() -> webhookService.processRazorpayWebhook(payload, "sig"))
                    .as("a lock conflict must not be answered 200: the same delivery will post")
                    .isInstanceOf(RuntimeException.class);

            holder.rollback();
        }

        TenantContextHolder.setTenantId(tenantId);
        LeaseTestFixtures.authenticateAsTenantAdmin();

        List<WebhookLog> logs = tx.execute(s -> webhookLogRepository.findAll().stream()
                .filter(l -> tenantId.equals(l.getTenantId()))
                .toList());
        assertThat(logs)
                .as("the audit row must survive the rollback the rethrow causes")
                .hasSize(1);
        assertThat(logs.get(0).getProcessed()).isFalse();
        assertThat(logs.get(0).getProcessingResult()).contains("redeliver");

        // Nothing was recorded as a refund, because nothing is owed: the money will
        // post when the gateway tries again.
        OnlinePayment payment = tx.execute(s ->
                onlinePaymentRepository.findByGatewayOrderId(orderId).orElseThrow());
        assertThat(payment.getStatus()).isEqualTo(OnlinePaymentStatus.CREATED);
        long owed = tx.execute(s -> onlinePaymentService.unappliedTotals().count());
        assertThat(owed).isZero();
    }
}
