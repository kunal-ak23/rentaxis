package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.OnlinePayment;
import com.datagami.rentaxis.domain.entity.PaymentGateway;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.OnlinePaymentRepository;
import com.datagami.rentaxis.domain.repository.PaymentGatewayRepository;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finance's refund worklist over HTTP: online payments the gateway captured and
 * the register refused ({@code CAPTURED_UNAPPLIED}).
 *
 * <p>Full-context HTTP test with the legacy {@code X-User-*} headers, the same
 * shape as {@link ChequeControllerIT}: {@code @PreAuthorize} and the tenant
 * context only bite once a request has been through {@code ApiSecurityFilter}.</p>
 *
 * <p>Payment rows are written directly rather than driven through a faked
 * gateway: what is under test is which rows the list shows, not how a capture
 * comes to be unapplied ({@code OnlinePaymentServiceIT} owns that).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OnlinePaymentControllerUnappliedIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LeaseService leaseService;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService generation;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired ChequeRepository chequeRepo;
    @Autowired OnlinePaymentRepository onlinePaymentRepo;
    @Autowired PaymentGatewayRepository gatewayRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 1, 5);
    private static final LocalDate START = LocalDate.of(2026, 2, 1);
    private static final LocalDate END = LocalDate.of(2027, 1, 31);

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private LeaseTestFixtures fixtures;
    private UUID leaseId;
    private List<Cheque> register;
    private PaymentGateway gateway;

    private User accountant;
    private User tenantAdmin;
    private User superAdmin;
    private User propertyManager;
    private User renter;

    /** The older of the two unapplied captures. */
    private OnlinePayment olderUnapplied;
    /** The newer one — first on the list. */
    private OnlinePayment newerUnapplied;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);

        leaseId = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000")), 4, "100040").lease().getId();
        register = tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId));
        // The fixture never generates a contract; give the lease a number so the
        // display form ("CODE/681") is exercised.
        jdbc.update("update leases set contract_number = 681 where id = ?", leaseId);

        gateway = gatewayRepo.findByCode("RAZORPAY").orElseGet(() -> {
            PaymentGateway g = new PaymentGateway();
            g.setCode("RAZORPAY");
            g.setName("Razorpay");
            g.setIsActive(true);
            return gatewayRepo.save(g);
        });

        accountant = user(UserRole.ACCOUNTANT, fixtures.tenantId());
        tenantAdmin = user(UserRole.TENANT_ADMIN, fixtures.tenantId());
        superAdmin = user(UserRole.SUPER_ADMIN, fixtures.tenantId());
        propertyManager = user(UserRole.PROPERTY_MANAGER, fixtures.tenantId());
        renter = user(UserRole.RENTER, fixtures.tenantId());

        // Two captures the register refused, one applied capture, and the
        // sessions that never produced money. Only the first two are refunds.
        olderUnapplied = payment(register.get(0), OnlinePaymentStatus.CAPTURED_UNAPPLIED,
                "500.50", "pay_OLD", "Captured but not applied: the instalment is already cleared "
                        + "(gateway payment pay_OLD)", NOW.minus(2, ChronoUnit.HOURS));
        newerUnapplied = payment(register.get(1), OnlinePaymentStatus.CAPTURED_UNAPPLIED,
                "12750", "pay_NEW", "Captured but not applied: amount mismatch "
                        + "(gateway payment pay_NEW)", NOW.minus(5, ChronoUnit.MINUTES));
        payment(register.get(2), OnlinePaymentStatus.CAPTURED, "12750", "pay_OK", null, NOW);
        payment(register.get(3), OnlinePaymentStatus.CREATED, "12750", null, null, NOW);
        payment(register.get(3), OnlinePaymentStatus.FAILED, "12750", null, "User cancelled checkout", NOW);
        payment(register.get(3), OnlinePaymentStatus.REFUNDED, "12750", "pay_REF", null, NOW);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ------------------------------------------------------------------
    // what is on the list
    // ------------------------------------------------------------------

    /** Everything finance needs to refund it, newest capture first. */
    @Test
    void unappliedCapturesAppearNewestFirstWithEverythingNeededToRefund() {
        ResponseEntity<Map> page = map(accountant, "/api/v1/online-payments/unapplied");

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> content = content(page);
        assertThat(content).extracting(r -> r.get("id"))
                .containsExactly(newerUnapplied.getId().toString(), olderUnapplied.getId().toString());

        Map<String, Object> row = content.getFirst();
        Cheque cheque = register.get(1);
        assertThat(new BigDecimal(row.get("amount").toString())).isEqualByComparingTo("12750");
        assertThat(row.get("currency")).isEqualTo("AED");
        assertThat(row.get("gatewayOrderId")).isEqualTo(newerUnapplied.getGatewayOrderId());
        assertThat(row.get("gatewayPaymentId")).isEqualTo("pay_NEW");
        assertThat((String) row.get("failureReason")).contains("amount mismatch");
        assertThat(row.get("chequeId")).isEqualTo(cheque.getId().toString());
        assertThat(row.get("chequeNumber")).isEqualTo(cheque.getChequeNumber());
        assertThat(row.get("chequeStatus")).isEqualTo("REGISTERED");
        assertThat(row.get("leaseId")).isEqualTo(leaseId.toString());
        assertThat(row.get("displayContractNumber")).isEqualTo(fixtures.property().getCode() + "/681");
        assertThat(row.get("renterName")).isEqualTo("Test Renter");
        assertThat(row.get("propertyName")).isEqualTo(fixtures.propertyName());
        assertThat(row.get("unitIdentifier")).isEqualTo("101");
        assertThat(Instant.parse((String) row.get("capturedAt"))).isEqualTo(NOW.minus(5, ChronoUnit.MINUTES));
        assertThat(row.get("createdAt")).isNotNull();
    }

    /** An applied capture, and sessions that took no money, are nobody's refund. */
    @Test
    void appliedCreatedFailedAndRefundedPaymentsAreExcludedFromListAndTotals() {
        List<Map<String, Object>> content = content(map(accountant, "/api/v1/online-payments/unapplied"));
        assertThat(content).extracting(r -> r.get("gatewayPaymentId"))
                .containsExactlyInAnyOrder("pay_NEW", "pay_OLD");

        ResponseEntity<Map> totals = map(accountant, "/api/v1/online-payments/unapplied/count");
        assertThat(totals.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) totals.getBody().get("count")).longValue()).isEqualTo(2);
        assertThat(new BigDecimal(totals.getBody().get("totalAmount").toString()))
                .isEqualByComparingTo("13250.50");
    }

    /** The tile and the list are two views of the same rows. */
    @Test
    void countAndTotalAgreeWithTheList() {
        ResponseEntity<Map> page = map(tenantAdmin, "/api/v1/online-payments/unapplied?page=0&size=1");
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(content(page)).extracting(r -> r.get("id"))
                .containsExactly(newerUnapplied.getId().toString());
        assertThat(totalElements(page)).isEqualTo(2);

        List<Map<String, Object>> all = content(map(tenantAdmin, "/api/v1/online-payments/unapplied?size=50"));
        BigDecimal listed = all.stream()
                .map(r -> new BigDecimal(r.get("amount").toString()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        ResponseEntity<Map> totals = map(tenantAdmin, "/api/v1/online-payments/unapplied/count");
        assertThat(((Number) totals.getBody().get("count")).longValue()).isEqualTo(all.size());
        assertThat(new BigDecimal(totals.getBody().get("totalAmount").toString())).isEqualByComparingTo(listed);
    }

    /** A super admin working inside the tenant sees the same worklist. */
    @Test
    void superAdminInTheTenantSeesTheWorklist() {
        assertThat(content(map(superAdmin, "/api/v1/online-payments/unapplied"))).hasSize(2);
        assertThat(status(superAdmin, "/api/v1/online-payments/unapplied/count")).isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------
    // who may read it
    // ------------------------------------------------------------------

    /** A refund is money out: property managers and renters are refused both endpoints. */
    @Test
    void propertyManagerAndRenterAreRefused() {
        for (User caller : List.of(propertyManager, renter)) {
            assertThat(status(caller, "/api/v1/online-payments/unapplied")).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(status(caller, "/api/v1/online-payments/unapplied/count")).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // ------------------------------------------------------------------
    // tenant isolation
    // ------------------------------------------------------------------

    /** Another landlord's finance team sees nothing of this one's refunds. */
    @Test
    void anotherTenantSeesNothing() {
        UUID otherTenant = fixtures.newTenant();
        User otherAccountant = user(UserRole.ACCOUNTANT, otherTenant);

        ResponseEntity<Map> page = map(otherAccountant, "/api/v1/online-payments/unapplied");
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(content(page)).isEmpty();
        assertThat(totalElements(page)).isZero();

        ResponseEntity<Map> totals = map(otherAccountant, "/api/v1/online-payments/unapplied/count");
        assertThat(((Number) totals.getBody().get("count")).longValue()).isZero();
        // Zero, never null: the tile has nothing to guard.
        assertThat(totals.getBody().get("totalAmount")).isNotNull();
        assertThat(new BigDecimal(totals.getBody().get("totalAmount").toString())).isEqualByComparingTo("0");
    }

    /**
     * The query scopes by tenant on its own, not only through the Hibernate filter:
     * with no tenant context (filter off) it still returns only the named tenant's
     * rows.
     */
    @Test
    void repositoryQueryScopesByTenantWithoutTheFilter() {
        UUID tenantA = fixtures.tenantId();

        // A second landlord with an unapplied capture of its own.
        LeaseTestFixtures other = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);
        UUID otherLease = other.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000")), 4, "200040").lease().getId();
        Cheque otherCheque = tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(otherLease)).getFirst();
        OnlinePayment otherUnapplied = payment(otherCheque, OnlinePaymentStatus.CAPTURED_UNAPPLIED,
                "999", "pay_OTHER", "Captured but not applied", NOW);

        TenantContextHolder.clear();
        List<UUID> ids = tx.execute(s -> onlinePaymentRepo.findUnapplied(tenantA, PageRequest.of(0, 50))
                .map(OnlinePayment::getId).getContent());
        assertThat(ids).containsExactlyInAnyOrder(newerUnapplied.getId(), olderUnapplied.getId())
                .doesNotContain(otherUnapplied.getId());
        Long count = tx.execute(s -> onlinePaymentRepo.countUnapplied(tenantA));
        BigDecimal sum = tx.execute(s -> onlinePaymentRepo.sumUnapplied(tenantA));
        assertThat(count).isEqualTo(2L);
        assertThat(sum).isEqualByComparingTo("13250.50");
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private OnlinePayment payment(Cheque cheque, OnlinePaymentStatus status, String amount,
                                  String gatewayPaymentId, String failureReason, Instant updatedAt) {
        UUID tenantId = cheque.getTenantId();
        return tx.execute(s -> {
            TenantContextHolder.setTenantId(tenantId);
            OnlinePayment p = new OnlinePayment();
            p.setTenantId(tenantId);
            p.setCheque(chequeRepo.findById(cheque.getId()).orElseThrow());
            p.setGateway(gatewayRepo.findById(gateway.getId()).orElseThrow());
            p.setGatewayOrderId("order_" + UUID.randomUUID());
            p.setGatewayPaymentId(gatewayPaymentId);
            p.setAmount(new BigDecimal(amount));
            p.setCurrency("AED");
            p.setStatus(status);
            p.setFailureReason(failureReason);
            p.setCreatedAt(updatedAt.minus(1, ChronoUnit.MINUTES));
            p.setUpdatedAt(updatedAt);
            return onlinePaymentRepo.save(p);
        });
    }

    private User user(UserRole role, UUID tenantId) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        return userRepo.save(u);
    }

    private RestClient.ResponseSpec get(User caller, String path) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port).build()
                .get().uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString())
                // Never throw on a 4xx: the status is the assertion.
                .retrieve().onStatus(status -> true, (req, res) -> { });
    }

    private HttpStatusCode status(User caller, String path) {
        return get(caller, path).toBodilessEntity().getStatusCode();
    }

    private ResponseEntity<Map> map(User caller, String path) {
        return get(caller, path).toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(ResponseEntity<Map> page) {
        return (List<Map<String, Object>>) page.getBody().get("content");
    }

    /** Spring Data's page JSON: {@code page.totalElements} (VIA_DTO) or top-level (DIRECT). */
    @SuppressWarnings("unchecked")
    private static long totalElements(ResponseEntity<Map> page) {
        Object meta = page.getBody().get("page");
        Object total = meta instanceof Map<?, ?> m ? m.get("totalElements") : page.getBody().get("totalElements");
        return ((Number) total).longValue();
    }
}
