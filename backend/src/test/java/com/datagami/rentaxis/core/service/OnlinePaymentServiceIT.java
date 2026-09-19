package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateOrderResponseDTO;
import com.datagami.rentaxis.api.dto.RenterChequeDTO;
import com.datagami.rentaxis.api.dto.VerifyPaymentRequestDTO;
import com.datagami.rentaxis.api.dto.VerifyPaymentResponseDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.gateway.PaymentGatewayFactory;
import com.datagami.rentaxis.core.service.gateway.PaymentGatewayProvider;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
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
        when(provider.verifyWebhookSignature(anyString(), any(), anyString())).thenReturn(false);

        asWebhook(() -> webhookService.processRazorpayWebhook(capturedPayload(), "forged"));

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
            TenantGatewayConfig config = configRepo.findByIsActiveTrue().get(0);
            config.setWebhookSecretEncrypted(null);
            configRepo.save(config);
        });

        asWebhook(() -> webhookService.processRazorpayWebhook(capturedPayload(), SIGNATURE));

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
        when(provider.verifyWebhookSignature(anyString(), any(), anyString())).thenReturn(true);

        asWebhook(() -> webhookService.processRazorpayWebhook(failedPayload(), SIGNATURE));

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

        when(provider.verifyWebhookSignature(anyString(), any(), anyString())).thenReturn(true);
        asWebhook(() -> webhookService.processRazorpayWebhook(failedPayload(), SIGNATURE));

        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.CLEARED);
        assertThat(crtCount(chequeId)).isEqualTo(1L);
        assertThat(onlyPayment().getStatus()).isEqualTo(OnlinePaymentStatus.CAPTURED);
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

    private String capturedPayload() {
        return "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{"
                + "\"order_id\":\"" + orderId + "\",\"id\":\"" + PAYMENT_ID + "\"}}}}";
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
        when(provider.verifyWebhookSignature(anyString(), any(), anyString())).thenReturn(true);
        asWebhook(() -> webhookService.processRazorpayWebhook(capturedPayload(), SIGNATURE));
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

    private String lastWebhookResult() {
        return tx.execute(s -> webhookLogs.findAll().stream()
                .filter(l -> fixtures.tenantId().equals(l.getTenantId()))
                .reduce((a, b) -> b)
                .orElseThrow()
                .getProcessingResult());
    }

    private Account leaf(AccountRole role) {
        return tx.execute(s -> resolver.resolve(role, fixtures.property().getId()));
    }

    private static RenterChequeDTO row(List<RenterChequeDTO> rows, UUID id) {
        return rows.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }
}
