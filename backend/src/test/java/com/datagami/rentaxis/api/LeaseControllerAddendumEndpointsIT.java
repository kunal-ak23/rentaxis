package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.lease.AddChargeRequest;
import com.datagami.rentaxis.api.dto.lease.AddendumResponse;
import com.datagami.rentaxis.api.dto.lease.LeaseAddendumDTO;
import com.datagami.rentaxis.api.dto.lease.RecordEjariRequest;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.chequeRow;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may post a lease addendum, read the addenda list, and record the Ejari
 * that follows one, asserted over HTTP.
 *
 * <p>{@code LeaseVariationService} only enforces lease scope
 * ({@code requireManageable}/{@code requireReadable}) — it has no notion of
 * posting role. The role gate that keeps a PROPERTY_MANAGER from charging a
 * TCO themselves lives entirely in {@code @PreAuthorize} on this controller,
 * so it has to be asserted here, the same way {@link
 * LeaseControllerRenewExtendEndpointsIT} asserts renew/extend.</p>
 *
 * <p>Full-context HTTP test with the legacy {@code X-User-*} headers the
 * Next.js proxy sends, the same shape as {@link LeaseControllerPostEndpointsIT}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaseControllerAddendumEndpointsIT extends AbstractPostgresIT {

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
        return spec.retrieve().onStatus(status -> true, (req, response) -> { });
    }

    private int status(User caller, HttpMethod method, String path, Object body) {
        HttpStatusCode code = request(caller, method, path, body).toBodilessEntity().getStatusCode();
        return code.value();
    }

    private AddChargeRequest body() {
        return new AddChargeRequest(LocalDate.of(2027, 2, 15), LocalDate.of(2027, 2, 10), null, "Parking",
                List.of(line("PARKING_FEE", "6000")),
                List.of(chequeRow("6000", LocalDate.of(2027, 3, 1))));
    }

    @Test
    void anAccountantMayAddAChargeAndRecordItsEjari() {
        AddendumResponse r = request(accountant, HttpMethod.POST, "/api/v1/leases/" + leaseId + "/addenda", body())
                .body(AddendumResponse.class);
        assertThat(r.addendum().addendumNumber()).startsWith("ADD-");
        assertThat(r.addendum().ejariPending()).isTrue();

        LeaseAddendumDTO patched = request(accountant, HttpMethod.PATCH,
                "/api/v1/leases/" + leaseId + "/addenda/" + r.addendum().id() + "/ejari",
                new RecordEjariRequest("EJ-1")).body(LeaseAddendumDTO.class);
        assertThat(patched.ejariPending()).isFalse();
    }

    @Test
    void aPropertyManagerMayReadAddendaButNotPostOne() {
        assertThat(status(propertyManager, HttpMethod.POST, "/api/v1/leases/" + leaseId + "/addenda", body()))
                .isEqualTo(403);
        assertThat(status(propertyManager, HttpMethod.GET, "/api/v1/leases/" + leaseId + "/addenda", null))
                .isEqualTo(200);
    }

    @Test
    void rentersAndTenantUsersMayNotPostAnAddendum() {
        assertThat(status(renter, HttpMethod.POST, "/api/v1/leases/" + leaseId + "/addenda", body())).isEqualTo(403);
        assertThat(status(tenantUser, HttpMethod.POST, "/api/v1/leases/" + leaseId + "/addenda", body())).isEqualTo(403);
    }

    /**
     * The service only checks lease scope, not posting role — without the
     * controller's {@code @PreAuthorize} a PROPERTY_MANAGER assigned to the
     * property could record an Ejari number themselves. The role check fires
     * before the service runs, so any UUID does for the addendum id.
     */
    @Test
    void aPropertyManagerMayNotRecordEjari() {
        assertThat(status(propertyManager, HttpMethod.PATCH,
                "/api/v1/leases/" + leaseId + "/addenda/" + UUID.randomUUID() + "/ejari",
                new RecordEjariRequest("EJ-1"))).isEqualTo(403);
    }
}
