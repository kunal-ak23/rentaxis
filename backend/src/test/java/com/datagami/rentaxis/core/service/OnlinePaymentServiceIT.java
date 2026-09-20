package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateOrderResponseDTO;
import com.datagami.rentaxis.api.dto.RenterChequeDTO;
import com.datagami.rentaxis.api.dto.VerifyPaymentRequestDTO;
import com.datagami.rentaxis.api.dto.VerifyPaymentResponseDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.api.dto.penalty.PenaltyAssessmentDTO;
import com.datagami.rentaxis.api.dto.penalty.ProposePenaltyRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.gateway.PaymentGatewayFactory;
import com.datagami.rentaxis.core.service.gateway.PaymentGatewayProvider;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.penalty.PenaltyAssessmentService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.OnlinePayment;
import com.datagami.rentaxis.domain.entity.PaymentGateway;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.TenantGatewayConfig;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.OnlinePaymentRepository;
import com.datagami.rentaxis.domain.repository.PaymentGatewayRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Paying a register row through the gateway, end to end, against a real database
 * (spec §9.3).
 *
 * <p>Successor to {@code OnlinePaymentServiceClearIdempotencyIT}, which asserted
 * that a duplicate webhook left a status column unchanged — true of a method that
 * did nothing at all. The question that matters is <b>how many {@code CRT}s are in
 * the ledger afterwards</b>, and it is asked here for every order the two reports
 * of one capture can arrive in.</p>
 *
 * <p>Razorpay is faked at {@link PaymentGatewayFactory}, not at the provider list:
 * the factory picks the first provider whose code matches, so registering a second
 * RAZORPAY bean alongside the real one would leave which of them wins up to bean
 * ordering.</p>
 *
 * <p><b>Transactions.</b> {@code TenantAspect} only enables the Hibernate tenant
 * filter inside one, so every read-back goes through {@link #tx}.</p>
 */
@SpringBootTest
@Testcontainers
class OnlinePaymentServiceIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired OnlinePaymentService onlinePayments;
    @Autowired WebhookService webhookService;
    @Autowired RentReceiptService receipts;
    @Autowired ChequeService chequeService;
    @Autowired PenaltyAssessmentService penalties;
    @Autowired TenantGatewayConfigService gatewayConfigService;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService generation;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired AccountResolver resolver;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired EncryptionService encryptionService;
    @Autowired LeaseAccessPolicy leaseAccessPolicy;

    @Autowired ChequeRepository chequeRepo;
    @Autowired OnlinePaymentRepository onlinePaymentRepo;
    @Autowired PaymentGatewayRepository gatewayRepo;
    @Autowired TenantGatewayConfigRepository configRepo;
    @Autowired RentCollectionSettingsRepository settingsRepo;
    @Autowired AccountRepository accountRepo;
    @Autowired JournalLineRepository journalLines;
    @Autowired WebhookLogRepository webhookLogs;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean PaymentGatewayFactory gatewayFactory;

    /** The faked gateway. Each test says what it answers. */
    private PaymentGatewayProvider provider;

    private LeaseTestFixtures fixtures;
    private Account settlementAccount;

    /** Everything is dated relative to today so the first instalment is actually due. */
    private static final LocalDate TODAY = LocalDate.now();
    private static final LocalDate CONTRACT_DATE = TODAY.minusMonths(3);
    private static final LocalDate START = TODAY.minusMonths(2);
    private static final LocalDate END = START.plusYears(1).minusDays(1);

    /** One instalment of the fixture lease: 48,000 over four. */
    private static final BigDecimal INSTALMENT = new BigDecimal("12000");

    private static final String PAYMENT_ID = "pay_IT_0001";
    private static final String SIGNATURE = "sig_IT_0001";
    private static final String WEBHOOK_SECRET = "whsec_it";

    /**
     * The order the faked gateway hands back. Mutable because the idempotency test
     * runs three whole payments inside one method, and {@code gateway_order_id} is
     * what both capture reports look a payment up by — reusing one id would make
     * the second lookup ambiguous rather than idempotent.
     */
    private String orderId;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);

        orderId = "order_IT_" + UUID.randomUUID();
        provider = org.mockito.Mockito.mock(PaymentGatewayProvider.class);
        when(provider.getGatewayCode()).thenReturn("RAZORPAY");
        when(gatewayFactory.getProvider(anyString())).thenReturn(provider);
        when(provider.createOrder(any(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> order(orderId, inv.getArgument(0)));

        settlementAccount = tx.execute(s -> accountService.createLeaf(
                "Razorpay settlement", accountService.getAccountByCode("A-02-02"), null));
        seedGateway();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ------------------------------------------------------------------
    // create order
    // ------------------------------------------------------------------

    @Test
    void createOrderOnDueRegisteredChequeMovesItToOnlinePending() {
        UUID chequeId = firstCheque();

        CreateOrderResponseDTO response = onlinePayments.createOrder(chequeId);

        assertThat(response.getOrderId()).isEqualTo(orderId);
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.ONLINE_PENDING);
        // An authorisation is not money: nothing may post until the gateway captures.
        assertThat(crtCount(chequeId)).isZero();

        OnlinePayment payment = onlyPayment();
        assertThat(payment.getStatus()).isEqualTo(OnlinePaymentStatus.CREATED);
        UUID paidRow = tx.execute(s -> onlinePaymentRepo.findById(payment.getId()).orElseThrow()
                .getCheque().getId());
        assertThat(paidRow).isEqualTo(chequeId);
        assertThat(payment.getAmount()).isEqualByComparingTo("12000");
        // Penalties are their own register rows now; the rent order is the rent.
        assertThat(payment.getPenaltyAmount()).isEqualByComparingTo("0");
    }

    /**
     * A gateway that cannot take AED is refused, not silently charged in INR.
     *
     * <p>The register keeps its money in AED and {@code capture} posts the row's AED
     * amount. The old fallback raised the order for the same <em>number</em> in
     * rupees, so a 12,000 AED instalment was charged as ₹12,000 and then booked as
     * collected in full — money that was never taken, settled in the ledger.</p>
     */
    @Test
    void anOrderIsRefusedWhenTheGatewayCannotTakeAed() {
        UUID chequeId = firstCheque();
        String before = tx.execute(s -> gatewayRepo.findByCode("RAZORPAY").orElseThrow().getSupportedCurrencies());
        tx.executeWithoutResult(s -> {
            PaymentGateway g = gatewayRepo.findByCode("RAZORPAY").orElseThrow();
            g.setSupportedCurrencies("INR,USD");
            gatewayRepo.save(g);
        });
        try {
            assertThatThrownBy(() -> onlinePayments.createOrder(chequeId))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("AED");

            assertThat(reread(chequeId).getStatus())
                    .as("a refusal leaves the row on the register, not stranded in a session")
                    .isEqualTo(ChequeStatus.REGISTERED);
            assertThat(allPayments()).isEmpty();
        } finally {
            // payment_gateways is a shared catalogue table, not tenant-scoped.
            tx.executeWithoutResult(s -> {
                PaymentGateway g = gatewayRepo.findByCode("RAZORPAY").orElseThrow();
                g.setSupportedCurrencies(before);
                gatewayRepo.save(g);
            });
        }
    }

    @Test
    void createOrderRefusesAnInstalmentThatIsNotDueYet() {
        List<ChequeDTO> register = registerRows();
        ChequeDTO future = register.get(register.size() - 1);
        assertThat(future.chequeDate()).isAfter(TODAY);

        assertThatThrownBy(() -> onlinePayments.createOrder(future.id()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("not due yet");
        assertThat(reread(future.id()).getStatus()).isEqualTo(ChequeStatus.REGISTERED);
    }

    // ------------------------------------------------------------------
    // the bounced row's door into the gateway
    // ------------------------------------------------------------------

    @Test
    void bouncedChequePaidOnlineGetsOnlineReplacementRow() {
        UUID bouncedId = bounceFirstCheque();

        CreateOrderResponseDTO response = onlinePayments.createOrder(bouncedId);
        assertThat(response.getOrderId()).isEqualTo(orderId);

        Cheque bounced = reread(bouncedId);
        assertThat(bounced.getStatus()).isEqualTo(ChequeStatus.REPLACED);

        Cheque replacement = tx.execute(s -> {
            Cheque b = chequeRepo.findById(bouncedId).orElseThrow();
            return chequeRepo.findById(b.getReplacedBy().getId()).orElseThrow();
        });
        assertThat(replacement.getMode()).isEqualTo(ChequeMode.ONLINE);
        assertThat(replacement.getStatus()).isEqualTo(ChequeStatus.ONLINE_PENDING);
        assertThat(replacement.getAmount()).isEqualByComparingTo(bounced.getAmount());
        // Its own PDR — which is the PDC balance the capture will clear.
        assertThat(replacement.getPdrJournalId()).isNotNull();

        // The order and the payment are about the replacement, never the bounce.
        assertThat(paymentsFor(replacement.getId())).hasSize(1);
        assertThat(paymentsFor(bouncedId)).isEmpty();
    }

    /**
     * The gateway refusing the order must leave nothing behind. Without one
     * transaction around the replacement, the PDR and the order call, a Razorpay
     * outage would supersede a bounced cheque with a row nobody could ever pay.
     */
    @Test
    void aFailedGatewayOrderLeavesTheBouncedChequeBounced() {
        UUID bouncedId = bounceFirstCheque();
        // doThrow, not when(...).thenThrow: when() calls the method for real, and
        // the setUp stub would answer it with a null amount before the new stubbing
        // was ever recorded.
        doThrow(new RuntimeException("Razorpay is down")).when(provider)
                .createOrder(any(), anyString(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> onlinePayments.createOrder(bouncedId))
                .isInstanceOf(RuntimeException.class);

        Cheque bounced = reread(bouncedId);
        assertThat(bounced.getStatus()).isEqualTo(ChequeStatus.BOUNCED);
        assertThat(bounced.getReplacedBy()).isNull();
        List<Cheque> register = tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId()));
        assertThat(register).hasSize(4);
        assertThat(allPayments()).isEmpty();
    }

    // ------------------------------------------------------------------
    // capture
    // ------------------------------------------------------------------

    @Test
    void captureClearsViaCrtToSettlementAccount() {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);
        when(provider.verifyPaymentSignature(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        VerifyPaymentResponseDTO response = onlinePayments.verifyPayment(verifyRequest());

        assertThat(response.isSuccess()).isTrue();
        Cheque cleared = reread(chequeId);
        assertThat(cleared.getStatus()).isEqualTo(ChequeStatus.CLEARED);
        assertThat(cleared.getClearedAt()).isEqualTo(TODAY);
        assertThat(cleared.getCrtJournalId()).isNotNull();
        assertThat(onlyPayment().getStatus()).isEqualTo(OnlinePaymentStatus.CAPTURED);

        // Dr the gateway's nominated bank leaf / Cr PDC receivable — not the
        // property's BANK role, which is a different account entirely.
        List<JournalLine> lines = tx.execute(s -> journalLines
                .findByEntry_IdOrderByLineNoAsc(cleared.getCrtJournalId()));
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).getAccountId()).isEqualTo(settlementAccount.getId());
        assertThat(lines.get(0).getDebit()).isEqualByComparingTo("12000");
        assertThat(lines.get(1).getAccountId()).isEqualTo(leaf(AccountRole.PDC_RECEIVABLE).getId());
        assertThat(lines.get(1).getCredit()).isEqualByComparingTo("12000");
        assertThat(settlementAccount.getId()).isNotEqualTo(leaf(AccountRole.BANK).getId());
    }

    /**
     * The successor assertion to the old idempotency IT: whichever way the two
     * reports of one capture arrive, the ledger holds exactly one {@code CRT}.
     */
    @Test
    void everyOrderOfTheTwoCaptureReportsLeavesExactlyOneCrt() {
        assertExactlyOneCrt("webhook twice", () -> {
            webhookCaptured();
            webhookCaptured();
        });
        assertExactlyOneCrt("verify then webhook", () -> {
            verifyCaptured();
            webhookCaptured();
        });
        assertExactlyOneCrt("webhook then verify", () -> {
            webhookCaptured();
            verifyCaptured();
        });
    }

    /**
     * The renter closed the modal, the row went back on the register — and the
     * money was taken anyway. Without re-pending the row, {@code clearOnline}
     * refuses a REGISTERED cheque and captured money is never posted.
     */
    @Test
    void aCaptureThatArrivesAfterTheRenterCancelledStillClearsTheRow() {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);
        onlinePayments.cancelPendingOnlinePayment(chequeId);
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.REGISTERED);

        webhookCaptured();

        Cheque cleared = reread(chequeId);
        assertThat(cleared.getStatus())
                .as("money the gateway took must never be left unposted")
                .isEqualTo(ChequeStatus.CLEARED);
        assertThat(crtCount(chequeId)).isEqualTo(1L);
        assertThat(onlyPayment().getStatus()).isEqualTo(OnlinePaymentStatus.CAPTURED);
    }

    // ------------------------------------------------------------------
    // the webhook's signature is the only guard in front of the register
    // ------------------------------------------------------------------

    @Test
    void anUnverifiedWebhookPostsNothing() {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);
        webhookDelivers(capturedPayload(), "forged");

        assertThat(reread(chequeId).getStatus())
                .as("a payload the tenant's secret does not vouch for may not move the register")
                .isEqualTo(ChequeStatus.ONLINE_PENDING);
        assertThat(crtCount(chequeId)).isZero();
        assertThat(onlyPayment().getStatus()).isEqualTo(OnlinePaymentStatus.CREATED);
        assertThat(lastWebhookResult()).contains("signature verification failed");
    }

    /**
     * A tenant with no webhook secret cannot have its deliveries verified at all,
     * so they are refused. Reading "no secret" as "no check needed" would leave the
     * register wide open on exactly the tenants nobody finished configuring.
     */
    @Test
    void aWebhookForATenantWithNoSecretPostsNothing() {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);
        tx.executeWithoutResult(s -> {
            TenantGatewayConfig config = configRepo.findByIsActiveTrueOrderByCreatedAtAscIdAsc().get(0);
            config.setWebhookSecretEncrypted(null);
            configRepo.save(config);
        });

        webhookDelivers(capturedPayload(), signatureFor(WEBHOOK_SECRET));

        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.ONLINE_PENDING);
        assertThat(crtCount(chequeId)).isZero();
        assertThat(lastWebhookResult()).contains("No webhook secret configured");
    }

    // ------------------------------------------------------------------
    // the ways a session ends without money
    // ------------------------------------------------------------------

    @Test
    void cancelRevertsToRegistered() {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);

        onlinePayments.cancelPendingOnlinePayment(chequeId);

        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.REGISTERED);
        assertThat(crtCount(chequeId)).isZero();
        assertThat(onlyPayment().getStatus()).isEqualTo(OnlinePaymentStatus.FAILED);
        // Cancelled twice by a renter clicking twice: a no-op, not a refusal.
        onlinePayments.cancelPendingOnlinePayment(chequeId);
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.REGISTERED);
    }

    // ------------------------------------------------------------------
    // an abandoned checkout is not a dead end
    // ------------------------------------------------------------------

    /**
     * The renter closed the browser <em>tab</em>, not the modal.
     *
     * <p>Nothing tells us: Razorpay sends no {@code payment.failed} for an order
     * nobody finished, and there is no expiry sweep. The row sat in
     * {@code ONLINE_PENDING} for ever — {@code createOrder} refused a non-REGISTERED
     * row, so the renter could not retry, and every staff action on the register
     * refuses the status, so nobody could bank the paper, cancel it or hand it back
     * either. The renter may now simply start again; the stale session is
     * superseded so only one order is live on the row.</p>
     */
    @Test
    void aRenterMayRestartACheckoutTheyAbandoned() {
        UUID chequeId = firstCheque();
        String abandoned = startOrder(chequeId);
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.ONLINE_PENDING);

        String retry = startOrder(chequeId);

        assertThat(retry).isNotEqualTo(abandoned);
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.ONLINE_PENDING);
        assertThat(paymentByOrder(abandoned).getStatus())
                .as("only one checkout may be live on a row")
                .isEqualTo(OnlinePaymentStatus.FAILED);
        assertThat(paymentByOrder(abandoned).getFailureReason()).contains("Superseded");
        assertThat(paymentByOrder(retry).getStatus()).isEqualTo(OnlinePaymentStatus.CREATED);
        assertThat(paymentByOrder(retry).getAmount()).isEqualByComparingTo("12000");

        // And the retry settles the instalment exactly once.
        webhookCaptured();
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.CLEARED);
        assertThat(crtCount(chequeId)).isEqualTo(1L);
    }

    /**
     * FAILED is deliberately not terminal, so superseding a session cannot lose a
     * capture that was genuinely made through it: the late capture on the abandoned
     * order still posts, and the newer order is then the one that is refunded.
     */
    @Test
    void aSupersededCheckoutThatCapturesLateIsStillApplied() {
        UUID chequeId = firstCheque();
        String abandoned = startOrder(chequeId);
        String retry = startOrder(chequeId);

        orderId = abandoned;
        webhookCaptured();

        assertThat(paymentByOrder(abandoned).getStatus()).isEqualTo(OnlinePaymentStatus.CAPTURED);
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.CLEARED);
        assertThat(crtCount(chequeId)).isEqualTo(1L);

        orderId = retry;
        webhookCaptured();
        assertThat(paymentByOrder(retry).getStatus())
                .as("two payments, one instalment: the second is a refund, not a second CRT")
                .isEqualTo(OnlinePaymentStatus.CAPTURED_UNAPPLIED);
        assertThat(crtCount(chequeId)).isEqualTo(1L);
    }

    /** A renter cannot open a fresh checkout over money the gateway has already taken. */
    @Test
    void aRestartIsRefusedOnceTheGatewayHasTakenTheMoney() {
        UUID chequeId = firstCheque();
        startOrder(chequeId);
        webhookDelivers(capturedPayload(900_000L, "AED"), signatureFor(WEBHOOK_SECRET));
        assertThat(onlyPayment().getStatus()).isEqualTo(OnlinePaymentStatus.CAPTURED_UNAPPLIED);
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.ONLINE_PENDING);

        assertThatThrownBy(() -> onlinePayments.createOrder(chequeId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already been taken by the gateway");
        assertThat(allPayments()).hasSize(1);
    }

    /**
     * Staff can hand an abandoned session back to the register, which is what makes
     * the instalment workable again — bankable, cancellable, returnable.
     */
    @Test
    void staffCanReleaseAnAbandonedOnlineSessionBackToTheRegister() {
        UUID chequeId = firstCheque();
        String abandoned = startOrder(chequeId);

        ChequeDTO released = onlinePayments.releaseOnlinePending(chequeId);

        assertThat(released.status()).isEqualTo(ChequeStatus.REGISTERED);
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.REGISTERED);
        assertThat(crtCount(chequeId)).isZero();
        assertThat(paymentByOrder(abandoned).getStatus()).isEqualTo(OnlinePaymentStatus.FAILED);
        assertThat(paymentByOrder(abandoned).getFailureReason()).contains("Released by staff");

        // The point of releasing it: the register can work the row again.
        chequeService.deposit(chequeId, ChequeActionRequest.on(TODAY));
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.DEPOSITED);
    }

    /**
     * Once the gateway has taken money, what is owed is a refund and not a release:
     * releasing would invite a second collection of the same instalment and drop the
     * capture off finance's worklist.
     */
    @Test
    void releasingIsRefusedOnceTheGatewayHasTakenTheMoney() {
        UUID chequeId = firstCheque();
        startOrder(chequeId);
        webhookDelivers(capturedPayload(900_000L, "AED"), signatureFor(WEBHOOK_SECRET));

        assertThatThrownBy(() -> onlinePayments.releaseOnlinePending(chequeId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("refund");
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.ONLINE_PENDING);
        assertThat(onlyPayment().getStatus()).isEqualTo(OnlinePaymentStatus.CAPTURED_UNAPPLIED);
        assertThat(onlinePayments.unappliedTotals().count()).isEqualTo(1L);
    }

    /** Releasing is staff work: a renter may not reach it even for their own row. */
    @Test
    void aRenterCannotReleaseTheirOwnPendingRow() {
        UUID chequeId = firstCheque();
        startOrder(chequeId);
        asRenter(fixtures.renter());

        assertThatThrownBy(() -> onlinePayments.releaseOnlinePending(chequeId))
                .isInstanceOf(NotFoundException.class);

        LeaseTestFixtures.authenticateAsTenantAdmin();
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.ONLINE_PENDING);
    }

    /** A row that is not in a session is refused in the register's own words. */
    @Test
    void releasingARowThatIsNotInASessionIsRefused() {
        UUID chequeId = firstCheque();

        assertThatThrownBy(() -> onlinePayments.releaseOnlinePending(chequeId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Can only revert cheques in ONLINE_PENDING");
    }

    /**
     * The state is owed money, and every "what is outstanding" question now says so.
     * A settlement that forgot it would hand the renter back a deposit with an
     * instalment still unpaid.
     */
    @Test
    void anAbandonedSessionStaysOnTheDueListAndInSettlementArrears() {
        UUID chequeId = firstCheque();
        startOrder(chequeId);

        List<Cheque> arrears = tx.execute(s -> chequeRepo.findDueForLease(leaseId(), TODAY));
        List<Cheque> chased = tx.execute(s -> chequeRepo.findAllDue(TODAY));

        assertThat(arrears).extracting(Cheque::getId)
                .as("the settlement preview's arrears read this query")
                .contains(chequeId);
        assertThat(chased).extracting(Cheque::getId)
                .as("and the overdue reminder job walks this one")
                .contains(chequeId);
    }

    @Test
    void aFailedSignatureOnTheClientCallbackReleasesTheRow() {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);
        when(provider.verifyPaymentSignature(anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        VerifyPaymentResponseDTO response = onlinePayments.verifyPayment(verifyRequest());

        assertThat(response.isSuccess()).isFalse();
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.REGISTERED);
        assertThat(crtCount(chequeId)).isZero();
        assertThat(onlyPayment().getStatus()).isEqualTo(OnlinePaymentStatus.FAILED);
    }

    @Test
    void aFailedPaymentWebhookReleasesTheRow() {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);
        webhookDelivers(failedPayload(), signatureFor(WEBHOOK_SECRET));

        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.REGISTERED);
        assertThat(crtCount(chequeId)).isZero();
        OnlinePayment payment = onlyPayment();
        assertThat(payment.getStatus()).isEqualTo(OnlinePaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("Insufficient funds");
    }

    /**
     * A late "failed" delivery for a session that already captured must not hand a
     * cleared instalment back to the renter as unpaid.
     */
    @Test
    void aFailureReportAfterACaptureDoesNotReleaseTheRow() {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);
        webhookCaptured();

        webhookDelivers(failedPayload(), signatureFor(WEBHOOK_SECRET));

        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.CLEARED);
        assertThat(crtCount(chequeId)).isEqualTo(1L);
        assertThat(onlyPayment().getStatus()).isEqualTo(OnlinePaymentStatus.CAPTURED);
    }

    // ------------------------------------------------------------------
    // a captured payment never goes backwards
    // ------------------------------------------------------------------

    /**
     * A late "failed" delivery for an applied capture changes nothing on the
     * payment (not status, amount or capture time) and is logged as processed
     * with the reason it was ignored.
     */
    @Test
    void aFailureReportAfterACaptureLeavesThePaymentUntouchedAndIsLoggedAsIgnored() throws Exception {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);
        webhookCaptured();
        OnlinePayment before = onlyPayment();
        Thread.sleep(20);

        webhookDelivers(failedPayload(), signatureFor(WEBHOOK_SECRET));

        OnlinePayment after = onlyPayment();
        assertThat(after.getStatus()).isEqualTo(OnlinePaymentStatus.CAPTURED);
        assertThat(after.getAmount()).isEqualByComparingTo(before.getAmount());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(after.getFailureReason()).isNull();
        assertThat(lastWebhookLog().getProcessed()).isTrue();
        assertThat(lastWebhookResult()).startsWith("ignored: payment already captured");
        assertThat(onlinePayments.unappliedTotals().count()).isZero();
    }

    /**
     * The same late "failed" delivery for a capture the register refused: the
     * refund is still owed, so the payment stays CAPTURED_UNAPPLIED and stays on
     * finance's list with the same count and total.
     */
    @Test
    void aFailureReportAfterAnUnappliedCaptureKeepsItOnTheRefundList() throws Exception {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);
        webhookDelivers(capturedPayload(900_000L, "AED"), signatureFor(WEBHOOK_SECRET));
        OnlinePayment before = onlyPayment();
        assertThat(before.getStatus()).isEqualTo(OnlinePaymentStatus.CAPTURED_UNAPPLIED);
        var totalsBefore = onlinePayments.unappliedTotals();
        Thread.sleep(20);

        webhookDelivers(failedPayload(), signatureFor(WEBHOOK_SECRET));

        OnlinePayment after = onlyPayment();
        assertThat(after.getStatus()).isEqualTo(OnlinePaymentStatus.CAPTURED_UNAPPLIED);
        assertThat(after.getAmount()).isEqualByComparingTo(before.getAmount());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(after.getFailureReason()).isEqualTo(before.getFailureReason());
        assertThat(lastWebhookLog().getProcessed()).isTrue();
        assertThat(lastWebhookResult()).startsWith("ignored: payment already captured");

        assertThat(onlinePayments.unapplied(PageRequest.of(0, 25)).getContent())
                .extracting(r -> r.id()).containsExactly(after.getId());
        assertThat(onlinePayments.unappliedTotals()).isEqualTo(totalsBefore);
        assertThat(totalsBefore.count()).isEqualTo(1L);
    }

    /** A second delivery of an unapplied capture does not move its capture time. */
    @Test
    void aDuplicateUnappliedCaptureKeepsTheFirstCaptureTime() throws Exception {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);
        webhookDelivers(capturedPayload(900_000L, "AED"), signatureFor(WEBHOOK_SECRET));
        OnlinePayment first = onlyPayment();
        Thread.sleep(20);

        webhookDelivers(capturedPayload(900_000L, "AED"), signatureFor(WEBHOOK_SECRET));

        OnlinePayment after = onlyPayment();
        assertThat(after.getStatus()).isEqualTo(OnlinePaymentStatus.CAPTURED_UNAPPLIED);
        assertThat(after.getUpdatedAt()).isEqualTo(first.getUpdatedAt());
        assertThat(after.getFailureReason()).isEqualTo(first.getFailureReason());
        assertThat(onlinePayments.unapplied(PageRequest.of(0, 25)).getContent().getFirst().capturedAt())
                .isEqualTo(first.getUpdatedAt());
    }

    /**
     * An applied capture redelivered after its row bounced from CLEARED (a
     * chargeback) is still the applied capture: it neither turns into a refund
     * nor moves its capture time.
     */
    @Test
    void aRedeliveredCaptureAfterTheRowBouncedStaysAppliedWithItsFirstCaptureTime() throws Exception {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);
        webhookCaptured();
        OnlinePayment first = onlyPayment();
        chequeService.bounce(chequeId, new ChequeActionRequest(TODAY, null, ChequeFailureReason.BOUNCE, null));
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.BOUNCED);
        Thread.sleep(20);

        webhookCaptured();

        OnlinePayment after = onlyPayment();
        assertThat(after.getStatus()).isEqualTo(OnlinePaymentStatus.CAPTURED);
        assertThat(after.getUpdatedAt()).isEqualTo(first.getUpdatedAt());
        assertThat(after.getFailureReason()).isNull();
        assertThat(onlinePayments.unappliedTotals().count()).isZero();
        assertThat(lastWebhookResult())
                .as("an applied capture is reported as applied, not as a refund")
                .isEqualTo("Payment captured successfully");
    }

    /**
     * REFUNDED is in MONEY_CAPTURED but nothing exercised it: the set could have
     * lost that member and every test would still have passed. Money that has
     * been given back is still money the gateway took, so a redelivered capture
     * must not re-apply it - that would clear the register row a second time
     * against a payment the landlord no longer holds.
     */
    @Test
    void aCaptureReportForARefundedPaymentChangesNothing() throws Exception {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);
        webhookDelivers(capturedPayload(900_000L, "AED"), signatureFor(WEBHOOK_SECRET));
        OnlinePayment unapplied = onlyPayment();
        assertThat(unapplied.getStatus()).isEqualTo(OnlinePaymentStatus.CAPTURED_UNAPPLIED);

        // The refund is issued outside this service today; the status is what a
        // later refund flow will set, and this rule has to hold when it does.
        unapplied.setStatus(OnlinePaymentStatus.REFUNDED);
        onlinePaymentRepo.saveAndFlush(unapplied);
        OnlinePayment before = onlyPayment();
        ChequeStatus rowBefore = chequeRepo.findById(chequeId).orElseThrow().getStatus();
        Thread.sleep(20);

        webhookDelivers(capturedPayload(), signatureFor(WEBHOOK_SECRET));

        OnlinePayment after = onlyPayment();
        assertThat(after.getStatus()).isEqualTo(OnlinePaymentStatus.REFUNDED);
        assertThat(after.getAmount()).isEqualByComparingTo(before.getAmount());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(chequeRepo.findById(chequeId).orElseThrow().getStatus())
                .as("a refunded payment must not clear the row a second time")
                .isEqualTo(rowBefore);
        assertThat(lastWebhookLog().getProcessed()).isTrue();
    }

    @Test
    void aFailureReportForARefundedPaymentChangesNothing() throws Exception {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);
        webhookDelivers(capturedPayload(900_000L, "AED"), signatureFor(WEBHOOK_SECRET));
        OnlinePayment unapplied = onlyPayment();
        unapplied.setStatus(OnlinePaymentStatus.REFUNDED);
        onlinePaymentRepo.saveAndFlush(unapplied);
        OnlinePayment before = onlyPayment();
        Thread.sleep(20);

        webhookDelivers(failedPayload(), signatureFor(WEBHOOK_SECRET));

        OnlinePayment after = onlyPayment();
        assertThat(after.getStatus()).isEqualTo(OnlinePaymentStatus.REFUNDED);
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(lastWebhookResult()).startsWith("ignored: payment already captured");
    }

    /**
     * The other side of the rule, and the one that must NOT be terminal. A
     * session the gateway reported as failed is released and the row goes back
     * to REGISTERED; if a capture then arrives - deliveries are not ordered, and
     * a late capture means the money really was taken - it has to be applied,
     * not ignored. FAILED is deliberately absent from MONEY_CAPTURED, and
     * nothing pinned that until now.
     */
    @Test
    void aCaptureThatArrivesAfterTheSessionWasReleasedStillApplies() throws Exception {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);
        webhookDelivers(failedPayload(), signatureFor(WEBHOOK_SECRET));
        assertThat(onlyPayment().getStatus()).isEqualTo(OnlinePaymentStatus.FAILED);
        assertThat(chequeRepo.findById(chequeId).orElseThrow().getStatus())
                .isEqualTo(ChequeStatus.REGISTERED);

        webhookDelivers(capturedPayload(), signatureFor(WEBHOOK_SECRET));

        assertThat(onlyPayment().getStatus())
                .as("money that was taken late is still money taken")
                .isEqualTo(OnlinePaymentStatus.CAPTURED);
        assertThat(chequeRepo.findById(chequeId).orElseThrow().getStatus())
                .isEqualTo(ChequeStatus.CLEARED);
        assertThat(lastWebhookResult()).isEqualTo("Payment captured successfully");
    }

    // ------------------------------------------------------------------
    // receipts
    // ------------------------------------------------------------------

    @Test
    void receiptOnlyForCleared() {
        UUID chequeId = firstCheque();

        assertThatThrownBy(() -> receipts.generateReceipt(chequeId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("cleared");

        onlinePayments.createOrder(chequeId);
        webhookCaptured();

        byte[] pdf = receipts.generateReceipt(chequeId);
        assertThat(pdf).isNotEmpty();
        // A PDF, not an error page rendered as one.
        assertThat(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
    }

    @Test
    void aRenterCannotDownloadAnotherRentersReceipt() {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);
        webhookCaptured();

        Renter stranger = fixtures.createRenter("Somebody Else");
        asRenter(stranger);
        assertThatThrownBy(() -> receipts.generateReceipt(chequeId))
                .isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------
    // the renter's list
    // ------------------------------------------------------------------

    @Test
    void myPaymentsShowsTheRegisterWithWhatIsPayableRightNow() {
        UUID dueId = firstCheque();
        List<ChequeDTO> register = registerRows();
        UUID futureId = register.get(register.size() - 1).id();

        List<RenterChequeDTO> rows = tx.execute(s ->
                onlinePayments.getMyPayments(fixtures.renter().getUserId()));

        assertThat(rows).hasSize(4);
        RenterChequeDTO due = row(rows, dueId);
        assertThat(due.due()).isTrue();
        assertThat(due.payable()).isEqualByComparingTo("12000");
        assertThat(due.onlineEnabled()).isTrue();
        assertThat(due.penaltyOutstanding()).isEqualByComparingTo("0");
        assertThat(due.mode()).isEqualTo(ChequeMode.PDC);

        RenterChequeDTO future = row(rows, futureId);
        assertThat(future.due()).isFalse();
        assertThat(future.payable()).isEqualByComparingTo("0");
    }

    @Test
    void myPaymentsHidesRowsTheRenterHasNoClaimOnAndKeepsBouncedOnesPayable() {
        UUID bouncedId = bounceFirstCheque();
        List<ChequeDTO> register = registerRows();
        UUID cancelledId = register.get(3).id();
        chequeService.cancel(cancelledId, ChequeActionRequest.on(TODAY));

        List<RenterChequeDTO> rows = tx.execute(s ->
                onlinePayments.getMyPayments(fixtures.renter().getUserId()));

        assertThat(rows).extracting(RenterChequeDTO::id).doesNotContain(cancelledId);
        RenterChequeDTO bounced = row(rows, bouncedId);
        assertThat(bounced.status()).isEqualTo(ChequeStatus.BOUNCED);
        assertThat(bounced.due()).isTrue();
        // Payable although its PDC receivable is reversed: createOrder replaces it first.
        assertThat(bounced.payable()).isEqualByComparingTo("12000");
        assertThat(bounced.failureReason()).isEqualTo(ChequeFailureReason.BOUNCE);
    }

    @Test
    void myPaymentsRespectsThePropertyOnlinePaymentSwitch() {
        disableOnlinePayments();
        UUID chequeId = firstCheque();

        List<RenterChequeDTO> rows = tx.execute(s ->
                onlinePayments.getMyPayments(fixtures.renter().getUserId()));
        assertThat(row(rows, chequeId).onlineEnabled()).isFalse();

        assertThatThrownBy(() -> onlinePayments.createOrder(chequeId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("switched off");
    }

    // ------------------------------------------------------------------
    // what a renter may and may not do to their own register rows
    // ------------------------------------------------------------------

    /**
     * Carried from Task 7's review: the gateway path is the <em>only</em> place a
     * renter may move a register row, and all they can do with it is start paying.
     */
    @Test
    void aRenterMayStartAPaymentOnTheirOwnRowButMayNotClearOrBounceIt() {
        UUID chequeId = firstCheque();
        UUID strangersCheque = otherRentersDueCheque();
        asRenter(fixtures.renter());

        assertThatThrownBy(() -> chequeService.clear(chequeId, ChequeActionRequest.on(TODAY)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> chequeService.bounce(chequeId,
                new ChequeActionRequest(TODAY, null, ChequeFailureReason.BOUNCE, null)))
                .isInstanceOf(NotFoundException.class);
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.REGISTERED);

        assertThatThrownBy(() -> chequeService.registerOnlinePending(strangersCheque))
                .isInstanceOf(NotFoundException.class);

        ChequeDTO pending = chequeService.registerOnlinePending(chequeId);
        assertThat(pending.status()).isEqualTo(ChequeStatus.ONLINE_PENDING);
    }

    // ------------------------------------------------------------------
    // gateway configuration
    // ------------------------------------------------------------------

    @Test
    void aSettlementAccountMustBeAnActiveBankLeaf() {
        Account receivable = leaf(AccountRole.RENT_RECEIVABLE);
        assertThatThrownBy(() -> gatewayConfigService.saveConfig(configDto(receivable.getId())))
                .isInstanceOf(BusinessRuleViolationException.class);

        Account cashGroup = tx.execute(s -> accountService.getAccountByCode("A-02"));
        assertThatThrownBy(() -> gatewayConfigService.saveConfig(configDto(cashGroup.getId())))
                .isInstanceOf(BusinessRuleViolationException.class);

        assertThat(gatewayConfigService.saveConfig(configDto(settlementAccount.getId()))
                .getSettlementAccountId()).isEqualTo(settlementAccount.getId());
    }

    // ------------------------------------------------------------------
    // captures the register cannot accept
    // ------------------------------------------------------------------

    /**
     * Two orders, one instalment, both paid.
     *
     * <p>The renter opens checkout, abandons it (the row goes back to REGISTERED),
     * opens it again and pays through the second order — and then the first order
     * captures too, because they had in fact completed it and the gateway was slow
     * to say so. Both payments are real money against one debt. The second to arrive
     * cannot post a {@code CRT} (the instalment is settled) and must not be waved
     * through as a duplicate (it is a different payment, and a refund is owed), so
     * it lands in {@code CAPTURED_UNAPPLIED}.</p>
     */
    @Test
    void aSecondOrderCapturedOnASettledInstalmentIsRecordedAsUnapplied() {
        UUID chequeId = firstCheque();

        String abandoned = startOrder(chequeId);
        onlinePayments.cancelPendingOnlinePayment(chequeId);
        startOrder(chequeId);
        webhookCaptured();
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.CLEARED);

        orderId = abandoned;
        webhookCaptured();

        assertThat(crtCount(chequeId))
                .as("one instalment, one CRT, however many orders were paid")
                .isEqualTo(1L);
        assertThat(paymentByOrder(abandoned).getStatus()).isEqualTo(OnlinePaymentStatus.CAPTURED_UNAPPLIED);
        assertThat(paymentByOrder(abandoned).getFailureReason())
                .contains("already settled by another payment")
                .contains(PAYMENT_ID);
        assertThat(lastWebhookResult()).contains("Captured but not applied");
        assertThat(successNotifications(chequeId))
                .as("the renter is not told a second time that their instalment is paid")
                .isEqualTo(1L);
    }

    /** The same, on the ONLINE-mode replacement row a bounced cheque is paid through. */
    @Test
    void aSecondOrderCapturedOnASettledOnlineReplacementRowIsRecordedAsUnapplied() {
        UUID bouncedId = bounceFirstCheque();
        startOrder(bouncedId);
        UUID onlineRow = tx.execute(s -> chequeRepo.findById(bouncedId).orElseThrow().getReplacedBy().getId());
        assertThat(reread(onlineRow).getMode()).isEqualTo(ChequeMode.ONLINE);
        // Back off the replacement row so the two competing sessions below both
        // start from REGISTERED, the way a renter opening checkout twice would.
        onlinePayments.cancelPendingOnlinePayment(onlineRow);

        String abandoned = startOrder(onlineRow);
        onlinePayments.cancelPendingOnlinePayment(onlineRow);
        startOrder(onlineRow);
        webhookCaptured();

        orderId = abandoned;
        webhookCaptured();

        assertThat(crtCount(onlineRow)).isEqualTo(1L);
        assertThat(paymentByOrder(abandoned).getStatus()).isEqualTo(OnlinePaymentStatus.CAPTURED_UNAPPLIED);
    }

    /**
     * A clerk banked the cheque while the renter was paying for it. The paper will
     * clear at the bank; the gateway's money cannot clear the same row.
     */
    @Test
    void aCaptureForARowAClerkHasMovedIsRecordedAsUnapplied() {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);
        onlinePayments.cancelPendingOnlinePayment(chequeId);
        chequeService.deposit(chequeId, ChequeActionRequest.on(TODAY));

        webhookCaptured();

        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.DEPOSITED);
        assertThat(crtCount(chequeId)).isZero();
        OnlinePayment payment = onlyPayment();
        assertThat(payment.getStatus()).isEqualTo(OnlinePaymentStatus.CAPTURED_UNAPPLIED);
        assertThat(payment.getFailureReason()).contains("DEPOSITED");
        assertThat(successNotifications(chequeId)).isZero();
    }

    /** The gateway charged less than the instalment: it does not settle it. */
    @Test
    void anUnderPaymentIsRecordedAsUnappliedAndPostsNothing() {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);

        webhookDelivers(capturedPayload(900_000L, "AED"), signatureFor(WEBHOOK_SECRET));

        assertThat(crtCount(chequeId)).isZero();
        assertThat(reread(chequeId).getStatus())
                .as("the row stays pending: the instalment is not settled by 9,000 of 12,000")
                .isEqualTo(ChequeStatus.ONLINE_PENDING);
        OnlinePayment payment = onlyPayment();
        assertThat(payment.getStatus()).isEqualTo(OnlinePaymentStatus.CAPTURED_UNAPPLIED);
        assertThat(payment.getFailureReason()).contains("900000").contains("1200000");
        assertThat(lastWebhookResult()).contains("Captured but not applied");
    }

    @Test
    void aCaptureInTheWrongCurrencyIsRecordedAsUnapplied() {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);

        webhookDelivers(capturedPayload(1_200_000L, "INR"), signatureFor(WEBHOOK_SECRET));

        assertThat(crtCount(chequeId)).isZero();
        OnlinePayment payment = onlyPayment();
        assertThat(payment.getStatus()).isEqualTo(OnlinePaymentStatus.CAPTURED_UNAPPLIED);
        assertThat(payment.getFailureReason()).contains("INR");
    }

    /**
     * The order and the row disagree — the instalment was amended after the order
     * was raised, say. Checked on both paths, since the client callback carries no
     * amount of its own to compare against.
     */
    @Test
    void anOrderRaisedForADifferentAmountIsRecordedAsUnappliedOnTheVerifyPath() {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);
        tx.executeWithoutResult(s -> {
            OnlinePayment payment = onlinePaymentRepo.findByGatewayOrderId(orderId).orElseThrow();
            payment.setAmount(new BigDecimal("9000"));
            onlinePaymentRepo.save(payment);
        });
        when(provider.verifyPaymentSignature(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        VerifyPaymentResponseDTO response = onlinePayments.verifyPayment(verifyRequest());

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).contains("could not be applied").contains("refund");
        assertThat(crtCount(chequeId)).isZero();
        assertThat(onlyPayment().getStatus())
                .as("the CAPTURED_UNAPPLIED record must commit, not roll back with an exception")
                .isEqualTo(OnlinePaymentStatus.CAPTURED_UNAPPLIED);
    }

    // ------------------------------------------------------------------
    // the signature is checked against THIS tenant's secret
    // ------------------------------------------------------------------

    @Test
    void theDeliveryIsVerifiedAgainstThisTenantsOwnSecret() {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);

        webhookCaptured();

        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.CLEARED);
        // Not "some secret": the one belonging to the tenant that owns this order.
        org.mockito.Mockito.verify(provider)
                .verifyWebhookSignature(anyString(), any(), org.mockito.ArgumentMatchers.eq(WEBHOOK_SECRET));
    }

    /**
     * A payload signed with another tenant's webhook secret is not this tenant's
     * delivery, however valid it is for whoever made it.
     */
    @Test
    void aPayloadSignedForAnotherTenantsSecretIsRejected() {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);
        String otherTenantSecret = seedOtherTenantGateway();

        webhookDelivers(capturedPayload(), signatureFor(otherTenantSecret));

        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.ONLINE_PENDING);
        assertThat(crtCount(chequeId)).isZero();
        assertThat(onlyPaymentOfThisTenant().getStatus()).isEqualTo(OnlinePaymentStatus.CREATED);
        assertThat(lastWebhookResult()).contains("signature verification failed");
    }

    // ------------------------------------------------------------------
    // malformed deliveries
    // ------------------------------------------------------------------

    @Test
    void aMalformedPayloadIsRecordedAndIgnored() {
        webhookDelivers("{\"event\":\"payment.captured\"}", signatureFor(WEBHOOK_SECRET));

        // Read straight off the table: a delivery that never resolved an order has
        // no tenant on its audit row, so the tenant-scoped read would not see it.
        java.util.Map<String, Object> log = jdbc.queryForMap(
                "select processing_result, processed from webhook_logs order by created_at desc limit 1");
        assertThat((String) log.get("processing_result"))
                .as("a body with no payment entity is a malformed delivery, not a 500")
                .contains("Malformed payload");
        assertThat((Boolean) log.get("processed"))
                .as("there is nothing to retry, so the gateway is told we are done with it")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // who may act on a pending session
    // ------------------------------------------------------------------

    /**
     * A bad signature releases the session — so the caller has to be entitled to
     * the session first. Otherwise any renter could hand any other renter's
     * in-flight instalment back to the register with nothing but an order id.
     */
    @Test
    void anotherRenterCannotReleaseAPendingSessionWithABadSignature() {
        UUID chequeId = firstCheque();
        onlinePayments.createOrder(chequeId);
        Renter stranger = fixtures.createRenter("Somebody Else");

        asRenter(stranger);
        when(provider.verifyPaymentSignature(anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        assertThatThrownBy(() -> onlinePayments.verifyPayment(verifyRequest()))
                .isInstanceOf(NotFoundException.class);

        LeaseTestFixtures.authenticateAsTenantAdmin();
        assertThat(reread(chequeId).getStatus())
                .as("the rightful renter's session is untouched")
                .isEqualTo(ChequeStatus.ONLINE_PENDING);
        assertThat(onlyPayment().getStatus()).isEqualTo(OnlinePaymentStatus.CREATED);
    }

    // ------------------------------------------------------------------
    // approved penalties on the renter's list
    // ------------------------------------------------------------------

    /**
     * An approved penalty is an ordinary CASH row on the lease. It shows on the
     * renter's list carrying its assessment id, it is summed into every row's
     * {@code penaltyOutstanding} banner, and it stops counting once collected.
     */
    @Test
    void anApprovedPenaltyAppearsAsARowAndIsSummedUntilItIsCollected() {
        UUID bouncedId = bounceFirstCheque();
        UUID leaseId = leaseId();
        PenaltyAssessmentDTO proposed = penalties.propose(new ProposePenaltyRequest(
                leaseId, bouncedId, PenaltyReason.CHEQUE_RETURN, new BigDecimal("500"),
                "Returned cheque fee"), UUID.randomUUID());
        penalties.approve(proposed.id(), TODAY);

        List<RenterChequeDTO> rows = onlinePayments.getMyPayments(fixtures.renter().getUserId());

        RenterChequeDTO fine = rows.stream()
                .filter(r -> r.penaltyAssessmentId() != null)
                .findFirst().orElseThrow();
        assertThat(fine.penaltyAssessmentId()).isEqualTo(proposed.id());
        assertThat(fine.amount()).isEqualByComparingTo("500");
        assertThat(fine.mode()).isEqualTo(ChequeMode.CASH);
        assertThat(rows).allSatisfy(r ->
                assertThat(r.penaltyOutstanding()).isEqualByComparingTo("500"));

        chequeService.receive(fine.id(), ChequeActionRequest.on(TODAY));

        List<RenterChequeDTO> afterCollection = onlinePayments.getMyPayments(fixtures.renter().getUserId());
        assertThat(afterCollection).allSatisfy(r ->
                assertThat(r.penaltyOutstanding())
                        .as("a fine that has been paid is no longer outstanding")
                        .isEqualByComparingTo("0"));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** 48,000 over four numbered instruments, the first already matured. */
    private PostLeaseResponse posted;

    /** Keeps the extra leases' cheque numbers unique within the tenant. */
    private int orderSeq = 0;

    private PostLeaseResponse posted() {
        if (posted == null) {
            posted = fixtures.postedLease(CONTRACT_DATE, START, END,
                    List.of(line("RENT", "48000")), 4, "200010");
        }
        return posted;
    }

    private UUID leaseId() {
        return posted().lease().getId();
    }

    private List<ChequeDTO> registerRows() {
        posted();
        return tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId()).stream()
                .map(c -> com.datagami.rentaxis.core.service.cheque.ChequeMapper.toDto(c, TODAY, 0))
                .toList());
    }

    /** The first instalment: matured, registered, payable today. */
    private UUID firstCheque() {
        ChequeDTO first = registerRows().get(0);
        assertThat(first.chequeDate()).isBeforeOrEqualTo(TODAY);
        return first.id();
    }

    private UUID bounceFirstCheque() {
        UUID chequeId = firstCheque();
        chequeService.deposit(chequeId, ChequeActionRequest.on(TODAY));
        chequeService.bounce(chequeId, new ChequeActionRequest(TODAY, null, ChequeFailureReason.BOUNCE, null));
        return chequeId;
    }

    /** A second renter on a second unit, with a matured instalment of their own. */
    private UUID otherRentersDueCheque() {
        Unit unit = fixtures.createUnit(fixtures.property(), "202");
        Renter other = fixtures.createRenter("Other Renter");
        PostLeaseResponse theirs = fixtures.postedLease(unit, other, CONTRACT_DATE, START, END,
                List.of(line("RENT", "24000")), 2, "300010");
        return theirs.cheques().get(0).id();
    }

    private void seedGateway() {
        PaymentGateway gateway = gatewayRepo.findByCode("RAZORPAY").orElseGet(() -> {
            PaymentGateway g = new PaymentGateway();
            g.setCode("RAZORPAY");
            g.setName("Razorpay");
            g.setIsActive(true);
            return gatewayRepo.save(g);
        });
        tx.executeWithoutResult(s -> {
            TenantGatewayConfig config = new TenantGatewayConfig();
            config.setGateway(gateway);
            config.setApiKeyEncrypted(encryptionService.encrypt("rzp_test_key"));
            config.setApiSecretEncrypted(encryptionService.encrypt("rzp_test_secret"));
            config.setWebhookSecretEncrypted(encryptionService.encrypt(WEBHOOK_SECRET));
            config.setSettlementAccount(accountRepo.findById(settlementAccount.getId()).orElseThrow());
            config.setIsActive(true);
            configRepo.save(config);
        });
    }

    private void disableOnlinePayments() {
        tx.executeWithoutResult(s -> {
            RentCollectionSettings settings = new RentCollectionSettings();
            settings.setProperty(fixtures.property());
            settings.setOnlinePaymentEnabled(false);
            settingsRepo.save(settings);
        });
    }

    private com.datagami.rentaxis.api.dto.TenantGatewayConfigDTO configDto(UUID settlementAccountId) {
        com.datagami.rentaxis.api.dto.TenantGatewayConfigDTO dto =
                new com.datagami.rentaxis.api.dto.TenantGatewayConfigDTO();
        dto.setGatewayId(tx.execute(s -> gatewayRepo.findByCode("RAZORPAY").orElseThrow().getId()));
        dto.setApiKey("rzp_test_key");
        dto.setApiSecret("rzp_test_secret");
        dto.setSettlementAccountId(settlementAccountId);
        return dto;
    }

    private static CreateOrderResponseDTO order(String orderId, BigDecimal amount) {
        CreateOrderResponseDTO response = new CreateOrderResponseDTO();
        response.setOrderId(orderId);
        response.setAmount(amount.multiply(BigDecimal.valueOf(100)).longValue());
        response.setCurrency("AED");
        response.setGatewayCode("RAZORPAY");
        return response;
    }

    private VerifyPaymentRequestDTO verifyRequest() {
        VerifyPaymentRequestDTO request = new VerifyPaymentRequestDTO();
        request.setGatewayOrderId(orderId);
        request.setGatewayPaymentId(PAYMENT_ID);
        request.setGatewaySignature(SIGNATURE);
        return request;
    }

    /** What the gateway says it took: the instalment, in fils, in AED. */
    private String capturedPayload() {
        return capturedPayload(INSTALMENT.multiply(BigDecimal.valueOf(100)).longValue(), "AED");
    }

    /**
     * The same delivery with the money spelled out, so a test can say "the gateway
     * charged 9,000 of a 12,000 instalment" or "it charged INR".
     */
    private String capturedPayload(long minorUnits, String currency) {
        return "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{"
                + "\"order_id\":\"" + orderId + "\",\"id\":\"" + PAYMENT_ID + "\","
                + "\"amount\":" + minorUnits + ",\"currency\":\"" + currency + "\"}}}}";
    }

    private String failedPayload() {
        return "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{"
                + "\"order_id\":\"" + orderId + "\",\"id\":\"" + PAYMENT_ID + "\","
                + "\"error_description\":\"Insufficient funds\"}}}}";
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** A verified capture delivered by the gateway, with no user in the context. */
    private void webhookCaptured() {
        webhookDelivers(capturedPayload(), signatureFor(WEBHOOK_SECRET));
    }

    /**
     * One delivery, signed with whatever secret the caller names.
     *
     * <p>The faked provider only answers true when the signature it is handed was
     * made for the secret it is handed, which is what makes "the right tenant's
     * secret" an assertion rather than an assumption: a stub that returned true for
     * every secret would pass whether or not the handler looked up the right
     * tenant's configuration.</p>
     */
    private void webhookDelivers(String payload, String signature) {
        bindSignaturesToSecrets();
        asWebhook(() -> webhookService.processRazorpayWebhook(payload, signature));
    }

    private void bindSignaturesToSecrets() {
        when(provider.verifyWebhookSignature(anyString(), any(), anyString()))
                .thenAnswer(inv -> signatureFor(inv.getArgument(2)).equals(inv.getArgument(1)));
    }

    /** The only signature that verifies against this secret. */
    private static String signatureFor(String secret) {
        return "sig-for-" + secret;
    }

    /** The same capture reported by the browser coming back from Razorpay. */
    private void verifyCaptured() {
        when(provider.verifyPaymentSignature(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        onlinePayments.verifyPayment(verifyRequest());
    }

    /**
     * The webhook carries no {@code Authentication} at all — that is the whole
     * reason its signature has to be the guard — so the security context is
     * cleared around the delivery and restored afterwards for the test's reads.
     */
    private void asWebhook(Runnable delivery) {
        UUID tenantId = fixtures.tenantId();
        LeaseTestFixtures.clearAuth();
        try {
            delivery.run();
        } finally {
            TenantContextHolder.setTenantId(tenantId);
            LeaseTestFixtures.authenticateAsTenantAdmin();
        }
    }

    private void asRenter(Renter renter) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        renter.getUserId().toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_RENTER"))));
    }

    /**
     * One capture, reported the way the test says, against its own lease on its
     * own unit and its own gateway order — so the three orderings cannot
     * contaminate each other's ledger, and so neither the one-active-lease-per-unit
     * constraint nor a reused {@code gateway_order_id} decides the outcome.
     */
    private void assertExactlyOneCrt(String ordering, Runnable report) {
        orderId = "order_IT_" + UUID.randomUUID();
        Unit unit = fixtures.createUnit(fixtures.property(), "9-" + UUID.randomUUID().toString().substring(0, 4));
        Renter renter = fixtures.createRenter("Payer " + ordering);
        PostLeaseResponse lease = fixtures.postedLease(unit, renter, CONTRACT_DATE, START, END,
                List.of(line("RENT", "48000")), 4, "4" + (100000 + orderSeq++));
        UUID chequeId = lease.cheques().get(0).id();
        onlinePayments.createOrder(chequeId);

        report.run();

        assertThat(crtCount(chequeId))
                .as("two reports of one capture (%s) must leave exactly one CRT", ordering)
                .isEqualTo(1L);
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.CLEARED);
        assertThat(paymentsFor(chequeId))
                .singleElement()
                .extracting(OnlinePayment::getStatus)
                .isEqualTo(OnlinePaymentStatus.CAPTURED);
    }

    private long crtCount(UUID chequeId) {
        return jdbc.queryForObject(
                "select count(*) from journal_entries where tenant_id = ? and doc_type = ? and source_id = ?",
                Long.class, fixtures.tenantId(), JournalDocType.CRT.name(), chequeId);
    }

    private Cheque reread(UUID chequeId) {
        return tx.execute(s -> {
            Cheque c = chequeRepo.findById(chequeId).orElseThrow();
            // Touch the lazy relation the assertions read.
            if (c.getReplacedBy() != null) {
                c.getReplacedBy().getId();
            }
            return c;
        });
    }

    private OnlinePayment onlyPayment() {
        List<OnlinePayment> payments = allPayments();
        assertThat(payments).hasSize(1);
        return payments.get(0);
    }

    private List<OnlinePayment> allPayments() {
        return tx.execute(s -> onlinePaymentRepo.findAll());
    }

    private List<OnlinePayment> paymentsFor(UUID chequeId) {
        return tx.execute(s -> onlinePaymentRepo.findByCheque_Id(chequeId));
    }

    private OnlinePayment paymentByOrder(String order) {
        return tx.execute(s -> onlinePaymentRepo.findByGatewayOrderId(order).orElseThrow());
    }

    /**
     * The only payment belonging to this test's tenant. {@link #onlyPayment} counts
     * every row in the database, which the two-tenant case deliberately adds to.
     */
    private OnlinePayment onlyPaymentOfThisTenant() {
        List<OnlinePayment> mine = allPayments().stream()
                .filter(p -> fixtures.tenantId().equals(p.getTenantId()))
                .toList();
        assertThat(mine).hasSize(1);
        return mine.get(0);
    }

    /**
     * A gateway session on this row under a fresh order id.
     *
     * <p>The faked gateway answers with whatever {@link #orderId} currently holds,
     * so a test that opens two checkouts has to rotate it — two payments sharing one
     * {@code gateway_order_id} would make the webhook's lookup ambiguous and the
     * test would be measuring that instead of what it means to.</p>
     *
     * @return this order's id, so the caller can come back to it later.
     */
    private String startOrder(UUID chequeId) {
        orderId = "order_IT_" + UUID.randomUUID();
        onlinePayments.createOrder(chequeId);
        return orderId;
    }

    /** Another landlord org with a gateway configuration and a secret of its own. */
    private String seedOtherTenantGateway() {
        String secret = "whsec_other_" + UUID.randomUUID().toString().substring(0, 6);
        UUID mine = fixtures.tenantId();
        UUID other = tx.execute(s -> {
            com.datagami.rentaxis.domain.entity.LandlordOrg org =
                    new com.datagami.rentaxis.domain.entity.LandlordOrg();
            org.setName("Other-Tenant-" + UUID.randomUUID());
            return orgRepo.save(org).getId();
        });
        TenantContextHolder.setTenantId(other);
        try {
            PaymentGateway gateway = tx.execute(s -> gatewayRepo.findByCode("RAZORPAY").orElseThrow());
            tx.executeWithoutResult(s -> {
                TenantGatewayConfig config = new TenantGatewayConfig();
                config.setGateway(gateway);
                config.setApiKeyEncrypted(encryptionService.encrypt("rzp_other_key"));
                config.setApiSecretEncrypted(encryptionService.encrypt("rzp_other_secret"));
                config.setWebhookSecretEncrypted(encryptionService.encrypt(secret));
                config.setIsActive(true);
                configRepo.save(config);
            });
        } finally {
            TenantContextHolder.setTenantId(mine);
        }
        return secret;
    }

    /** How many "your payment went through" rows the renter has for this row. */
    private long successNotifications(UUID chequeId) {
        return jdbc.queryForObject(
                "select count(*) from notifications where tenant_id = ? and type = 'PAYMENT_CLEARED' "
                        + "and reference_type = 'CHEQUE' and reference_id = ?",
                Long.class, fixtures.tenantId(), chequeId);
    }

    private com.datagami.rentaxis.domain.entity.WebhookLog lastWebhookLog() {
        return tx.execute(s -> webhookLogs.findAll().stream()
                .filter(l -> fixtures.tenantId().equals(l.getTenantId()))
                .reduce((a, b) -> b)
                .orElseThrow());
    }

    private String lastWebhookResult() {
        return lastWebhookLog().getProcessingResult();
    }

    private Account leaf(AccountRole role) {
        return tx.execute(s -> resolver.resolve(role, fixtures.property().getId()));
    }

    private static RenterChequeDTO row(List<RenterChequeDTO> rows, UUID id) {
        return rows.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }
}
