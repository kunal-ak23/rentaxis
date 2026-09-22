package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.lease.GenerateChequesRequest;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.cutover.ImportBatchService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
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
 * Who may post a lease, asserted over HTTP.
 *
 * <p>Posting is the act that puts money on the landlord's books, so the role list
 * is deliberately shorter than the cheque grid's: SUPER_ADMIN, TENANT_ADMIN and
 * ACCOUNTANT, but <em>not</em> PROPERTY_MANAGER. A building manager assembles the
 * contract and cuts the grid; an accountant decides when it becomes a journal.</p>
 *
 * <p>{@code @PreAuthorize} does nothing in a service-level test — the roles only
 * bite once a request has been through {@code ApiSecurityFilter} — so this is a
 * full-context HTTP test with the legacy {@code X-User-*} headers the Next.js proxy
 * sends, the same shape as {@link LeaseControllerChequeEndpointsIT}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LeaseControllerPostEndpointsIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort int port;

    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService chequeGenerationService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;
    @Autowired ImportBatchService importBatches;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);

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

        CreateLeaseDTO dto = fixtures.draftDto(START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")));
        dto.setContractDate(CONTRACT_DATE);
        dto.setFirstDueDate(START);
        leaseId = leaseService.createDraftLease(dto).getId();

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

    private String postPath() {
        return "/api/v1/leases/" + leaseId + "/post";
    }

    private String amendPath() {
        return "/api/v1/leases/" + leaseId + "/amend-lines";
    }

    /** The grid that makes the lease postable: 4 rent instalments plus the fee. */
    private void generateGrid() {
        chequeGenerationService.generate(leaseId,
                new GenerateChequesRequest(4, START, null, "Emirates NBD", null, false, null));
    }

    private LeaseStatus currentStatus() {
        return leaseService.getLeaseById(leaseId).getStatus();
    }

    /** An ACCOUNTANT posts the lease and then amends it. */
    @Test
    void accountantMayPostAndAmendTheLease() {
        generateGrid();

        ResponseEntity<Map> posted = body(accountant, HttpMethod.POST, postPath(), null);
        assertThat(posted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) posted.getBody().get("tcoEntryNumber")).startsWith("TCO-26/");
        assertThat(posted.getBody().get("tcoJournalId")).isNotNull();
        assertThat((List<?>) posted.getBody().get("cheques")).hasSize(5);
        assertThat(currentStatus()).isEqualTo(LeaseStatus.ACTIVE);

        ResponseEntity<Map> amended = body(accountant, HttpMethod.POST, amendPath(), Map.of(
                "lines", List.of(
                        Map.of("chargeTypeCode", "RENT", "grossAmount", 51000),
                        Map.of("chargeTypeCode", "ADMIN_FEE", "grossAmount", 2000)),
                "reason", "Re-checked against the signed contract"));
        assertThat(amended.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) amended.getBody().get("tcoEntryNumber")).isEqualTo("TCO-26/2");
    }

    /**
     * The dry run answers 200 with the problems and changes nothing — that is the
     * whole point of it, and a review step that quietly posted would be worse than
     * no review step at all.
     */
    @Test
    void dryRunReportsTheProblemsWithoutPosting() {
        // No grid yet, so the lease cannot post.
        ResponseEntity<Map> dry = body(accountant, HttpMethod.POST, postPath() + "?dryRun=true", null);

        assertThat(dry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dry.getBody().get("ok")).isEqualTo(false);
        assertThat((List<?>) dry.getBody().get("errors")).isNotEmpty();
        assertThat(dry.getBody().get("errors").toString()).contains("no cheque grid");
        assertThat(currentStatus()).isEqualTo(LeaseStatus.DRAFT);

        // And once the grid exists it says so, still without posting.
        generateGrid();
        ResponseEntity<Map> clean = body(accountant, HttpMethod.POST, postPath() + "?dryRun=true", null);
        assertThat(clean.getBody().get("ok")).isEqualTo(true);
        assertThat((List<?>) clean.getBody().get("errors")).isEmpty();
        assertThat(currentStatus()).isEqualTo(LeaseStatus.DRAFT);
    }

    /**
     * A PROPERTY_MANAGER builds the contract and cuts the grid but does not decide
     * when it becomes a journal; a renter and a non-finance staff user touch
     * neither endpoint.
     */
    @Test
    void propertyManagersRentersAndTenantUsersMayNotPostOrAmend() {
        generateGrid();

        Object amendBody = Map.of("lines", List.of(Map.of("chargeTypeCode", "RENT", "grossAmount", 53000)),
                "reason", "nope");
        for (User caller : List.of(propertyManager, renter, tenantUser)) {
            assertThat(status(caller, HttpMethod.POST, postPath(), null))
                    .as("post as " + caller.getRole()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(status(caller, HttpMethod.POST, postPath() + "?dryRun=true", null))
                    .as("dry run as " + caller.getRole()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(status(caller, HttpMethod.POST, amendPath(), amendBody))
                    .as("amend as " + caller.getRole()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        // And nothing they sent took effect.
        assertThat(currentStatus()).isEqualTo(LeaseStatus.DRAFT);
    }

    /**
     * A contract that belongs to a DRAFT cut-over batch is posted through the batch,
     * not through this door (review M4).
     *
     * <p>The lease here is otherwise perfectly postable — the grid is cut and the
     * same call succeeds in {@link #accountantMayPostAndAmendTheLease} — so the
     * refusal is the batch rule and nothing else. Posting an imported contract here
     * came out wrong three ways at once: the journals carried no
     * {@code import_batch_id}, so they sat outside both the period-lock exemption a
     * pre-books contract needs and "Reverse batch" forever; the cheque replay was
     * skipped, so the statuses and dates PACT exported were silently dropped; and
     * afterwards the batch could be neither reversed nor discarded. Property
     * managers cannot reach this door, but accountants and tenant admins can.</p>
     */
    @Test
    void aContractBelongingToADraftImportBatchIsRefusedAndNamesTheBatch() {
        generateGrid();
        TenantContextHolder.setTenantId(fixtures.tenantId());
        UUID batchId = importBatches.create(null, "September cut-over").getId();
        importBatches.linkLease(batchId, leaseId);

        ResponseEntity<Map> refused = body(accountant, HttpMethod.POST, postPath(), null);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) refused.getBody().get("message"))
                .isEqualTo("This contract belongs to import batch " + batchId + "; post the batch instead.");
        assertThat(currentStatus()).isEqualTo(LeaseStatus.DRAFT);
    }

    /** The old activation route is gone: Post is the only way onto the books. */
    @Test
    void theActivateEndpointNoLongerExists() {
        assertThat(status(accountant, HttpMethod.PUT, "/api/v1/leases/" + leaseId + "/activate", null))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(currentStatus()).isEqualTo(LeaseStatus.DRAFT);
    }
}
