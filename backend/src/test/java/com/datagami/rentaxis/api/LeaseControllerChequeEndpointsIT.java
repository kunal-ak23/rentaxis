package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may touch the cheque grid, asserted over HTTP.
 *
 * <p>Method security is what these endpoints are guarded by, and
 * {@code @PreAuthorize} does nothing in a plain service-level test — the roles
 * only bite once a request has been through {@code ApiSecurityFilter}. So this is
 * a full-context HTTP test with the legacy {@code X-User-*} headers the Next.js
 * proxy sends, the same shape as {@link JournalControllerIT} and
 * {@code AccountantOperationalReadAccessIT}.</p>
 *
 * <p>ACCOUNTANT is the interesting one. The role clears {@code @PreAuthorize} by
 * name, but it also has to clear {@code LeaseAccessPolicy}, which resolves a
 * caller to "sees everything", "these properties" or "nobody" — and an
 * unrecognised role falls closed. An accountant granted these endpoints and then
 * told "Lease not found" for every lease in the organisation is the failure this
 * test exists to catch.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LeaseControllerChequeEndpointsIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort int port;

    @Autowired LeaseService leaseService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;

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
                .bootstrap();

        leaseId = leaseService.createDraftLease(fixtures.draftDto(
                java.time.LocalDate.of(2026, 9, 24),
                java.time.LocalDate.of(2027, 9, 23),
                List.of(line("RENT", "51000"), line("SECURITY_DEPOSIT", "3000")))).getId();

        accountant = user(UserRole.ACCOUNTANT);
        propertyManager = user(UserRole.PROPERTY_MANAGER);
        renter = user(UserRole.RENTER);
        tenantUser = user(UserRole.TENANT_USER);

        // Without the assignment a PROPERTY_MANAGER is scoped to no properties at
        // all, and the policy answers 404 rather than 403 — a different failure
        // from the one this test is about.
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

    /** For the happy paths, where the grid itself is the assertion. */
    private ResponseEntity<List> call(User caller, HttpMethod method, String path, Object body) {
        return request(caller, method, path, body).toEntity(List.class);
    }

    /**
     * Status only. A refusal's body is the error object, not a list, so a
     * {@code toEntity(List.class)} here fails while converting rather than
     * reporting the 403 the test is about.
     */
    private HttpStatusCode status(User caller, HttpMethod method, String path, Object body) {
        return request(caller, method, path, body).toBodilessEntity().getStatusCode();
    }

    private String base() {
        return "/api/v1/leases/" + leaseId + "/cheques";
    }

    private HttpStatusCode get(User u) {
        return status(u, HttpMethod.GET, base(), null);
    }

    private HttpStatusCode generate(User u) {
        return status(u, HttpMethod.POST, base() + "/generate", Map.of("installments", 4));
    }

    private HttpStatusCode numbers(User u) {
        return status(u, HttpMethod.POST, base() + "/numbers", Map.of("startingNumber", "100040"));
    }

    private HttpStatusCode saveRows(User u) {
        List<Map<String, Object>> rows = List.of(Map.of(
                "chequeDate", "2026-09-24",
                "amount", 54000,
                "mode", "PDC",
                "narration", "Single cheque"));
        return status(u, HttpMethod.PUT, base(), rows);
    }

    /** An ACCOUNTANT reaches all four, and the grid it generates is the real one. */
    @Test
    void accountantMayDriveTheWholeGrid() {
        ResponseEntity<List> generated = call(accountant, HttpMethod.POST, base() + "/generate",
                Map.of("installments", 4));
        assertThat(generated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(generated.getBody()).hasSize(4);

        ResponseEntity<List> numbered = call(accountant, HttpMethod.POST, base() + "/numbers",
                Map.of("startingNumber", "100040"));
        assertThat(numbered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) numbered.getBody().get(0)).get("chequeNumber")).isEqualTo("100040");

        ResponseEntity<List> listed = call(accountant, HttpMethod.GET, base(), null);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).hasSize(4);

        ResponseEntity<List> saved = call(accountant, HttpMethod.PUT, base(), List.of(Map.of(
                "chequeDate", "2026-09-24", "amount", 54000, "mode", "PDC", "narration", "Single cheque")));
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) saved.getBody().get(0)).get("seqNo")).isEqualTo(1);
    }

    /** A PROPERTY_MANAGER assigned to the property reaches all four too. */
    @Test
    void assignedPropertyManagerMayDriveTheWholeGrid() {
        assertThat(generate(propertyManager)).isEqualTo(HttpStatus.OK);
        assertThat(numbers(propertyManager)).isEqualTo(HttpStatus.OK);
        assertThat(get(propertyManager)).isEqualTo(HttpStatus.OK);
        assertThat(saveRows(propertyManager)).isEqualTo(HttpStatus.OK);
    }

    /**
     * A renter may see their lease; they may not rewrite the instruments it
     * collects. Neither may a TENANT_USER, who is staff without a finance role.
     */
    @Test
    void rentersAndTenantUsersAreForbiddenFromEveryChequeEndpoint() {
        for (User caller : List.of(renter, tenantUser)) {
            assertThat(get(caller)).as("GET as " + caller.getRole()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(generate(caller)).as("generate as " + caller.getRole()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(numbers(caller)).as("numbers as " + caller.getRole()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(saveRows(caller)).as("save as " + caller.getRole()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        // And nothing they sent took effect.
        assertThat(call(accountant, HttpMethod.GET, base(), null).getBody()).isEmpty();
    }
}
