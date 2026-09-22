package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.api.dto.penalty.PenaltyAssessmentDTO;
import com.datagami.rentaxis.api.dto.penalty.ProposePenaltyRequest;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.penalty.PenaltyAssessmentService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may propose a penalty, and who may turn one into a charge, asserted over
 * HTTP.
 *
 * <p>{@code @PreAuthorize} does nothing in a plain service-level test — the roles
 * only bite once a request has been through {@code ApiSecurityFilter} — so this
 * is a full-context HTTP test with the legacy {@code X-User-*} headers the
 * Next.js proxy sends, the same shape as {@link LeaseControllerChequeEndpointsIT}.</p>
 *
 * <p>The split this exists to pin: a PROPERTY_MANAGER may list and propose,
 * because noticing that a renter should be fined is part of running a building,
 * and may <em>not</em> approve, waive or reverse, because that writes to the
 * ledger. A RENTER reaches neither — only their own approved list.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PenaltyAssessmentControllerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort int port;

    @Autowired PenaltyAssessmentService penalties;
    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService generation;
    @Autowired LeasePostingService posting;
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

    private LeaseTestFixtures fixtures;
    private UUID leaseId;
    private UUID proposalId;
    private User accountant;
    private User propertyManager;
    private User renterUser;
    private User tenantUser;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);

        PostLeaseResponse posted = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000")), 4, "100040");
        leaseId = posted.lease().getId();
        proposalId = penalties.propose(new ProposePenaltyRequest(
                leaseId, null, PenaltyReason.CHEQUE_RETURN, new BigDecimal("500"), "Seeded"), null).id();

        accountant = user(UserRole.ACCOUNTANT);
        propertyManager = user(UserRole.PROPERTY_MANAGER);
        tenantUser = user(UserRole.TENANT_USER);

        // The fixture's renter, given a login. Without it /mine has no renter record
        // to resolve and answers empty for a reason this test is not about.
        renterUser = userRepo.findById(fixtures.renter().getUserId()).orElseThrow();

        // Without the assignment a PROPERTY_MANAGER is scoped to no properties at
        // all and the policy answers 404 rather than 403 — a different failure.
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

    /** Status only: a refusal's body is the error object, not the resource. */
    private HttpStatusCode status(User caller, HttpMethod method, String path, Object body) {
        return request(caller, method, path, body).toBodilessEntity().getStatusCode();
    }

    private static final String BASE = "/api/v1/penalties";

    private Map<String, Object> newProposal() {
        return Map.of("leaseId", leaseId.toString(), "reason", "CHEQUE_RETURN", "amount", 250);
    }

    // ------------------------------------------------------------------

    @Test
    void anAccountantMayListProposeApproveAndReverse() {
        assertThat(status(accountant, HttpMethod.GET, BASE, null)).isEqualTo(HttpStatus.OK);
        assertThat(status(accountant, HttpMethod.POST, BASE, newProposal())).isEqualTo(HttpStatus.CREATED);
        assertThat(status(accountant, HttpMethod.POST, BASE + "/" + proposalId + "/approve",
                Map.of("date", "2026-10-20"))).isEqualTo(HttpStatus.OK);
        assertThat(status(accountant, HttpMethod.POST, BASE + "/" + proposalId + "/reverse",
                Map.of("date", "2026-10-25", "note", "Approved in error"))).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aPropertyManagerMayListAndProposeButNotDecide() {
        assertThat(status(propertyManager, HttpMethod.GET, BASE, null)).isEqualTo(HttpStatus.OK);
        assertThat(status(propertyManager, HttpMethod.POST, BASE, newProposal()))
                .isEqualTo(HttpStatus.CREATED);

        assertThat(status(propertyManager, HttpMethod.POST, BASE + "/" + proposalId + "/approve", null))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(propertyManager, HttpMethod.POST, BASE + "/" + proposalId + "/waive",
                Map.of("note", "no"))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(propertyManager, HttpMethod.POST, BASE + "/" + proposalId + "/reverse", null))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** The worklist is finance's; a renter never sees a deliberation about themselves. */
    @Test
    void aRenterReachesOnlyTheirOwnApprovedList() {
        assertThat(status(renterUser, HttpMethod.GET, BASE, null)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(renterUser, HttpMethod.POST, BASE, newProposal())).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(renterUser, HttpMethod.POST, BASE + "/" + proposalId + "/approve", null))
                .isEqualTo(HttpStatus.FORBIDDEN);

        // /mine works and is empty while the only penalty is merely proposed.
        ResponseEntity<List> mine = request(renterUser, HttpMethod.GET, BASE + "/mine", null)
                .toEntity(List.class);
        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mine.getBody()).isEmpty();

        // Once finance approves it, the renter can see that one.
        PenaltyAssessmentDTO approved = approveAsSystem();
        ResponseEntity<List> after = request(renterUser, HttpMethod.GET, BASE + "/mine", null)
                .toEntity(List.class);
        assertThat(after.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) after.getBody().get(0)).get("id")).isEqualTo(approved.id().toString());
    }

    @Test
    void aTenantUserReachesNothing() {
        assertThat(status(tenantUser, HttpMethod.GET, BASE, null)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(tenantUser, HttpMethod.GET, BASE + "/mine", null)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(tenantUser, HttpMethod.POST, BASE, newProposal())).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(tenantUser, HttpMethod.POST, BASE + "/" + proposalId + "/waive",
                Map.of("note", "no"))).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** A waiver with no reason is a 400 from the service, not a silent success. */
    @Test
    void waivingWithoutAReasonIs400() {
        assertThat(status(accountant, HttpMethod.POST, BASE + "/" + proposalId + "/waive", null))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(accountant, HttpMethod.POST, BASE + "/" + proposalId + "/waive",
                Map.of("note", "Renter's bank confirmed their error"))).isEqualTo(HttpStatus.OK);
    }

    private PenaltyAssessmentDTO approveAsSystem() {
        TenantContextHolder.setTenantId(fixtures.tenantId());
        LeaseTestFixtures.authenticateAsTenantAdmin();
        try {
            return penalties.approve(proposalId, LocalDate.of(2026, 10, 20));
        } finally {
            LeaseTestFixtures.clearAuth();
        }
    }
}
