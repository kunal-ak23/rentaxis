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
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may renew a lease and who may extend one, asserted over HTTP.
 *
 * <p>The two role lists differ on purpose. <b>Renew</b> writes a DRAFT and no
 * journals, so a PROPERTY_MANAGER — the person who actually negotiates next
 * year's terms with the sitting tenant — is allowed it, exactly as they are
 * allowed to cut a cheque grid. <b>Extend</b> posts a TCO and registers
 * instruments the moment it is called; that is the accountant's decision, and the
 * role list is the posting one.</p>
 *
 * <p>{@code @PreAuthorize} does nothing in a service-level test — the roles only
 * bite once a request has been through {@code ApiSecurityFilter} — so this is a
 * full-context HTTP test with the legacy {@code X-User-*} headers the Next.js
 * proxy sends, the same shape as {@link LeaseControllerPostEndpointsIT}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaseControllerRenewExtendEndpointsIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService chequeGenerationService;
    @Autowired LeasePostingService leasePostingService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);
    private static final LocalDate NEW_END = LocalDate.of(2027, 12, 31);

    private LeaseTestFixtures fixtures;
    private UUID leaseId;
    private User accountant;
    private User propertyManager;
    private User renter;
    private User tenantUser;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGenerationService, leasePostingService);

        leaseId = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, null)
                .lease().getId();

        accountant = user(UserRole.ACCOUNTANT);
        propertyManager = user(UserRole.PROPERTY_MANAGER);
        renter = user(UserRole.RENTER);
        tenantUser = user(UserRole.TENANT_USER);

        // Without the assignment a PROPERTY_MANAGER is scoped to no properties at
        // all and the policy answers 404 rather than 403 — a different failure from
        // the one this test is about.
        UserPropertyAssignment assignment = new UserPropertyAssignment();
        assignment.setUserId(propertyManager.getId());
        assignment.setPropertyId(fixtures.property().getId());
        assignmentRepo.save(assignment);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private User user(UserRole role) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(fixtures.tenantId());
        return userRepo.save(u);
    }

    private RestClient.ResponseSpec request(User caller, HttpMethod method, String path, Object body) {
        RestClient.RequestBodySpec spec = RestClient.builder()
                .baseUrl("http://localhost:" + port).build()
                .method(method).uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString());
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        // Never throw on a 4xx: the status is the assertion.
        return spec.retrieve().onStatus(status -> true, (request, response) -> { });
    }

    private HttpStatusCode status(User caller, HttpMethod method, String path, Object body) {
        return request(caller, method, path, body).toBodilessEntity().getStatusCode();
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> body(User caller, HttpMethod method, String path, Object body) {
        return request(caller, method, path, body).toEntity(Map.class);
    }

    private String renewPath() {
        return "/api/v1/leases/" + leaseId + "/renew";
    }

    private String extendPath() {
        return "/api/v1/leases/" + leaseId + "/extend";
    }

    private Map<String, Object> renewBody() {
        return Map.of(
                "contractDate", "2027-09-16",
                "startDate", "2027-10-02",
                "endDate", "2028-10-01",
                "carryDepositForward", false);
    }

    private Map<String, Object> extendBody() {
        return Map.of(
                "newEndDate", NEW_END.toString(),
                "contractDate", "2027-09-20",
                "lines", List.of(Map.of("chargeTypeCode", "RENT", "grossAmount", 12000)),
                // Numbered: an extension PDC needs its number like any other (#80, PR #344 review I4).
                "cheques", List.of(Map.of("amount", 12000, "chequeDate", "2027-10-02", "chequeNumber", "EXT-000901")));
    }

    private LeaseStatus currentStatus() {
        return leaseService.getLeaseById(leaseId).getStatus();
    }

    private LocalDate currentEndDate() {
        return leaseService.getLeaseById(leaseId).getEndDate();
    }

    /** A PROPERTY_MANAGER drafts next year's contract; no journal is written. */
    @Test
    void propertyManagerMayRenewButNotExtend() {
        ResponseEntity<Map> renewed = body(propertyManager, HttpMethod.POST, renewPath(), renewBody());

        assertThat(renewed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renewed.getBody().get("status")).isEqualTo("DRAFT");
        assertThat(renewed.getBody().get("renewedFromLeaseId")).isEqualTo(leaseId.toString());
        assertThat(renewed.getBody().get("chainId")).isEqualTo(leaseId.toString());
        // The rent is copied; the one-off admin fee is not, and the response says so (spec §4c).
        assertThat((List<?>) renewed.getBody().get("lines")).hasSize(1);
        assertThat((List<?>) renewed.getBody().get("skippedOneOffLines")).hasSize(1);
        assertThat(currentStatus()).isEqualTo(LeaseStatus.ACTIVE);

        assertThat(status(propertyManager, HttpMethod.POST, extendPath(), extendBody()))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(currentEndDate()).isEqualTo(END);
    }

    /** An ACCOUNTANT may do both; the extension comes back as a posting response. */
    @Test
    void accountantMayRenewAndExtend() {
        ResponseEntity<Map> extended = body(accountant, HttpMethod.POST, extendPath(), extendBody());

        assertThat(extended.getStatusCode()).isEqualTo(HttpStatus.OK);
        // TCO-27/1, not TCO-26/2: entry numbers run per fiscal year and the
        // extension is dated a year after the contract it extends.
        assertThat((String) extended.getBody().get("tcoEntryNumber")).isEqualTo("TCO-27/1");
        assertThat((List<?>) extended.getBody().get("cheques")).hasSize(6);
        assertThat(currentEndDate()).isEqualTo(NEW_END);

        // And the same caller may draft the renewal of the now-longer lease.
        ResponseEntity<Map> renewed = body(accountant, HttpMethod.POST, renewPath(), Map.of(
                "startDate", "2028-01-01",
                "endDate", "2028-12-31",
                "carryDepositForward", false));
        assertThat(renewed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renewed.getBody().get("status")).isEqualTo("DRAFT");
    }

    /** Neither endpoint is a renter's or a non-finance staff user's business. */
    @Test
    void rentersAndTenantUsersMayNeitherRenewNorExtend() {
        for (User caller : List.of(renter, tenantUser)) {
            assertThat(status(caller, HttpMethod.POST, renewPath(), renewBody()))
                    .as("renew as " + caller.getRole()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(status(caller, HttpMethod.POST, extendPath(), extendBody()))
                    .as("extend as " + caller.getRole()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        assertThat(currentStatus()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(currentEndDate()).isEqualTo(END);
    }

    /**
     * The old extension body — a bare {@code newEndDate} and nothing else — is now a
     * 400 rather than a silent term change. It used to move the end date and leave
     * the extra months uncharged, and a caller still sending it must be told.
     */
    @Test
    void theOldExtendBodyIsRefused() {
        assertThat(status(accountant, HttpMethod.POST, extendPath(), Map.of("newEndDate", NEW_END.toString())))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(currentEndDate()).isEqualTo(END);
    }
}
