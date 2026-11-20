package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.rating.RenterRatingJob;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import com.datagami.rentaxis.testsupport.TestIdentities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Renter rating endpoints over HTTP: feature gate, roles, org isolation, CSV, recompute. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(com.datagami.rentaxis.testsupport.LaterBusinessDayConfig.class)
class RenterRatingControllerIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    /** No-op executor: run-on-enable and the recompute endpoint never race the test's own runNowForTenant. */
    @MockitoBean(name = "renterRatingExecutor") Executor renterRatingExecutor;
    @Autowired JdbcTemplate jdbc;

    @Autowired TenantFeatureService features;
    @Autowired RenterRatingJob job;
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

    private LeaseTestFixtures orgA;
    private UUID tenantId;
    private UUID renterId;
    private UUID otherOrgRenterId;
    private UUID otherTenantId;
    private UUID admin;
    private UUID manager;
    private UUID accountant;
    private UUID unassignedManager;
    private UUID superAdmin;

    private LeaseTestFixtures fixtures() {
        return new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);
    }

    @BeforeEach
    void setUp() {
        LeaseTestFixtures a = fixtures();
        a.postedLease(LocalDate.of(2026, 9, 16), LocalDate.of(2026, 10, 2), LocalDate.of(2027, 10, 1),
                List.of(line("RENT", "51000")), 4, "100040");
        orgA = a;
        tenantId = a.tenantId();
        renterId = a.renter().getId();
        features.setEnabled(tenantId, TenantFeature.RENTER_RATING, true);

        LeaseTestFixtures b = fixtures();
        b.postedLease(LocalDate.of(2026, 9, 16), LocalDate.of(2026, 10, 2), LocalDate.of(2027, 10, 1),
                List.of(line("RENT", "51000")), 4, "100050");
        otherTenantId = b.tenantId();
        otherOrgRenterId = b.renter().getId();
        features.setEnabled(otherTenantId, TenantFeature.RENTER_RATING, true);

        admin = TestIdentities.user(userRepo, UserRole.TENANT_ADMIN, tenantId);
        manager = TestIdentities.user(userRepo, UserRole.PROPERTY_MANAGER, tenantId);
        unassignedManager = TestIdentities.user(userRepo, UserRole.PROPERTY_MANAGER, tenantId);
        accountant = TestIdentities.user(userRepo, UserRole.ACCOUNTANT, tenantId);
        superAdmin = TestIdentities.user(userRepo, UserRole.SUPER_ADMIN, null);

        UserPropertyAssignment assignment = new UserPropertyAssignment();
        assignment.setUserId(manager);
        assignment.setPropertyId(a.property().getId());
        assignmentRepo.save(assignment);

        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
        job.runNowForTenant(tenantId, LocalDate.now());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
        features.setEnabled(tenantId, TenantFeature.RENTER_RATING, true);
    }

    private RestClient.ResponseSpec request(UUID userId, UserRole role, UUID tenant, HttpMethod method, String path,
                                            Object body) {
        RestClient.RequestBodySpec spec = RestClient.builder()
                .baseUrl("http://localhost:" + port).build()
                .method(method).uri(path)
                .header("X-User-Id", userId.toString())
                .header("X-User-Role", role.name());
        if (tenant != null) {
            spec = spec.header("X-Tenant-Id", tenant.toString()).header("X-User-Tenant-Id", tenant.toString());
        }
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.retrieve().onStatus(status -> true, (req, res) -> { });
    }

    private HttpStatusCode status(UUID userId, UserRole role, HttpMethod method, String path, Object body) {
        return request(userId, role, tenantId, method, path, body).toBodilessEntity().getStatusCode();
    }

    private Map<?, ?> json(UUID userId, UserRole role, String path) {
        return request(userId, role, tenantId, HttpMethod.GET, path, null).body(Map.class);
    }

    private String ratingPath() {
        return "/api/v1/renters/" + renterId + "/rating";
    }

    private UUID roleUser(UserRole role) {
        return TestIdentities.user(userRepo, role, tenantId);
    }

    @Test
    void featureOffReturns404() {
        features.setEnabled(tenantId, TenantFeature.RENTER_RATING, false);
        assertThat(status(admin, UserRole.TENANT_ADMIN, HttpMethod.GET, ratingPath(), null))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(admin, UserRole.TENANT_ADMIN, HttpMethod.GET, "/api/v1/renter-ratings", null))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(admin, UserRole.TENANT_ADMIN, HttpMethod.GET, "/api/v1/renter-ratings/export", null))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void staffRolesCanRead() {
        for (Object[] who : new Object[][]{{admin, UserRole.TENANT_ADMIN}, {manager, UserRole.PROPERTY_MANAGER},
                {accountant, UserRole.ACCOUNTANT}}) {
            Map<?, ?> body = json((UUID) who[0], (UserRole) who[1], ratingPath());
            assertThat(body.containsKey("grade") && body.containsKey("score") && body.containsKey("events")).isTrue();
        }
    }

    @Test
    void nonStaffDenied() {
        for (UserRole role : List.of(UserRole.RENTER, UserRole.SECURITY_GUARD, UserRole.TENANT_USER, UserRole.PROMOTER)) {
            UUID u = roleUser(role);
            assertThat(status(u, role, HttpMethod.GET, ratingPath(), null)).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(status(u, role, HttpMethod.GET, "/api/v1/renter-ratings", null)).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(status(u, role, HttpMethod.GET, "/api/v1/renter-ratings/export", null))
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void adjustAdminOnly() {
        Map<String, Object> body = Map.of("points", -5, "reason", "Late notice ignored");
        assertThat(status(manager, UserRole.PROPERTY_MANAGER, HttpMethod.POST, ratingPath() + "/adjustments", body))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(accountant, UserRole.ACCOUNTANT, HttpMethod.POST, ratingPath() + "/adjustments", body))
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> created = request(admin, UserRole.TENANT_ADMIN, tenantId, HttpMethod.POST,
                ratingPath() + "/adjustments", body).toEntity(Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String adjustmentId = String.valueOf(created.getBody().get("adjustmentId"));
        assertThat(created.getBody().get("createdByName")).isEqualTo("TENANT_ADMIN");
        List<?> events = (List<?>) json(manager, UserRole.PROPERTY_MANAGER, ratingPath()).get("events");
        assertThat(events).anySatisfy(e -> {
            Map<?, ?> m = (Map<?, ?>) e;
            assertThat(m.get("adjustmentId")).isEqualTo(adjustmentId);
            assertThat(m.get("createdByName")).isEqualTo("TENANT_ADMIN");
        });

        assertThat(status(manager, UserRole.PROPERTY_MANAGER, HttpMethod.DELETE,
                ratingPath() + "/adjustments/" + adjustmentId, null)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(admin, UserRole.TENANT_ADMIN, HttpMethod.DELETE,
                ratingPath() + "/adjustments/" + adjustmentId, null)).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void deleteAdjustmentViaAnotherRenterIs404AndKeepsRow() {
        ResponseEntity<Map> created = request(admin, UserRole.TENANT_ADMIN, tenantId, HttpMethod.POST,
                ratingPath() + "/adjustments", Map.of("points", 5, "reason", "Paid early")).toEntity(Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String adjustmentId = String.valueOf(created.getBody().get("adjustmentId"));
        UUID otherRenterSameOrg = orgA.createRenter("Other Renter").getId();

        assertThat(status(admin, UserRole.TENANT_ADMIN, HttpMethod.DELETE,
                "/api/v1/renters/" + otherRenterSameOrg + "/rating/adjustments/" + adjustmentId, null))
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(jdbc.queryForObject(
                "select count(*) from renter_rating_adjustments where id = ?::uuid and removed_at is null",
                Integer.class, adjustmentId)).isEqualTo(1);
    }

    @Test
    void invalidAdjustmentIs400() {
        assertThat(status(admin, UserRole.TENANT_ADMIN, HttpMethod.POST, ratingPath() + "/adjustments",
                Map.of("points", 99, "reason", "too big"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(admin, UserRole.TENANT_ADMIN, HttpMethod.POST, ratingPath() + "/adjustments",
                Map.of("points", 5, "reason", " "))).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void crossOrgRenter404() {
        String path = "/api/v1/renters/" + otherOrgRenterId + "/rating";
        assertThat(status(admin, UserRole.TENANT_ADMIN, HttpMethod.GET, path, null)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(admin, UserRole.TENANT_ADMIN, HttpMethod.POST, path + "/adjustments",
                Map.of("points", 5, "reason", "x"))).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void propertyManagerIsScoped() {
        assertThat(status(unassignedManager, UserRole.PROPERTY_MANAGER, HttpMethod.GET, ratingPath(), null))
                .isEqualTo(HttpStatus.NOT_FOUND);
        Map<?, ?> unassigned = json(unassignedManager, UserRole.PROPERTY_MANAGER, "/api/v1/renter-ratings");
        assertThat(((Number) unassigned.get("totalElements")).intValue()).isZero();
        Map<?, ?> assigned = json(manager, UserRole.PROPERTY_MANAGER, "/api/v1/renter-ratings");
        assertThat(((Number) assigned.get("totalElements")).intValue()).isEqualTo(1);
        Map<?, ?> admins = json(admin, UserRole.TENANT_ADMIN, "/api/v1/renter-ratings");
        assertThat(((Number) admins.get("totalElements")).intValue()).isEqualTo(1);
    }

    @Test
    void listRejectsTooManyRenterIds() {
        String ids = IntStream.range(0, 201).mapToObj(i -> UUID.randomUUID().toString())
                .collect(Collectors.joining(","));
        assertThat(status(admin, UserRole.TENANT_ADMIN, HttpMethod.GET, "/api/v1/renter-ratings?renterIds=" + ids, null))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        Map<?, ?> some = json(admin, UserRole.TENANT_ADMIN, "/api/v1/renter-ratings?renterIds=" + renterId);
        assertThat(((Number) some.get("totalElements")).intValue()).isEqualTo(1);
    }

    @Test
    void exportIsCsv() {
        ResponseEntity<String> res = request(admin, UserRole.TENANT_ADMIN, tenantId, HttpMethod.GET,
                "/api/v1/renter-ratings/export", null).toEntity(String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(res.getHeaders().getFirst("Content-Disposition")).contains("tenant-ratings.csv");
        String body = res.getBody().replace("﻿", "");
        assertThat(body).startsWith("Tenant,Grade,Score");
        assertThat(body.split("\r\n")).hasSize(2);
    }

    @Test
    void recomputeIsSuperAdminOnlyAndAsync() {
        String path = "/api/v1/admin/renter-ratings/recompute?tenantId=";
        clearInvocations(renterRatingExecutor);
        assertThat(status(admin, UserRole.TENANT_ADMIN, HttpMethod.POST, path + tenantId, null))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(request(superAdmin, UserRole.SUPER_ADMIN, null, HttpMethod.POST, path + tenantId, null)
                .toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(renterRatingExecutor, times(1)).execute(any(Runnable.class));
        assertThat(request(superAdmin, UserRole.SUPER_ADMIN, null, HttpMethod.POST, path + UUID.randomUUID(), null)
                .toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        features.setEnabled(tenantId, TenantFeature.RENTER_RATING, false);
        assertThat(request(superAdmin, UserRole.SUPER_ADMIN, null, HttpMethod.POST, path + tenantId, null)
                .toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
