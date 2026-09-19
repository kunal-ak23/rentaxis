package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
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
import org.springframework.transaction.support.TransactionTemplate;
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
 * Who may read a contract, and what the contracts list actually filters on.
 *
 * <p>Two fixes share a test because they share the endpoint. An ACCOUNTANT could
 * already <em>post</em> a lease — and read its cheques and its journals — but not
 * load the lease itself, so the one role whose job is putting a contract on the
 * books could not open the contract it was posting. And the paged list took only a
 * free-text term, which meant the list screen filtered status over the page it had
 * been handed: a paginator announcing 25 while showing four.</p>
 *
 * <p>{@code @PreAuthorize} does nothing in a service-level test — the roles only
 * bite once a request has been through {@code ApiSecurityFilter} — so this is a
 * full-context HTTP test with the {@code X-User-*} headers the Next.js proxy
 * sends, the same shape as {@link LeaseControllerPostEndpointsIT}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LeaseControllerReadAccessIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort int port;

    @Autowired LeaseService leaseService;
    @Autowired LeaseRepository leaseRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;
    @Autowired TransactionTemplate tx;

    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);

    private LeaseTestFixtures fixtures;
    private User accountant;
    private User propertyManager;

    /** Marina's two contracts — one live, one still a draft — and Palm's one. */
    private Property marina;
    private Property palm;
    private UUID marinaActive;
    private UUID marinaDraft;
    private UUID palmActive;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, null, null);

        marina = fixtures.property();
        palm = fixtures.createProperty("PALM");

        Unit marinaUnitA = fixtures.unit();
        Unit marinaUnitB = fixtures.createUnit(marina, "202");
        Unit palmUnit = fixtures.createUnit(palm, "901");

        marinaActive = draft(marinaUnitA);
        activate(marinaActive);
        marinaDraft = draft(marinaUnitB);
        palmActive = draft(palmUnit);
        activate(palmActive);

        accountant = user(UserRole.ACCOUNTANT);
        propertyManager = user(UserRole.PROPERTY_MANAGER);

        // Marina only. Without an assignment a PROPERTY_MANAGER is scoped to no
        // property at all, which is a different answer from "scoped to one".
        UserPropertyAssignment assignment = new UserPropertyAssignment();
        assignment.setUserId(propertyManager.getId());
        assignment.setPropertyId(marina.getId());
        assignmentRepo.save(assignment);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ------------------------------------------------------------------
    // an accountant can open the contract it posts
    // ------------------------------------------------------------------

    @Test
    void anAccountantMayReadTheListTheContractItsLinesAndItsEvents() {
        assertThat(status(accountant, HttpMethod.GET, "/api/v1/leases")).isEqualTo(HttpStatus.OK);
        assertThat(status(accountant, HttpMethod.GET, "/api/v1/leases/paged")).isEqualTo(HttpStatus.OK);
        assertThat(status(accountant, HttpMethod.GET, "/api/v1/leases/" + marinaActive))
                .isEqualTo(HttpStatus.OK);
        assertThat(status(accountant, HttpMethod.GET, "/api/v1/leases/" + marinaActive + "/lines"))
                .isEqualTo(HttpStatus.OK);
        assertThat(status(accountant, HttpMethod.GET, "/api/v1/leases/" + marinaActive + "/events"))
                .isEqualTo(HttpStatus.OK);
        assertThat(status(accountant, HttpMethod.GET, "/api/v1/leases/property/" + marina.getId()))
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * Reads only. Drafting, editing and deleting a contract stay with the admins —
     * the accountant decides when a contract becomes a journal, not what it says.
     */
    @Test
    void anAccountantStillMayNotCreateEditOrDeleteAContract() {
        assertThat(statusWithBody(accountant, HttpMethod.POST, "/api/v1/leases", createBody()))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusWithBody(accountant, HttpMethod.PUT, "/api/v1/leases/" + marinaDraft, createBody()))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(accountant, HttpMethod.DELETE, "/api/v1/leases/" + marinaDraft))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** An accountant is tenant-wide, so the list is the tenant's, not one building's. */
    @Test
    void anAccountantSeesEveryPropertysContracts() {
        assertThat(ids(paged(accountant, "")))
                .containsExactlyInAnyOrder(marinaActive, marinaDraft, palmActive);
    }

    // ------------------------------------------------------------------
    // the list's filters are filters
    // ------------------------------------------------------------------

    /**
     * The total and the page describe the same set. Filtering status over a page
     * the database had already counted is what made the paginator lie.
     */
    @Test
    void aStatusFilterNarrowsTheQueryAndTheTotalFollowsIt() {
        ResponseEntity<Map> active = paged(accountant, "?status=ACTIVE");
        assertThat(total(active)).isEqualTo(2);
        assertThat(ids(active)).containsExactlyInAnyOrder(marinaActive, palmActive);

        ResponseEntity<Map> drafts = paged(accountant, "?status=DRAFT");
        assertThat(total(drafts)).isEqualTo(1);
        assertThat(ids(drafts)).containsExactly(marinaDraft);

        // A status nothing is in is an empty page with an honest total, not an error.
        ResponseEntity<Map> renewed = paged(accountant, "?status=RENEWED");
        assertThat(total(renewed)).isZero();
        assertThat(ids(renewed)).isEmpty();
    }

    /** Page size one: the total still counts the matches, not the page. */
    @Test
    void theTotalCountsTheMatchesNotThePage() {
        ResponseEntity<Map> firstOfTwo = paged(accountant, "?status=ACTIVE&page=0&size=1");
        assertThat(total(firstOfTwo)).isEqualTo(2);
        assertThat(ids(firstOfTwo)).hasSize(1);

        ResponseEntity<Map> secondOfTwo = paged(accountant, "?status=ACTIVE&page=1&size=1");
        assertThat(total(secondOfTwo)).isEqualTo(2);
        assertThat(ids(secondOfTwo)).hasSize(1).doesNotContainAnyElementsOf(ids(firstOfTwo));
    }

    @Test
    void aPropertyFilterNarrowsTheQueryToThatBuilding() {
        ResponseEntity<Map> palmOnly = paged(accountant, "?propertyId=" + palm.getId());
        assertThat(total(palmOnly)).isEqualTo(1);
        assertThat(ids(palmOnly)).containsExactly(palmActive);
    }

    /**
     * A manager's scope still wins. The filter narrows what the database returns;
     * {@code LeaseAccessPolicy} decides which of those the caller may see, and the
     * total is computed after both — so a manager asking for ACTIVE contracts is
     * told how many ACTIVE contracts <em>they</em> have, not how many exist.
     */
    @Test
    void aPropertyManagersScopeSurvivesAStatusFilter() {
        ResponseEntity<Map> active = paged(propertyManager, "?status=ACTIVE");
        assertThat(total(active)).isEqualTo(1);
        assertThat(ids(active)).containsExactly(marinaActive);

        // And a building they were not assigned answers with nothing rather than
        // with the other manager's contracts.
        ResponseEntity<Map> palmOnly = paged(propertyManager, "?propertyId=" + palm.getId());
        assertThat(total(palmOnly)).isZero();
        assertThat(ids(palmOnly)).isEmpty();
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private UUID draft(Unit unit) {
        CreateLeaseDTO dto = fixtures.draftDto(unit, fixtures.renter(), START, END,
                List.of(line("RENT", "51000")));
        dto.setContractDate(START.minusDays(14));
        dto.setFirstDueDate(START);
        return leaseService.createDraftLease(dto).getId();
    }

    /**
     * Straight to ACTIVE on the row. Posting properly would need a grid, a chart and
     * a journal per lease, and none of that changes which rows a status filter
     * returns — which is what this test is about.
     */
    private void activate(UUID leaseId) {
        tx.executeWithoutResult(s -> {
            Lease lease = leaseRepo.findById(leaseId).orElseThrow();
            lease.setStatus(LeaseStatus.ACTIVE);
            leaseRepo.save(lease);
        });
    }

    private CreateLeaseDTO createBody() {
        CreateLeaseDTO dto = fixtures.draftDto(fixtures.unit(), fixtures.renter(), START, END,
                List.of(line("RENT", "51000")));
        dto.setContractDate(START.minusDays(14));
        dto.setFirstDueDate(START);
        return dto;
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

    private HttpStatusCode status(User caller, HttpMethod method, String path) {
        return request(caller, method, path, null).toBodilessEntity().getStatusCode();
    }

    private HttpStatusCode statusWithBody(User caller, HttpMethod method, String path, Object body) {
        return request(caller, method, path, body).toBodilessEntity().getStatusCode();
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> paged(User caller, String query) {
        ResponseEntity<Map> response =
                request(caller, HttpMethod.GET, "/api/v1/leases/paged" + query, null).toEntity(Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private int total(ResponseEntity<Map> page) {
        return ((Number) page.getBody().get("totalElements")).intValue();
    }

    @SuppressWarnings("unchecked")
    private List<UUID> ids(ResponseEntity<Map> page) {
        return ((List<Map<String, Object>>) page.getBody().get("content")).stream()
                .map(row -> UUID.fromString((String) row.get("id")))
                .toList();
    }
}
