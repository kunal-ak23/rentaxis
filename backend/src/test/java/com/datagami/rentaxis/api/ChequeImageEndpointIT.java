package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/v1/cheques/{id}/image} streams a cheque's attached scan out of the
 * organisation's private container (PR #396 follow-up: the stored blob URL answers
 * 403, so nothing could show a scan). Staff in scope see it; a property manager only
 * for their buildings; a renter only their own rows; another organisation, a draft
 * row asked for by a renter, and a row with no scan are all 404 — and none of those
 * ever touches the container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChequeImageEndpointIT extends AbstractPostgresIT {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    @LocalServerPort int port;

    @MockitoBean BlobStorageService blob;

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
    @Autowired ChequeRepository chequeRepo;

    private LeaseTestFixtures a;
    private UUID tenantA;
    private UUID tenantB;
    private Property marina;
    private Renter renter;
    private Renter otherRenter;
    private UUID scanned;
    private UUID unscanned;
    private UUID otherRentersScan;
    private UUID draftScan;

    @BeforeEach
    void setUp() {
        LeaseTestFixtures b = fixtures().bootstrap();
        tenantB = b.tenantId();

        a = fixtures().bootstrap().withLeaseServices(leaseService, generation, posting);
        tenantA = a.tenantId();
        marina = a.property();
        Unit u1 = a.createUnit(marina, "IMG-1");
        Unit u2 = a.createUnit(marina, "IMG-2");
        Unit u3 = a.createUnit(marina, "IMG-3");
        renter = a.createRenter("Scan Renter");
        otherRenter = a.createRenter("Other Scan Renter");

        List<ChequeDTO> mine = a.postedLease(u1, renter, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 1),
                LocalDate.of(2027, 1, 31), List.of(line("RENT", "48000")), 2,
                LeaseTestFixtures.nextChequeNumber()).cheques();
        scanned = mine.get(0).id();
        unscanned = mine.get(1).id();
        attach(scanned, "cheques/" + UUID.randomUUID() + ".png");

        List<ChequeDTO> theirs = a.postedLease(u2, otherRenter, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 1),
                LocalDate.of(2027, 1, 31), List.of(line("RENT", "36000")), 2,
                LeaseTestFixtures.nextChequeNumber()).cheques();
        otherRentersScan = theirs.get(0).id();
        attach(otherRentersScan, "cheques/" + UUID.randomUUID() + ".jpg");

        UUID draft = a.draftLease(u3, renter, LocalDate.of(2026, 1, 5), LocalDate.of(2027, 3, 1),
                LocalDate.of(2028, 2, 29), List.of(line("RENT", "30000")));
        draftScan = a.generateGrid(draft, 2, LocalDate.of(2027, 3, 1)).get(0).id();
        attach(draftScan, "cheques/" + UUID.randomUUID() + ".jpeg");

        when(blob.download(eq(tenantA), any())).thenReturn(new BlobStorageService.DownloadResult(PNG, null));
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @Test
    void financeRolesStreamTheScanWithAnImageTypeAndNoCaching() {
        for (UserRole role : List.of(UserRole.TENANT_ADMIN, UserRole.ACCOUNTANT, UserRole.SUPER_ADMIN)) {
            ResponseEntity<byte[]> res = get(user(tenantA, role), scanned);
            assertThat(res.getStatusCode().value()).as(role.name()).isEqualTo(200);
            assertThat(res.getBody()).isEqualTo(PNG);
            assertThat(res.getHeaders().getContentType()).hasToString("image/png");
            assertThat(res.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(res.getHeaders().getCacheControl()).contains("private");
        }
        // The type comes from the path when the blob was stored without one.
        assertThat(get(user(tenantA, UserRole.TENANT_ADMIN), otherRentersScan).getHeaders().getContentType())
                .hasToString("image/jpeg");
        // Staff see a draft contract's scan: a scan attaches to a draft grid.
        assertThat(get(user(tenantA, UserRole.TENANT_ADMIN), draftScan).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void aPropertyManagerSeesTheirOwnBuildingsScansOnly() {
        User unassigned = user(tenantA, UserRole.PROPERTY_MANAGER);
        assertThat(get(unassigned, scanned).getStatusCode().value()).isEqualTo(404);

        User assigned = user(tenantA, UserRole.PROPERTY_MANAGER);
        UserPropertyAssignment link = new UserPropertyAssignment();
        link.setUserId(assigned.getId());
        link.setPropertyId(marina.getId());
        assignmentRepo.save(link);
        assertThat(get(assigned, scanned).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void aRenterSeesTheirOwnPostedRowsOnly() {
        User renterUser = userRepo.findById(renter.getUserId()).orElseThrow();
        assertThat(get(renterUser, scanned).getStatusCode().value()).isEqualTo(200);
        assertThat(get(renterUser, otherRentersScan).getStatusCode().value()).isEqualTo(404);
        assertThat(get(renterUser, draftScan).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void anotherOrganisationAMissingScanAndAnUnknownIdAreNotFoundAndNeverReadTheContainer() {
        assertThat(get(user(tenantB, UserRole.TENANT_ADMIN), scanned).getStatusCode().value()).isEqualTo(404);
        assertThat(get(user(tenantB, UserRole.ACCOUNTANT), scanned).getStatusCode().value()).isEqualTo(404);
        assertThat(get(user(tenantA, UserRole.TENANT_ADMIN), unscanned).getStatusCode().value()).isEqualTo(404);
        assertThat(get(user(tenantA, UserRole.TENANT_ADMIN), UUID.randomUUID()).getStatusCode().value()).isEqualTo(404);
        verify(blob, never()).download(eq(tenantB), any());
    }

    @Test
    void otherRolesAreRefused() {
        assertThat(get(user(tenantA, UserRole.TENANT_USER), scanned).getStatusCode().value()).isIn(403, 404);
    }

    private void attach(UUID chequeId, String path) {
        Cheque c = chequeRepo.findById(chequeId).orElseThrow();
        c.setImageBlobPath(path);
        c.setImageUrl("https://example.blob.core.windows.net/tenant-x/" + path);
        chequeRepo.save(c);
    }

    private LeaseTestFixtures fixtures() {
        return new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService);
    }

    private User user(UUID tenantId, UserRole role) {
        User u = new User();
        u.setEmail("img-" + role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        return userRepo.save(u);
    }

    private ResponseEntity<byte[]> get(User caller, UUID chequeId) {
        return RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri("/api/v1/cheques/" + chequeId + "/image")
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString())
                .retrieve().onStatus(s -> true, (req, res) -> { })
                .toEntity(byte[].class);
    }
}
