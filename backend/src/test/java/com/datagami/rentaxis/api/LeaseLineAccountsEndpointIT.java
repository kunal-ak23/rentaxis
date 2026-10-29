package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Property;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Client request (2026-10-28): the New Contract grid's Ledger column fills in as
 * soon as the Particulars is chosen. {@code GET /api/v1/leases/line-accounts} says,
 * per charge type, the account a line saved without one is credited to on that
 * property — so what the grid shows before the save is what the save writes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaseLineAccountsEndpointIT extends AbstractPostgresIT {

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

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private User user(UserRole role, UUID tenantId) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        return userRepo.save(u);
    }

    private RestClient.ResponseSpec get(User caller, UUID propertyId) {
        return RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri("/api/v1/leases/line-accounts?propertyId=" + propertyId)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString())
                .retrieve().onStatus(status -> true, (request, response) -> { });
    }

    private Map<UUID, UUID> suggestions(User caller, UUID propertyId) {
        ResponseEntity<List<Map<String, String>>> res = get(caller, propertyId)
                .toEntity(new ParameterizedTypeReference<>() { });
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<UUID, UUID> out = new HashMap<>();
        for (Map<String, String> row : res.getBody()) {
            out.put(UUID.fromString(row.get("chargeTypeId")), UUID.fromString(row.get("accountId")));
        }
        return out;
    }

    @Test
    void suggestsExactlyTheAccountsADraftSavedWithoutOneIsCreditedTo() {
        User admin = user(UserRole.TENANT_ADMIN, fixtures.tenantId());
        Map<UUID, UUID> suggested = suggestions(admin, fixtures.property().getId());

        CreateLeaseDTO dto = fixtures.draftDto(LocalDate.of(2026, 11, 1), LocalDate.of(2027, 10, 31),
                List.of(line("RENT", "60000"), line("SECURITY_DEPOSIT", "5000"), line("ADMIN_FEE", "1000")));
        UUID leaseId = leaseService.createDraftLease(dto).getId();
        List<LeaseLineDTO> lines = leaseService.getLeaseById(leaseId).getLines();

        assertThat(lines).hasSize(3);
        for (LeaseLineDTO l : lines) {
            assertThat(l.creditAccountId()).as(l.chargeTypeCode()).isNotNull();
            assertThat(suggested.get(l.chargeTypeId())).as(l.chargeTypeCode()).isEqualTo(l.creditAccountId());
        }
        // Per property: the rent of another building is credited to that building's own income leaf.
        UUID rentType = lines.get(0).chargeTypeId();
        Property other = fixtures.createProperty("OTH");
        assertThat(suggestions(admin, other.getId()).get(rentType)).isNotNull()
                .isNotEqualTo(suggested.get(rentType));
    }

    @Test
    void anotherOrganisationsPropertyIsNotFound() {
        UUID otherProperty = fixtures.property().getId();
        UUID secondTenant = fixtures.newTenant();
        User outsider = user(UserRole.TENANT_ADMIN, secondTenant);

        assertThat(get(outsider, otherProperty).toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * The roles that edit a contract's lines somewhere — the wizard and draft editor
     * (admins), amend/extend/addenda (an accountant too), renew (a manager too) —
     * and a manager only for the buildings they are assigned to.
     */
    @Test
    void theRolesThatEditLinesMayAskAndAManagerOnlyForTheirBuildings() {
        UUID property = fixtures.property().getId();
        assertThat(suggestions(user(UserRole.ACCOUNTANT, fixtures.tenantId()), property)).isNotEmpty();

        User manager = user(UserRole.PROPERTY_MANAGER, fixtures.tenantId());
        assertThat(get(manager, property).toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        UserPropertyAssignment assignment = new UserPropertyAssignment();
        assignment.setUserId(manager.getId());
        assignment.setPropertyId(property);
        assignmentRepo.save(assignment);
        assertThat(suggestions(manager, property)).isNotEmpty();

        assertThat(get(user(UserRole.RENTER, fixtures.tenantId()), property).toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
