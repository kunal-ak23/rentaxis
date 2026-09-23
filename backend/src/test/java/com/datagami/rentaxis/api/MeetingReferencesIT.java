package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 5, audit D-F4: what a meeting request may point at. A renter names only
 * their own lease (and so their own unit, property and cheques); the host is a
 * tenant admin or property manager of the tenant; and the response never carries
 * another renter's name, which the lease label used to read back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeetingReferencesIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired com.datagami.rentaxis.core.service.lease.LeasePostingService leasePosting;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;

    private final ObjectMapper json = new ObjectMapper();
    private LeaseTestFixtures fixtures;
    private User host;
    private User alice;
    private Renter bob;
    private UUID aliceLease;
    private UUID bobLease;
    private Unit bobUnit;
    private List<ChequeDTO> bobCheques;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, leasePosting);
        Renter aliceRenter = fixtures.createRenter("Alice Resident");
        alice = userRepo.findById(aliceRenter.getUserId()).orElseThrow();
        bob = fixtures.createRenter("Bob Neighbour");
        bobUnit = fixtures.createUnit(fixtures.property(), "1203");
        aliceLease = fixtures.draftLease(fixtures.unit(), aliceRenter, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 10, 1), LocalDate.of(2027, 9, 30), List.of(line("RENT", "60000")));
        bobLease = fixtures.draftLease(bobUnit, bob, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 10, 1), LocalDate.of(2027, 9, 30), List.of(line("RENT", "60000")));
        bobCheques = fixtures.generateGrid(bobLease, 2, LocalDate.of(2026, 10, 1));

        host = new User();
        host.setEmail("host-" + UUID.randomUUID() + "@t.io");
        host.setName("Front Office");
        host.setRole(UserRole.TENANT_ADMIN);
        host.setStatus(UserStatus.ACTIVE);
        host.setPasswordHash("x");
        host.setTenantId(fixtures.tenantId());
        host = userRepo.save(host);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @Test
    void aRenterCannotAttachAMeetingToAnotherRentersLeaseOrReadTheirName() {
        ResponseEntity<String> theirs = create(alice, Map.of("leaseId", bobLease.toString()), 1);
        assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(theirs.getBody()).doesNotContain("Bob Neighbour");

        ResponseEntity<String> own = create(alice, Map.of("leaseId", aliceLease.toString()), 2);
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(own.getBody()).contains("Alice Resident").doesNotContain("Bob Neighbour");
    }

    @Test
    void aRenterCannotPointAtAnotherUnitOrAnotherLeasesCheques() {
        assertThat(create(alice, Map.of("leaseId", aliceLease.toString(), "unitId", bobUnit.getId().toString()), 3)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(create(alice, Map.of("leaseId", aliceLease.toString(),
                "purpose", "CHEQUE_REPLACEMENT",
                "chequeIds", List.of(bobCheques.get(0).id().toString())), 4).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(create(alice, Map.of("unitId", bobUnit.getId().toString()), 5).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theHostMustBeStaffOfTheTenant() {
        User bobUser = userRepo.findById(bob.getUserId()).orElseThrow();
        ResponseEntity<String> renterHost = create(alice, Map.of("hostUserId", bobUser.getId().toString()), 6);
        assertThat(renterHost.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Nor can a renter read another renter's calendar through the slot finder.
        String date = LocalDate.now(ZoneId.of("Asia/Dubai")).plusDays(3).toString();
        assertThat(call(alice, HttpMethod.GET, "/api/v1/meetings/slots?hostUserId=" + bobUser.getId()
                + "&date=" + date, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(alice, HttpMethod.GET, "/api/v1/meetings/slots?hostUserId=" + host.getId()
                + "&date=" + date, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> create(User caller, Map<String, Object> extra, int slotOffset) {
        ZonedDateTime slot = ZonedDateTime.now(ZoneId.of("Asia/Dubai")).plusDays(3)
                .withHour(10).withMinute(0).withSecond(0).withNano(0).plusMinutes(30L * slotOffset);
        Map<String, Object> body = new HashMap<>();
        body.put("type", "OFFICE_VISIT");
        body.put("purpose", "OTHER");
        body.put("slotStart", slot.toInstant().toString());
        body.put("hostUserId", host.getId().toString());
        body.putAll(extra);
        return call(caller, HttpMethod.POST, "/api/v1/meetings", body);
    }

    private ResponseEntity<String> call(User caller, HttpMethod method, String path, Object body) {
        RestClient.RequestBodySpec spec = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .method(method).uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", fixtures.tenantId().toString())
                .header("X-User-Tenant-Id", fixtures.tenantId().toString());
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }
}
