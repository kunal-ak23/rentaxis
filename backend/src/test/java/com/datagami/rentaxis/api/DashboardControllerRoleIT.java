package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sandbox role test (2026-10-15) M4: the accountant's Home failed with "Unable to
 * load dashboard data" because GET /dashboard/summary and /monthly-collections
 * admitted only SUPER_ADMIN, TENANT_ADMIN and PROPERTY_MANAGER.
 *
 * <p>The accountant is admitted now. The figures are nothing new to the role — it
 * already reads every lease ({@code LeaseAccessPolicy} treats it as tenant-wide)
 * and works the whole cheque register — and they stay the caller's organisation's:
 * the second organisation's accountant sees only its own rent. Every other role
 * the controller refused is still refused; TENANT_USER in particular gets no
 * financial totals, and its web Home no longer asks for them.</p>
 *
 * <p>Over HTTP, because {@code @PreAuthorize} only bites behind
 * {@code ApiSecurityFilter} (see {@code VoucherControllerIT}).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardControllerRoleIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired ObjectMapper json;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired ChequeGenerationService generation;
    @Autowired LeasePostingService posting;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 1, 5);
    private static final LocalDate START = LocalDate.of(2026, 2, 1);
    private static final LocalDate END = LocalDate.of(2027, 1, 31);

    private UUID orgA;
    private UUID orgB;

    @BeforeEach
    void setUp() {
        orgA = orgWithOnePostedLease("51000", "410010");
        orgB = orgWithOnePostedLease("24000", "420010");
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private UUID orgWithOnePostedLease(String rent, String firstCheque) {
        LeaseTestFixtures f = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);
        f.postedLease(CONTRACT_DATE, START, END, List.of(line("RENT", rent)), 4, firstCheque);
        UUID tenant = f.tenantId();
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
        return tenant;
    }

    @Test
    void anAccountantReadsItsOwnOrganisationsSummaryOnly() {
        JsonNode a = body(get("/api/v1/dashboard/summary", user(UserRole.ACCOUNTANT, orgA)), HttpStatus.OK);
        assertThat(a.get("totalProperties").asInt()).isEqualTo(1);
        assertThat(a.get("activeLeases").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(a.get("totalRentRevenue").asText())).isEqualByComparingTo("51000");

        JsonNode b = body(get("/api/v1/dashboard/summary", user(UserRole.ACCOUNTANT, orgB)), HttpStatus.OK);
        assertThat(b.get("totalProperties").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(b.get("totalRentRevenue").asText())).isEqualByComparingTo("24000");
    }

    @Test
    void anAccountantReadsTheMonthlyChartOfItsOwnOrganisation() {
        JsonNode a = body(get("/api/v1/dashboard/monthly-collections", user(UserRole.ACCOUNTANT, orgA)), HttpStatus.OK);
        JsonNode b = body(get("/api/v1/dashboard/monthly-collections", user(UserRole.ACCOUNTANT, orgB)), HttpStatus.OK);
        assertThat(a.size()).isEqualTo(12);
        // Each organisation's expected total is its own lease's cheques that fall in
        // the window, so the two differ and neither includes the other's rent.
        assertThat(expected(a)).isNotEqualByComparingTo(expected(b));
        assertThat(expected(a).add(expected(b)).signum()).isPositive();
    }

    @Test
    void theRolesThatAlreadyHadItStillDo() {
        assertThat(get("/api/v1/dashboard/summary", user(UserRole.TENANT_ADMIN, orgA)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/dashboard/summary", user(UserRole.PROPERTY_MANAGER, orgA)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"TENANT_USER", "RENTER", "SECURITY_GUARD"})
    void everyOtherRoleIsStillRefused(UserRole role) {
        User caller = user(role, orgA);
        assertThat(get("/api/v1/dashboard/summary", caller).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/dashboard/monthly-collections", caller).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------

    private User user(UserRole role, UUID tenant) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenant);
        return userRepo.save(u);
    }

    private ResponseEntity<String> get(String path, User caller) {
        return RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString())
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    private JsonNode body(ResponseEntity<String> res, HttpStatus expected) {
        assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(expected);
        try {
            return json.readTree(res.getBody());
        } catch (Exception e) {
            throw new AssertionError("Response was not JSON: " + res.getBody(), e);
        }
    }

    private static BigDecimal expected(JsonNode series) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode point : series) {
            total = total.add(new BigDecimal(point.get("expected").asText()));
        }
        return total;
    }
}
