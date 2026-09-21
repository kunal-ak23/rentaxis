package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
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
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may look at a termination and who may perform one, asserted over HTTP.
 *
 * <p>The two role lists differ on purpose, and the asymmetry is the same one
 * renew/extend draws. <b>Preview</b> writes nothing: it tells the building manager
 * what ending a tenancy on a given day would cost, which is a question they are
 * entitled to ask about a building they run. <b>Terminate</b> hands instruments
 * back, reverses journals and closes a contract; that is the accountant's
 * decision.</p>
 *
 * <p><b>403 and 404 mean two different things here and both are asserted.</b> A
 * role the endpoint does not admit is refused by {@code @PreAuthorize} before the
 * request reaches any service — that is a 403. A caller whose role <em>is</em>
 * admitted but who is not entitled to <em>this</em> lease is refused by
 * {@code LeaseAccessPolicy}, which deliberately answers <b>404</b>: a 403 on a
 * lease id confirms the lease exists, which lets a manager enumerate the
 * organisation's contracts through the status code alone.</p>
 *
 * <p>{@code @PreAuthorize} does nothing in a service-level test — the roles only
 * bite once a request has been through {@code ApiSecurityFilter} — so this is a
 * full-context HTTP test with the legacy {@code X-User-*} headers the Next.js
 * proxy sends, the same shape as {@link LeaseControllerRenewExtendEndpointsIT}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LeaseControllerTerminateEndpointsIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

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
    private static final LocalDate T = LocalDate.of(2027, 2, 15);

    private LeaseTestFixtures fixtures;
    private UUID leaseId;
    /** A second building the property manager was never assigned to. */
    private UUID foreignLeaseId;
    private User superAdmin;
    private User tenantAdmin;
    private User accountant;
    private User propertyManager;
    private User renter;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGenerationService, leasePostingService);

        leaseId = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, null)
                .lease().getId();

        Property other = fixtures.createProperty("PALM");
        Unit otherUnit = fixtures.createUnit(other, "201");
        Renter otherRenter = fixtures.createRenter("Other Renter");
        foreignLeaseId = fixtures.postedLease(otherUnit, otherRenter, CONTRACT_DATE, START, END,
                List.of(line("RENT", "36500")), 4, null)
                .lease().getId();

        superAdmin = user(UserRole.SUPER_ADMIN);
        tenantAdmin = user(UserRole.TENANT_ADMIN);
        accountant = user(UserRole.ACCOUNTANT);
        propertyManager = user(UserRole.PROPERTY_MANAGER);
        renter = user(UserRole.RENTER);

        // Assigned to the first building only, which is what makes the second one a
        // genuine "not yours" rather than "assigned to nothing".
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

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> body(User caller, HttpMethod method, String path, Object body) {
        return request(caller, method, path, body).toEntity(Map.class);
    }

    private String previewPath(UUID id, LocalDate date) {
        return "/api/v1/leases/" + id + "/terminate/preview" + (date == null ? "" : "?date=" + date);
    }

    private String terminatePath(UUID id) {
        return "/api/v1/leases/" + id + "/terminate";
    }

    private Map<String, Object> terminateBody() {
        return Map.of("terminationDate", T.toString(), "notes", "Renter relocating");
    }

    private LeaseStatus currentStatus(UUID id) {
        return leaseService.getLeaseById(id).getStatus();
    }

    // ------------------------------------------------------------------

    /** The finance roles may do both, and the body is the contract Task 8 renders. */
    @Test
    void theFinanceRolesMayPreviewAndTerminate() {
        for (User caller : List.of(superAdmin, tenantAdmin, accountant)) {
            assertThat(status(caller, HttpMethod.GET, previewPath(leaseId, T), null))
                    .as(caller.getRole() + " preview").isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<Map> preview = body(accountant, HttpMethod.GET, previewPath(leaseId, T), null);
        assertThat(preview.getBody()).containsKeys("terminationDate", "earnedRentThroughDate",
                "recognisedSoFar", "unearnedRent", "chequesToReturn", "chequesToKeep",
                "bouncedOutstanding", "receivableAfter");
        assertThat(preview.getBody().get("terminationDate")).isEqualTo(T.toString());
        // Nothing was recognised, so everything the term is worth is still unearned.
        assertThat(new java.math.BigDecimal(String.valueOf(preview.getBody().get("recognisedSoFar"))))
                .isEqualByComparingTo("0");
        // 2 Oct → 15 Feb inclusive is 137 days of a 365-day term: 139.726027 × 137
        // = 19,142.47 earned, so 51,000 − 19,142.47 is still unearned.
        assertThat(new java.math.BigDecimal(String.valueOf(preview.getBody().get("earnedRentThroughDate"))))
                .isEqualByComparingTo("19142.47");
        assertThat(new java.math.BigDecimal(String.valueOf(preview.getBody().get("unearnedRent"))))
                .isEqualByComparingTo("31857.53");
        assertThat(currentStatus(leaseId)).isEqualTo(LeaseStatus.ACTIVE);

        ResponseEntity<Map> terminated = body(accountant, HttpMethod.POST, terminatePath(leaseId), terminateBody());

        assertThat(terminated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(terminated.getBody().get("status")).isEqualTo("TERMINATED");
        assertThat(terminated.getBody().get("terminatedOn")).isEqualTo(T.toString());
        assertThat(terminated.getBody().get("terminationNotes")).isEqualTo("Renter relocating");
        assertThat(terminated.getBody().get("terminationJournalId")).isNotNull();
        assertThat(currentStatus(leaseId)).isEqualTo(LeaseStatus.TERMINATED);
    }

    /**
     * A property manager may ask what a move-out would cost in their own building,
     * and may not perform it.
     *
     * <p>The two refusals below are deliberately different codes: the POST is
     * refused by the role gate before any service sees it (403), while a preview of
     * somebody else's building is refused by {@code LeaseAccessPolicy} as though
     * the lease did not exist (404).</p>
     */
    @Test
    void aPropertyManagerMayPreviewTheirOwnBuildingAndNothingElse() {
        ResponseEntity<Map> preview = body(propertyManager, HttpMethod.GET, previewPath(leaseId, T), null);
        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(preview.getBody().get("terminationDate")).isEqualTo(T.toString());

        assertThat(status(propertyManager, HttpMethod.GET, previewPath(foreignLeaseId, T), null))
                .as("preview of a building they were not assigned").isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(status(propertyManager, HttpMethod.POST, terminatePath(leaseId), terminateBody()))
                .as("terminate is the finance roles'").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(currentStatus(leaseId)).isEqualTo(LeaseStatus.ACTIVE);
    }

    /** A renter may neither look at nor perform a termination of their own contract. */
    @Test
    void aRenterIsRefusedBothEndpoints() {
        assertThat(status(renter, HttpMethod.GET, previewPath(leaseId, T), null))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(renter, HttpMethod.POST, terminatePath(leaseId), terminateBody()))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(currentStatus(leaseId)).isEqualTo(LeaseStatus.ACTIVE);
    }

    /**
     * The date is the whole request. Missing on the preview is a 400 from the
     * binder; missing in the body is a 400 from {@code @Valid}; outside the term is
     * a 400 the service explains.
     */
    @Test
    void aTerminationWithoutAUsableDateIsRefused() {
        assertThat(status(accountant, HttpMethod.GET, previewPath(leaseId, null), null))
                .as("preview with no date").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(accountant, HttpMethod.POST, terminatePath(leaseId), Map.of("notes", "no date")))
                .as("terminate with no date").isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> outsideTerm = body(accountant, HttpMethod.POST, terminatePath(leaseId),
                Map.of("terminationDate", END.plusDays(1).toString()));
        assertThat(outsideTerm.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) outsideTerm.getBody().get("message")).contains("outside the lease term");

        assertThat(currentStatus(leaseId)).isEqualTo(LeaseStatus.ACTIVE);
    }
}
