package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Break round 1, F4: a PROPERTY_MANAGER could read the full profile of any renter in
 * the organisation through {@code GET /renters/{id}} and list every one through the
 * unpaged {@code GET /renters}, although {@code /renters/paged}, {@code /search} and
 * {@code /names} already limited them to renters with a contract in their buildings
 * (or none yet). Every renter read now applies that one rule; an out-of-scope id is a
 * 404, the same answer as another organisation's id. Full HTTP with the proxy's
 * headers, because the principal only exists after the filter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RenterPropertyScopeIT extends AbstractPostgresIT {

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

    private final ObjectMapper json = new ObjectMapper();

    private LeaseTestFixtures fixtures;
    private User admin;
    private User pm;
    private Property marina;
    private Renter marinaRenter;
    private Renter palmRenter;
    private Renter bothRenter;
    private Renter newRenter;
    private Renter foreignRenter;

    @BeforeEach
    void setUp() {
        // Another organisation's renter, for the "same answer as a foreign id" check.
        LeaseTestFixtures foreign = fixtures().bootstrap();
        foreignRenter = foreign.renter();

        fixtures = fixtures().bootstrap();
        marina = fixtures.property();
        Property palm = fixtures.createProperty("PALM");
        Unit marinaUnit = fixtures.unit();
        Unit marinaUnit2 = fixtures.createUnit(marina, "102");
        Unit palmUnit = fixtures.createUnit(palm, "901");
        Unit palmUnit2 = fixtures.createUnit(palm, "902");

        marinaRenter = fixtures.renter();
        palmRenter = fixtures.createRenter("Palm Renter");
        bothRenter = fixtures.createRenter("Both Renter");
        newRenter = fixtures.createRenter("New Renter");
        lease(marinaUnit, marinaRenter);
        lease(palmUnit, palmRenter);
        lease(marinaUnit2, bothRenter);
        lease(palmUnit2, bothRenter);

        admin = user(UserRole.TENANT_ADMIN);
        pm = user(UserRole.PROPERTY_MANAGER);
        UserPropertyAssignment a = new UserPropertyAssignment();
        a.setUserId(pm.getId());
        a.setPropertyId(marina.getId());
        assignmentRepo.save(a);
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @Test
    void aManagerReadsOnlyRentersInTheirBuildingsById() {
        assertThat(status(pm, "/api/v1/renters/" + palmRenter.getId()))
                .as("break round 1 F4: the other building's renter profile").isEqualTo(404);
        assertThat(status(pm, "/api/v1/renters/" + foreignRenter.getId())).isEqualTo(404);
        assertThat(status(pm, "/api/v1/renters/" + marinaRenter.getId())).isEqualTo(200);
        assertThat(status(pm, "/api/v1/renters/" + bothRenter.getId())).isEqualTo(200);
        assertThat(status(pm, "/api/v1/renters/" + newRenter.getId()))
                .as("a renter with no contract yet must stay findable to be given one").isEqualTo(200);

        assertThat(status(admin, "/api/v1/renters/" + palmRenter.getId())).isEqualTo(200);
        assertThat(status(admin, "/api/v1/renters/" + foreignRenter.getId())).isEqualTo(404);
    }

    @Test
    void theUnpagedListMatchesThePagedOne() {
        List<String> unpaged = ids(get(pm, "/api/v1/renters"));
        assertThat(unpaged).containsExactlyInAnyOrder(
                marinaRenter.getId().toString(), bothRenter.getId().toString(), newRenter.getId().toString());
        List<String> paged = ids(get(pm, "/api/v1/renters/paged?size=200").get("content"));
        assertThat(unpaged).containsExactlyInAnyOrderElementsOf(paged);

        assertThat(ids(get(admin, "/api/v1/renters"))).contains(palmRenter.getId().toString())
                .doesNotContain(foreignRenter.getId().toString());
    }

    @Test
    void theRentersLeasesAndChequesFollowTheSameRule() {
        for (String sub : List.of("/leases", "/cheques")) {
            assertThat(status(pm, "/api/v1/renters/" + palmRenter.getId() + sub)).as(sub).isEqualTo(404);
            assertThat(status(pm, "/api/v1/renters/" + foreignRenter.getId() + sub)).as(sub).isEqualTo(404);
            assertThat(status(pm, "/api/v1/renters/" + marinaRenter.getId() + sub)).as(sub).isEqualTo(200);
            assertThat(status(admin, "/api/v1/renters/" + palmRenter.getId() + sub)).as(sub).isEqualTo(200);
        }
        // The renter on both buildings: the manager sees only the Marina contract.
        assertThat(get(pm, "/api/v1/renters/" + bothRenter.getId() + "/leases")).hasSize(1);
        assertThat(get(admin, "/api/v1/renters/" + bothRenter.getId() + "/leases")).hasSize(2);
    }

    @Test
    void unassigningTheManagerHidesTheRenterOnTheNextRequest() {
        assertThat(status(pm, "/api/v1/renters/" + marinaRenter.getId())).isEqualTo(200);
        assignmentRepo.deleteAll(assignmentRepo.findByUserId(pm.getId()));
        assertThat(status(pm, "/api/v1/renters/" + marinaRenter.getId())).isEqualTo(404);
        assertThat(ids(get(pm, "/api/v1/renters"))).containsExactly(newRenter.getId().toString());
    }

    // -------------------------------------------------------------- plumbing

    private LeaseTestFixtures fixtures() {
        return new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService);
    }

    private void lease(Unit unit, Renter renter) {
        CreateLeaseDTO dto = fixtures.draftDto(unit, renter, LocalDate.of(2026, 10, 2),
                LocalDate.of(2027, 10, 1), List.of(line("RENT", "51000")));
        dto.setContractDate(LocalDate.of(2026, 9, 18));
        dto.setFirstDueDate(LocalDate.of(2026, 10, 2));
        leaseService.createDraftLease(dto);
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

    private ResponseEntity<String> call(User caller, String path) {
        return RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", fixtures.tenantId().toString())
                .header("X-User-Tenant-Id", fixtures.tenantId().toString())
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    private int status(User caller, String path) {
        return call(caller, path).getStatusCode().value();
    }

    private JsonNode get(User caller, String path) {
        ResponseEntity<String> res = call(caller, path);
        assertThat(res.getStatusCode().value()).as(path + " " + res.getBody()).isEqualTo(200);
        try {
            return json.readTree(res.getBody());
        } catch (Exception e) {
            throw new AssertionError("Not JSON: " + res.getBody(), e);
        }
    }

    private static List<String> ids(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get("id").asText()));
        return out;
    }
}
