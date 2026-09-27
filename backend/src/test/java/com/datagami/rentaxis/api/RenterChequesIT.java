package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.cheque.ReplaceChequeRequest;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
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
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scale #14: the renter detail page reads every cheque of the renter's contracts in one
 * call. The rows must be exactly what the page used to assemble from one
 * {@code GET /leases/{id}/cheques} per lease of {@code GET /renters/{id}/leases} — same
 * leases, same rows, same fields, same order — for an admin and for a property manager,
 * and a renter of another organisation is a 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RenterChequesIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService generation;
    @Autowired LeasePostingService posting;
    @Autowired ChequeService chequeService;
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
    private static final LocalDate DEPOSIT_DATE = LocalDate.of(2026, 10, 5);
    private static final LocalDate BOUNCE_DATE = LocalDate.of(2026, 10, 12);
    private static final LocalDate REPLACE_DATE = LocalDate.of(2026, 10, 15);

    private final ObjectMapper json = new ObjectMapper();

    private LeaseTestFixtures a;
    private LeaseTestFixtures b;
    private User admin;
    private User pm;
    private User otherOrgAdmin;
    private Property palm;
    private Renter renter;
    private Renter noContracts;
    private UUID postedLease;
    private UUID draftLease;
    private UUID palmDraftLease;

    @BeforeEach
    void setUp() {
        // Organisation B: only its admin is needed, to ask for A's renter.
        b = fixtures().bootstrap();
        otherOrgAdmin = user(b.tenantId(), UserRole.TENANT_ADMIN);

        // Organisation A.
        a = fixtures().bootstrap().withLeaseServices(leaseService, generation, posting);
        Property marina = a.property();
        palm = a.createProperty("PALM");
        Unit u0701 = a.createUnit(marina, "07-01");
        Unit u0702 = a.createUnit(marina, "07-02");
        Unit palmUnit = a.createUnit(palm, "901");
        renter = a.createRenter("Cheque Renter");
        noContracts = a.createRenter("No Contracts Yet");

        // A posted contract whose register holds REGISTERED, REPLACED (bounced, then replaced) and CANCELLED rows.
        PostLeaseResponse posted = a.postedLease(u0701, renter, CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, null);
        postedLease = posted.lease().getId();
        List<ChequeDTO> register = posted.cheques();
        UUID first = register.get(0).id();
        chequeService.deposit(first, ChequeActionRequest.on(DEPOSIT_DATE));
        chequeService.bounce(first, new ChequeActionRequest(BOUNCE_DATE, null, ChequeFailureReason.BOUNCE, null));
        chequeService.replace(first, new ReplaceChequeRequest(List.of(new ChequeRowInput(null, null, REPLACE_DATE,
                LeaseTestFixtures.nextChequeNumber(), LocalDate.of(2026, 11, 1), "Emirates NBD", null, null,
                register.get(0).amount(), "Replacement", ChequeMode.PDC)), REPLACE_DATE, "Replaced"));
        chequeService.cancel(register.get(2).id(), ChequeActionRequest.on(DEPOSIT_DATE));

        // A draft contract with a DRAFT grid on the same building, and one on another building.
        draftLease = a.draftLease(u0702, renter, LocalDate.now(), LocalDate.now().plusMonths(2),
                LocalDate.now().plusMonths(2).plusYears(1).minusDays(1), List.of(line("RENT", "36000")));
        a.unnumberedGrid(draftLease, 2, LocalDate.now().plusMonths(2));
        palmDraftLease = a.draftLease(palmUnit, renter, LocalDate.now(), LocalDate.now().plusMonths(3),
                LocalDate.now().plusMonths(3).plusYears(1).minusDays(1), List.of(line("RENT", "30000")));
        a.unnumberedGrid(palmDraftLease, 3, LocalDate.now().plusMonths(3));

        admin = user(a.tenantId(), UserRole.TENANT_ADMIN);
        pm = user(a.tenantId(), UserRole.PROPERTY_MANAGER);
        UserPropertyAssignment asg = new UserPropertyAssignment();
        asg.setUserId(pm.getId());
        asg.setPropertyId(marina.getId());
        assignmentRepo.save(asg);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @Test
    void anAdminGetsEveryRowOfEveryContractExactlyAsThePerLeaseReadsGaveThem() {
        JsonNode rows = get(admin, a.tenantId(), "/api/v1/renters/" + renter.getId() + "/cheques");

        assertThat(rows).isEqualTo(perLeaseConcatenation(admin));
        List<String> statuses = values(rows, "status");
        assertThat(statuses).contains("REGISTERED", "REPLACED", "CANCELLED", "DRAFT");
        assertThat(values(rows, "leaseId")).containsOnly(postedLease.toString(), draftLease.toString(),
                palmDraftLease.toString());
        // 4 rent + 1 admin fee on the posted contract, plus its replacement; 2 + 3 draft rows.
        assertThat(rows).hasSize(6 + 2 + 3);
    }

    @Test
    void aPropertyManagerGetsOnlyTheContractsOnTheirBuildings() {
        JsonNode rows = get(pm, a.tenantId(), "/api/v1/renters/" + renter.getId() + "/cheques");

        assertThat(values(rows, "propertyId")).doesNotContain(palm.getId().toString());
        assertThat(values(rows, "leaseId")).doesNotContain(palmDraftLease.toString())
                .contains(postedLease.toString(), draftLease.toString());
        assertThat(rows).hasSize(6 + 2);
        assertThat(rows).isEqualTo(perLeaseConcatenation(pm));
    }

    @Test
    void anotherOrganisationsRenterIsNotFound() {
        ResponseEntity<String> res = call(otherOrgAdmin, b.tenantId(),
                "/api/v1/renters/" + renter.getId() + "/cheques");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).doesNotContain(postedLease.toString());

        assertThat(call(admin, a.tenantId(), "/api/v1/renters/" + UUID.randomUUID() + "/cheques").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aRenterWithNoContractsHasNoCheques() {
        assertThat(get(admin, a.tenantId(), "/api/v1/renters/" + noContracts.getId() + "/cheques")).isEmpty();
    }

    @Test
    void rolesAreThoseOfTheRenterRead() {
        User accountant = user(a.tenantId(), UserRole.ACCOUNTANT);
        assertThat(call(accountant, a.tenantId(), "/api/v1/renters/" + renter.getId() + "/cheques")
                .getStatusCode().is2xxSuccessful()).isTrue();
        User tenantUser = user(a.tenantId(), UserRole.TENANT_USER);
        assertThat(call(tenantUser, a.tenantId(), "/api/v1/renters/" + renter.getId() + "/cheques")
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ helpers

    /** What the page used to build: GET /renters/{id}/leases, then GET /leases/{id}/cheques per lease. */
    private JsonNode perLeaseConcatenation(User caller) {
        ArrayNode out = json.createArrayNode();
        JsonNode leases = get(caller, a.tenantId(), "/api/v1/renters/" + renter.getId() + "/leases");
        assertThat(leases.size()).isGreaterThan(1);
        for (JsonNode lease : leases) {
            out.addAll((ArrayNode) get(caller, a.tenantId(), "/api/v1/leases/" + lease.get("id").asText() + "/cheques"));
        }
        return out;
    }

    private static List<String> values(JsonNode rows, String field) {
        List<String> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.path(field).asText(null)));
        return out;
    }

    private LeaseTestFixtures fixtures() {
        return new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo, propertyService,
                accountService, propertyAccountService, chargeTypeService)
                .withLeaseServices(leaseService, generation, posting);
    }

    private User user(UUID tenantId, UserRole role) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        return userRepo.save(u);
    }

    private ResponseEntity<String> call(User caller, UUID tenantId, String path) {
        return RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Tenant-Id", tenantId.toString())
                .retrieve().onStatus(s -> true, (req, res) -> { })
                .toEntity(String.class);
    }

    private JsonNode get(User caller, UUID tenantId, String path) {
        ResponseEntity<String> res = call(caller, tenantId, path);
        assertThat(res.getStatusCode().is2xxSuccessful()).as("%s -> %s %s", path, res.getStatusCode(), res.getBody())
                .isTrue();
        try {
            return json.readTree(res.getBody());
        } catch (Exception e) {
            throw new AssertionError("Not JSON: " + res.getBody(), e);
        }
    }
}
