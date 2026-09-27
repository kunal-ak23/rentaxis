package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.GatePass;
import com.datagami.rentaxis.domain.entity.GatePassScan;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.GatePassOrigin;
import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import com.datagami.rentaxis.domain.entity.enums.GatePassType;
import com.datagami.rentaxis.domain.entity.enums.GateVisitorType;
import com.datagami.rentaxis.domain.entity.enums.ScanDirection;
import com.datagami.rentaxis.domain.entity.enums.ScanResult;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.GatePassRepository;
import com.datagami.rentaxis.domain.repository.GatePassScanRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scale #15: the gate-pass report is paged in the database. {@code /report/paged} must
 * serve exactly the rows {@code /report} serves (same fields), newest first, a page at a
 * time — with the property filter and a property manager's buildings applied in SQL, and
 * nothing of another organisation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatePassReportPagedIT extends AbstractPostgresIT {

    private static final Instant BASE = Instant.parse("2026-06-01T08:00:00Z");
    private static final String WINDOW = "from=2026-05-01T00:00:00Z&to=2026-07-01T00:00:00Z";

    @LocalServerPort int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;
    @Autowired GatePassRepository passRepo;
    @Autowired GatePassScanRepository scanRepo;

    private final ObjectMapper json = new ObjectMapper();

    private LeaseTestFixtures a;
    private User admin;
    private User pm;
    private User guard;
    private User otherOrgAdmin;
    private UUID otherTenant;
    private Property marina;
    private Property palm;

    @BeforeEach
    void setUp() {
        LeaseTestFixtures b = fixtures().bootstrap();
        otherTenant = b.tenantId();
        otherOrgAdmin = user(otherTenant, UserRole.TENANT_ADMIN, "Other Admin");

        a = fixtures().bootstrap();
        marina = a.property();
        palm = a.createProperty("PALM");
        Unit marinaUnit = a.createUnit(marina, "07-01");
        Unit palmUnit = a.createUnit(palm, "901");

        admin = user(a.tenantId(), UserRole.TENANT_ADMIN, "Admin");
        guard = user(a.tenantId(), UserRole.SECURITY_GUARD, "Gate Guard Omar");
        pm = user(a.tenantId(), UserRole.PROPERTY_MANAGER, "Marina Manager");
        UserPropertyAssignment asg = new UserPropertyAssignment();
        asg.setUserId(pm.getId());
        asg.setPropertyId(marina.getId());
        assignmentRepo.save(asg);

        // 30 scans in the window, alternating buildings, three passes per building,
        // one minute apart so "newest first" has exactly one answer.
        List<GatePass> marinaPasses = List.of(pass(marina, marinaUnit, "M1"), pass(marina, marinaUnit, "M2"),
                pass(marina, marinaUnit, "M3"));
        List<GatePass> palmPasses = List.of(pass(palm, palmUnit, "P1"), pass(palm, palmUnit, "P2"),
                pass(palm, palmUnit, "P3"));
        for (int i = 0; i < 30; i++) {
            List<GatePass> passes = i % 2 == 0 ? marinaPasses : palmPasses;
            scan(passes.get((i / 2) % 3), BASE.plus(i, ChronoUnit.MINUTES), i % 3 == 0 ? ScanResult.REJECTED
                    : ScanResult.ALLOWED);
        }
        // Outside the window: never reported.
        scan(marinaPasses.get(0), Instant.parse("2025-01-01T00:00:00Z"), ScanResult.ALLOWED);

        // Another organisation's traffic in the same window.
        Property otherProperty = b.property();
        Unit otherUnit = b.createUnit(otherProperty, "X-1");
        GatePass otherPass = pass(otherTenant, otherProperty, otherUnit, "Other guest", admin.getId());
        GatePassScan otherScan = scanOf(otherTenant, otherPass, BASE.plus(5, ChronoUnit.SECONDS), ScanResult.ALLOWED,
                null);
        scanRepo.save(otherScan);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @Test
    void theFirstPageIsTheTenNewestScansOfThirty() {
        JsonNode page = get(admin, a.tenantId(), "/api/v1/gatepass/report/paged?" + WINDOW + "&page=0&size=10");

        assertThat(page.get("totalElements").asInt()).isEqualTo(30);
        assertThat(page.get("content")).hasSize(10);
        List<String> times = values(page.get("content"), "scannedAt");
        List<String> expected = new ArrayList<>();
        for (int i = 29; i >= 20; i--) {
            expected.add(BASE.plus(i, ChronoUnit.MINUTES).toString());
        }
        assertThat(times.stream().map(t -> Instant.parse(t).toString()).toList()).isEqualTo(expected);
        JsonNode first = page.get("content").get(0);
        assertThat(first.get("scannedByName").asText()).isEqualTo("Gate Guard Omar");
        assertThat(first.get("unitNumber").asText()).isEqualTo("901");
    }

    @Test
    void everyPageTogetherIsTheFullReportNewestFirstFieldForField() {
        assertThat(allPages(admin, "")).isEqualTo(reversedReport(admin, ""));
    }

    @Test
    void thePropertyFilterRunsInTheQuery() {
        JsonNode page = get(admin, a.tenantId(),
                "/api/v1/gatepass/report/paged?" + WINDOW + "&propertyId=" + palm.getId() + "&page=0&size=10");
        assertThat(page.get("totalElements").asInt()).isEqualTo(15);
        assertThat(values(page.get("content"), "propertyId")).containsOnly(palm.getId().toString());

        String filter = "&propertyId=" + palm.getId();
        List<JsonNode> all = allPages(admin, filter);
        assertThat(all).hasSize(15).isEqualTo(reversedReport(admin, filter));
    }

    @Test
    void aPropertyManagerSeesOnlyTheirBuildingsWithOrWithoutAPropertyNamed() {
        JsonNode page = get(pm, a.tenantId(), "/api/v1/gatepass/report/paged?" + WINDOW + "&page=0&size=10");
        assertThat(page.get("totalElements").asInt()).isEqualTo(15);
        assertThat(values(page.get("content"), "propertyId")).containsOnly(marina.getId().toString());
        List<JsonNode> unnamed = allPages(pm, "");
        assertThat(unnamed).hasSize(15).isEqualTo(reversedReport(pm, ""));

        String own = "&propertyId=" + marina.getId();
        assertThat(allPages(pm, own)).hasSize(15).isEqualTo(reversedReport(pm, own));

        // Another building: refused exactly as /report refuses it.
        String foreign = "&propertyId=" + palm.getId();
        ResponseEntity<String> paged = call(pm, a.tenantId(),
                "/api/v1/gatepass/report/paged?" + WINDOW + foreign + "&page=0&size=10");
        assertThat(paged.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(paged.getBody()).doesNotContain("Guest P1");
        assertThat(call(pm, a.tenantId(), "/api/v1/gatepass/report?" + WINDOW + foreign).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aPropertyManagerWithNoBuildingsSeesNothing() {
        User unassigned = user(a.tenantId(), UserRole.PROPERTY_MANAGER, "Unassigned");
        JsonNode page = get(unassigned, a.tenantId(), "/api/v1/gatepass/report/paged?" + WINDOW + "&page=0&size=10");
        assertThat(page.get("totalElements").asInt()).isZero();
        assertThat(get(unassigned, a.tenantId(), "/api/v1/gatepass/report?" + WINDOW)).isEmpty();
    }

    @Test
    void anotherOrganisationSeesNoneOfIt() {
        JsonNode page = get(otherOrgAdmin, otherTenant, "/api/v1/gatepass/report/paged?" + WINDOW + "&page=0&size=50");
        assertThat(page.get("totalElements").asInt()).isEqualTo(1);
        assertThat(values(page.get("content"), "guestName")).containsExactly("Other guest");
        // Its only scan has no guard (a deleted guard is detached to null): no name, no 500 —
        // on /report too, whose name lookup threw on Map.of().get(null) before this change.
        assertThat(page.get("content").get(0).get("scannedByName").isNull()).isTrue();
        assertThat(get(otherOrgAdmin, otherTenant, "/api/v1/gatepass/report?" + WINDOW)).hasSize(1);

        // And A's property named by B's admin finds nothing of A's.
        JsonNode named = get(otherOrgAdmin, otherTenant,
                "/api/v1/gatepass/report/paged?" + WINDOW + "&propertyId=" + marina.getId() + "&page=0&size=50");
        assertThat(named.get("totalElements").asInt()).isZero();
    }

    @Test
    void theQueryBindsTheTenantItselfOnBothTables() {
        // Called with no transaction, so the Hibernate tenantFilter is not in play: only the
        // query's own tenant_id binding keeps A's 30 scans out of B's answer.
        TenantContextHolder.clear();
        // A corrupt cross-tenant link: a B scan pointing at one of A's passes. Binding only
        // the scan's tenant would hand B that A guest; binding the pass's tenant drops it.
        GatePass aPass = passRepo.findAll().stream()
                .filter(p -> a.tenantId().equals(p.getTenantId())).findFirst().orElseThrow();
        scanRepo.save(scanOf(otherTenant, aPass, BASE.plus(7, ChronoUnit.SECONDS), ScanResult.ALLOWED, null));
        var page = scanRepo.findReportPage(otherTenant, Instant.parse("2026-05-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:00Z"), null, true, List.of(new UUID(0L, 0L)),
                org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).allSatisfy(row ->
                assertThat(((GatePass) row[1]).getTenantId()).isEqualTo(otherTenant));
    }

    @Test
    void validationAndRolesAreThoseOfTheReport() {
        String backwards = "from=2026-07-01T00:00:00Z&to=2026-05-01T00:00:00Z";
        org.springframework.http.HttpStatusCode reportStatus = call(admin, a.tenantId(), "/api/v1/gatepass/report?" + backwards)
                .getStatusCode();
        assertThat(reportStatus.is4xxClientError()).isTrue();
        assertThat(call(admin, a.tenantId(), "/api/v1/gatepass/report/paged?" + backwards + "&page=0&size=10")
                .getStatusCode()).isEqualTo(reportStatus);

        User tenantUser = user(a.tenantId(), UserRole.TENANT_USER, "Viewer");
        assertThat(call(tenantUser, a.tenantId(), "/api/v1/gatepass/report/paged?" + WINDOW + "&page=0&size=10")
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(guard, a.tenantId(), "/api/v1/gatepass/report/paged?" + WINDOW + "&page=0&size=10")
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ helpers

    /** Walks the paged endpoint in pages of 7 (not a divisor of 15 or 30) and concatenates. */
    private List<JsonNode> allPages(User caller, String filter) {
        List<JsonNode> out = new ArrayList<>();
        int pageNo = 0;
        while (true) {
            JsonNode page = get(caller, a.tenantId(),
                    "/api/v1/gatepass/report/paged?" + WINDOW + filter + "&page=" + pageNo + "&size=7");
            page.get("content").forEach(out::add);
            if (page.get("last").asBoolean()) {
                assertThat(out).hasSize(page.get("totalElements").asInt());
                return out;
            }
            pageNo++;
            assertThat(pageNo).isLessThan(20);
        }
    }

    /** {@code /report} (oldest first) reversed: the order the paged endpoint promises. */
    private List<JsonNode> reversedReport(User caller, String filter) {
        List<JsonNode> out = new ArrayList<>();
        get(caller, a.tenantId(), "/api/v1/gatepass/report?" + WINDOW + filter).forEach(n -> out.add(0, n));
        return out;
    }

    private GatePass pass(Property property, Unit unit, String tag) {
        return pass(a.tenantId(), property, unit, "Guest " + tag, admin.getId());
    }

    private GatePass pass(UUID tenantId, Property property, Unit unit, String guestName, UUID createdBy) {
        GatePass pass = new GatePass();
        pass.setTenantId(tenantId);
        pass.setPropertyId(property.getId());
        pass.setUnitId(unit.getId());
        pass.setCreatedByUserId(createdBy);
        pass.setOrigin(GatePassOrigin.RENTER);
        pass.setGuestName(guestName);
        pass.setGuestPhone("+971501234567");
        pass.setVehicleNumber("DXB " + guestName.length());
        pass.setPurpose("Visit " + guestName);
        pass.setVisitorType(GateVisitorType.GUEST);
        pass.setPassType(GatePassType.SINGLE_USE);
        pass.setValidFrom(BASE.minus(1, ChronoUnit.DAYS));
        pass.setValidTo(BASE.plus(1, ChronoUnit.DAYS));
        pass.setStatus(GatePassStatus.ACTIVE);
        pass.setQrToken(UUID.randomUUID().toString().replace("-", "") + "0123456789abcdef");
        pass.setNumericCode(String.format("%08d",
                Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000_000L)));
        return passRepo.save(pass);
    }

    private void scan(GatePass pass, Instant at, ScanResult result) {
        scanRepo.save(scanOf(a.tenantId(), pass, at, result, guard.getId()));
    }

    private static GatePassScan scanOf(UUID tenantId, GatePass pass, Instant at, ScanResult result, UUID by) {
        GatePassScan scan = new GatePassScan();
        scan.setTenantId(tenantId);
        scan.setGatePassId(pass.getId());
        scan.setScannedByUserId(by);
        scan.setDirection(ScanDirection.ENTRY);
        scan.setResult(result);
        scan.setRejectionReason(result == ScanResult.REJECTED ? "Outside validity" : null);
        scan.setScannedAt(at);
        return scan;
    }

    private static List<String> values(JsonNode rows, String field) {
        List<String> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.path(field).asText(null)));
        return out;
    }

    private LeaseTestFixtures fixtures() {
        return new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo, propertyService,
                accountService, propertyAccountService, chargeTypeService);
    }

    private User user(UUID tenantId, UserRole role, String name) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(name);
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        if (role == UserRole.SECURITY_GUARD) {
            u.setPhoneNumber("+9715" + String.format("%08d",
                    Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 100_000_000L)));
        }
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
