package com.datagami.rentaxis.api;

import com.datagami.rentaxis.domain.entity.GuardPropertyAssignment;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.GuardPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the authorization that {@link GatePassController} adds on top of the
 * gate-pass services, which deliberately implement none of it. The services are
 * already unit-tested, so the happy paths here are thin — the weight is on the
 * checks that exist nowhere else: RBAC per role, the renter's active-lease rule,
 * guard property scoping, and the guard-facing response shape.
 *
 * <p>Auth context normally injected by the Next.js proxy is simulated via the
 * X-User-* / X-Tenant-* headers that {@code ApiSecurityFilter} reads, so these run
 * through the real filter chain — including {@code PublicRateLimitFilter} and
 * {@code @PreAuthorize}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GatePassControllerTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort int port;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired GuardPropertyAssignmentRepository assignmentRepo;
    @Autowired ObjectMapper objectMapper;

    /**
     * The scan rate limiter keys buckets on the client IP and lives in a filter bean
     * shared by every test in this context, so tests would otherwise drain each
     * other's budget from 127.0.0.1. Each request gets a unique X-Forwarded-For,
     * which the filter prefers over the socket address — the same header-trust that
     * is a known, separately-tracked weakness in production is what buys isolation
     * here.
     */
    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    /** Distinctive enough that finding it in a response body can only mean a leak. */
    private static final BigDecimal MONTHLY_RENT_SENTINEL = BigDecimal.valueOf(918273);

    private static String freshIp() {
        int n = IP_SEQ.incrementAndGet();
        return "10.90." + (n / 250) + "." + (n % 250 + 1);
    }

    // ------------------------------------------------------------- fixtures

    /** One tenant's worth of gate-pass fixture: a renter holding an active lease on a unit. */
    private record Fixture(LandlordOrg org, Property property, Unit unit, User renterUser, Renter renter) {
        UUID tenantId() {
            return org.getId();
        }
    }

    private LandlordOrg makeOrg() {
        LandlordOrg org = new LandlordOrg();
        org.setName("GatePass-" + UUID.randomUUID());
        return orgRepo.save(org);
    }

    private User makeUser(LandlordOrg org, UserRole role) {
        User u = new User();
        u.setEmail("gp-" + UUID.randomUUID() + "@test");
        u.setName("User " + role);
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(org.getId());
        return userRepo.save(u);
    }

    private Property makeProperty(LandlordOrg org) {
        Property p = new Property();
        p.setNameEn("Prop-" + UUID.randomUUID());
        p.setEmirate(Emirate.DUBAI);
        p.setTenantId(org.getId());
        return propertyRepo.save(p);
    }

    private Unit makeUnit(LandlordOrg org, Property property, String number) {
        Unit unit = new Unit();
        unit.setProperty(property);
        unit.setUnitNumber(number);
        unit.setTenantId(org.getId());
        return unitRepo.save(unit);
    }

    /** A renter with an ACTIVE lease on a fresh unit in {@code property}. */
    private Fixture makeRenterWithActiveLease(LandlordOrg org, Property property, String unitNumber) {
        User renterUser = makeUser(org, UserRole.RENTER);
        // Distinct from any guest name used below, so the JSON leak assertion is meaningful.
        renterUser.setName("Renter Identity " + UUID.randomUUID());
        renterUser = userRepo.save(renterUser);

        Renter renter = new Renter();
        renter.setUserId(renterUser.getId());
        renter.setNameEn(renterUser.getName());
        renter.setTenantId(org.getId());
        renter = renterRepo.save(renter);

        Unit unit = makeUnit(org, property, unitNumber);

        Lease lease = new Lease();
        lease.setUnit(unit);
        lease.setRenter(renter);
        lease.setTenantId(org.getId());
        lease.setStartDate(LocalDate.now().minusMonths(1));
        lease.setEndDate(LocalDate.now().plusMonths(11));
        // A sentinel rather than a plausible rent: scanResponseCarriesNoRenterOrFinancialFields
        // greps the raw JSON for this value, and a round number like 5000 collides with
        // digits in the guest phone, which made that assertion fail for the wrong reason.
        lease.setMonthlyRent(MONTHLY_RENT_SENTINEL);
        lease.setStatus(LeaseStatus.ACTIVE);
        leaseRepo.save(lease);

        return new Fixture(org, property, unit, renterUser, renter);
    }

    private Fixture makeFixture() {
        LandlordOrg org = makeOrg();
        return makeRenterWithActiveLease(org, makeProperty(org), "A1");
    }

    private User makeGuard(LandlordOrg org, Property... properties) {
        User guard = makeUser(org, UserRole.SECURITY_GUARD);
        for (Property p : properties) {
            GuardPropertyAssignment a = new GuardPropertyAssignment();
            a.setTenantId(org.getId());
            a.setUserId(guard.getId());
            a.setPropertyId(p.getId());
            assignmentRepo.save(a);
        }
        return guard;
    }

    // -------------------------------------------------------------- plumbing

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Issues a request as {@code caller} and returns the raw response without throwing on 4xx/5xx. */
    private ResponseEntity<String> call(HttpMethod method, String path, User caller, Object body) {
        RestClient.RequestBodySpec spec = client().method(method).uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Forwarded-For", freshIp());
        if (caller.getTenantId() != null) {
            spec = spec.header("X-Tenant-Id", caller.getTenantId().toString())
                    .header("X-User-Tenant-Id", caller.getTenantId().toString());
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve()
                .onStatus(status -> true, (req, res) -> { }) // assert on status ourselves
                .toEntity(String.class);
    }

    private JsonNode json(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new AssertionError("Response was not JSON: " + response.getBody(), e);
        }
    }

    private Map<String, Object> passBody(UUID unitId, String guestName, String type, Instant from, Instant to) {
        Map<String, Object> body = new HashMap<>();
        body.put("unitId", unitId == null ? null : unitId.toString());
        body.put("guestName", guestName);
        body.put("guestPhone", "+971500000000");
        body.put("purpose", "Delivery");
        body.put("vehicleNumber", "DXB-12345");
        body.put("passType", type);
        body.put("validFrom", from.toString());
        body.put("validTo", to.toString());
        return body;
    }

    private Map<String, Object> scanBody(String qrToken, String numericCode, String direction) {
        Map<String, Object> body = new HashMap<>();
        body.put("qrToken", qrToken);
        body.put("numericCode", numericCode);
        body.put("direction", direction);
        return body;
    }

    /** Creates a pass through the API as the fixture's renter, returning the response body. */
    private JsonNode createPass(Fixture f, String guestName, String type) {
        Instant now = Instant.now();
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/gatepass", f.renterUser(),
                passBody(f.unit().getId(), guestName, type, now.minus(1, ChronoUnit.HOURS),
                        now.plus(6, ChronoUnit.HOURS)));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json(res);
    }

    // ------------------------------------------------------- renter happy path

    @Test
    void renterCreatesPassAndSeesItWithCredentials() {
        Fixture f = makeFixture();

        JsonNode created = createPass(f, "Guest Alpha", "SINGLE_USE");
        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");
        // propertyId is derived from the unit, never taken from the request body.
        assertThat(created.get("propertyId").asText()).isEqualTo(f.property().getId().toString());
        assertThat(created.get("qrToken").asText()).isNotBlank();
        assertThat(created.get("numericCode").asText()).hasSize(8);

        ResponseEntity<String> mine = call(HttpMethod.GET, "/api/v1/gatepass/mine", f.renterUser(), null);
        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(mine)).hasSize(1);

        UUID id = UUID.fromString(created.get("id").asText());
        JsonNode fetched = json(call(HttpMethod.GET, "/api/v1/gatepass/" + id, f.renterUser(), null));
        assertThat(fetched.get("qrToken").asText()).isEqualTo(created.get("qrToken").asText());

        ResponseEntity<String> cancelled = call(HttpMethod.POST, "/api/v1/gatepass/" + id + "/cancel",
                f.renterUser(), null);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(cancelled).get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void renterCannotReadAnotherRentersPass() {
        Fixture owner = makeFixture();
        Fixture other = makeRenterWithActiveLease(owner.org(), owner.property(), "A2");

        UUID id = UUID.fromString(createPass(owner, "Guest Beta", "SINGLE_USE").get("id").asText());

        // 404 rather than 403 — a non-creator must not be able to confirm a pass exists.
        assertThat(call(HttpMethod.GET, "/api/v1/gatepass/" + id, other.renterUser(), null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------- the active-lease authorization rule

    @Test
    void createRejectsUnitNotOnCallersActiveLease() {
        Fixture f = makeFixture();
        // A real unit in the same tenant and property, but leased to nobody.
        Unit foreignUnit = makeUnit(f.org(), f.property(), "B9");

        Instant now = Instant.now();
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/gatepass", f.renterUser(),
                passBody(foreignUnit.getId(), "Guest Gamma", "SINGLE_USE", now, now.plus(2, ChronoUnit.HOURS)));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createRejectsUnitLeasedToADifferentRenter() {
        Fixture a = makeFixture();
        Fixture b = makeRenterWithActiveLease(a.org(), a.property(), "C3");

        Instant now = Instant.now();
        // Renter A points at renter B's unit.
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/gatepass", a.renterUser(),
                passBody(b.unit().getId(), "Guest Delta", "SINGLE_USE", now, now.plus(2, ChronoUnit.HOURS)));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createRejectsUnitWhoseLeaseIsNoLongerActive() {
        Fixture f = makeFixture();
        Lease lease = leaseRepo.findByUnitId(f.unit().getId()).get(0);
        lease.setStatus(LeaseStatus.TERMINATED);
        leaseRepo.save(lease);

        Instant now = Instant.now();
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/gatepass", f.renterUser(),
                passBody(f.unit().getId(), "Guest Epsilon", "SINGLE_USE", now, now.plus(2, ChronoUnit.HOURS)));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------ RBAC

    @Test
    void renterCannotScan() {
        Fixture f = makeFixture();
        assertThat(call(HttpMethod.POST, "/api/v1/gatepass/scan", f.renterUser(),
                scanBody(null, "12345678", "ENTRY")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void guardCannotReadReport() {
        Fixture f = makeFixture();
        User guard = makeGuard(f.org(), f.property());
        String window = "?from=" + Instant.now().minus(1, ChronoUnit.DAYS) + "&to=" + Instant.now();

        assertThat(call(HttpMethod.GET, "/api/v1/gatepass/report" + window, guard, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void guardCannotListRenterPasses() {
        Fixture f = makeFixture();
        User guard = makeGuard(f.org(), f.property());

        assertThat(call(HttpMethod.GET, "/api/v1/gatepass/mine", guard, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void renterCannotAssignGuardProperties() {
        Fixture f = makeFixture();
        User guard = makeGuard(f.org());

        assertThat(call(HttpMethod.PUT, "/api/v1/gatepass/guards/" + guard.getId() + "/properties",
                f.renterUser(), List.of(f.property().getId().toString())).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/v1/gatepass/guards/" + guard.getId() + "/properties",
                f.renterUser(), null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ scan

    @Test
    void guardScansPassAtAnAssignedProperty() {
        Fixture f = makeFixture();
        User guard = makeGuard(f.org(), f.property());
        JsonNode pass = createPass(f, "Guest Zeta", "SINGLE_USE");

        JsonNode res = json(call(HttpMethod.POST, "/api/v1/gatepass/scan", guard,
                scanBody(pass.get("qrToken").asText(), null, "ENTRY")));

        assertThat(res.get("result").asText()).isEqualTo("ALLOWED");
        assertThat(res.get("guestName").asText()).isEqualTo("Guest Zeta");
        assertThat(res.get("unitNumber").asText()).isEqualTo("A1");
    }

    @Test
    void scanRejectsBothNullAndBothPresentCodes() {
        Fixture f = makeFixture();
        User guard = makeGuard(f.org(), f.property());

        // Both absent: the scan service would read this as a numeric lookup for null and
        // report a misleading "not found" rather than the client error it is.
        assertThat(call(HttpMethod.POST, "/api/v1/gatepass/scan", guard, scanBody(null, null, "ENTRY"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Both present: ambiguous — the service would silently prefer the QR token.
        assertThat(call(HttpMethod.POST, "/api/v1/gatepass/scan", guard, scanBody("sometoken", "12345678", "ENTRY"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Blank is absent, not present.
        assertThat(call(HttpMethod.POST, "/api/v1/gatepass/scan", guard, scanBody("   ", "", "ENTRY"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * SOW §3.1: the Security role must never reach tenant financial or private
     * information. Asserted on the serialized body rather than the record shape, so
     * it fails if a field is ever added to ScanResponse or leaks through Jackson.
     */
    @Test
    void scanResponseCarriesNoRenterOrFinancialFields() {
        Fixture f = makeFixture();
        User guard = makeGuard(f.org(), f.property());
        JsonNode pass = createPass(f, "Guest Eta", "SINGLE_USE");

        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/gatepass/scan", guard,
                scanBody(pass.get("qrToken").asText(), null, "ENTRY"));
        String raw = res.getBody();

        Set<String> allowed = Set.of("result", "reason", "guestName", "guestPhone", "vehicleNumber", "purpose",
                "unitNumber", "passType", "validFrom", "validTo");
        Set<String> actual = new HashSet<>();
        json(res).fieldNames().forEachRemaining(actual::add);
        assertThat(allowed).containsAll(actual);
        assertThat(actual).contains("result", "guestName", "unitNumber");

        // No renter identity: not the linked user id, name, or email.
        assertThat(raw).doesNotContain(f.renterUser().getId().toString());
        assertThat(raw).doesNotContain(f.renterUser().getName());
        assertThat(raw).doesNotContain(f.renterUser().getEmail());
        assertThat(raw).doesNotContain(f.renter().getId().toString());
        // No lease or financial data.
        assertThat(raw).doesNotContain(MONTHLY_RENT_SENTINEL.toPlainString());
        assertThat(raw).doesNotContainIgnoringCase("lease");
        assertThat(raw).doesNotContainIgnoringCase("rent");
        // Not even the pass's own identifiers/credentials — the guard scanned the code,
        // it does not need it handed back.
        assertThat(raw).doesNotContain(pass.get("qrToken").asText());
        assertThat(raw).doesNotContain(pass.get("numericCode").asText());
    }

    // ---------------------------------------------------------- expected today

    @Test
    void expectedTodayShowsOnlyAssignedProperties() {
        LandlordOrg org = makeOrg();
        Property assigned = makeProperty(org);
        Property unassigned = makeProperty(org);
        Fixture here = makeRenterWithActiveLease(org, assigned, "H1");
        Fixture there = makeRenterWithActiveLease(org, unassigned, "T1");
        createPass(here, "Guest Here", "SINGLE_USE");
        createPass(there, "Guest There", "SINGLE_USE");

        User guard = makeGuard(org, assigned);
        JsonNode res = json(call(HttpMethod.GET, "/api/v1/gatepass/expected-today", guard, null));

        assertThat(res).hasSize(1);
        assertThat(res.get(0).get("guestName").asText()).isEqualTo("Guest Here");
    }

    @Test
    void expectedTodayForUnassignedGuardIsEmptyNotEverything() {
        Fixture f = makeFixture();
        createPass(f, "Guest Theta", "SINGLE_USE");
        User unpostedGuard = makeGuard(f.org()); // no assignments

        JsonNode res = json(call(HttpMethod.GET, "/api/v1/gatepass/expected-today", unpostedGuard, null));

        // Must be empty, never a fallback to every property in the tenant.
        assertThat(res).isEmpty();
    }

    // ------------------------------------------------------------- approvals

    @Test
    void approvalsAreScopedForGuardsButTenantWideForManagers() {
        LandlordOrg org = makeOrg();
        Property assigned = makeProperty(org);
        Property other = makeProperty(org);
        Fixture here = makeRenterWithActiveLease(org, assigned, "H1");
        Fixture there = makeRenterWithActiveLease(org, other, "T1");

        // RECURRING passes land in PENDING_APPROVAL.
        createPass(here, "Guest Assigned", "RECURRING");
        createPass(there, "Guest Elsewhere", "RECURRING");

        User guard = makeGuard(org, assigned);
        JsonNode guardView = json(call(HttpMethod.GET, "/api/v1/gatepass/approvals", guard, null));
        assertThat(guardView).hasSize(1);
        assertThat(guardView.get(0).get("guestName").asText()).isEqualTo("Guest Assigned");

        User admin = makeUser(org, UserRole.TENANT_ADMIN);
        JsonNode adminView = json(call(HttpMethod.GET, "/api/v1/gatepass/approvals", admin, null));
        assertThat(adminView).hasSize(2);
    }

    @Test
    void approvalsListOmitsPassCredentials() {
        Fixture f = makeFixture();
        createPass(f, "Guest Iota", "RECURRING");
        User admin = makeUser(f.org(), UserRole.TENANT_ADMIN);

        ResponseEntity<String> res = call(HttpMethod.GET, "/api/v1/gatepass/approvals", admin, null);

        // An approver decides yes/no on a guest; it needs none of the guest's
        // admission credentials to do that.
        assertThat(res.getBody()).doesNotContain("qrToken");
        assertThat(res.getBody()).doesNotContain("numericCode");
    }

    @Test
    void managerApprovesRecurringPass() {
        Fixture f = makeFixture();
        UUID id = UUID.fromString(createPass(f, "Guest Kappa", "RECURRING").get("id").asText());
        User admin = makeUser(f.org(), UserRole.TENANT_ADMIN);

        JsonNode res = json(call(HttpMethod.POST, "/api/v1/gatepass/" + id + "/approval", admin,
                Map.of("approved", true)));

        assertThat(res.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(res.has("qrToken")).isFalse();
    }

    @Test
    void guardCannotApprovePassAtUnassignedProperty() {
        LandlordOrg org = makeOrg();
        Property assigned = makeProperty(org);
        Property other = makeProperty(org);
        Fixture there = makeRenterWithActiveLease(org, other, "T1");
        UUID id = UUID.fromString(createPass(there, "Guest Lambda", "RECURRING").get("id").asText());

        User guard = makeGuard(org, assigned);

        // 404, not 403 — the guard must not learn the pass exists.
        assertThat(call(HttpMethod.POST, "/api/v1/gatepass/" + id + "/approval", guard, Map.of("approved", true))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------- report

    @Test
    void reportReturnsScansJoinedToPasses() {
        Fixture f = makeFixture();
        User guard = makeGuard(f.org(), f.property());
        JsonNode pass = createPass(f, "Guest Mu", "SINGLE_USE");
        call(HttpMethod.POST, "/api/v1/gatepass/scan", guard, scanBody(pass.get("qrToken").asText(), null, "ENTRY"));

        User admin = makeUser(f.org(), UserRole.TENANT_ADMIN);
        String window = "?from=" + Instant.now().minus(1, ChronoUnit.HOURS) + "&to="
                + Instant.now().plus(1, ChronoUnit.HOURS);
        JsonNode rows = json(call(HttpMethod.GET, "/api/v1/gatepass/report" + window, admin, null));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("guestName").asText()).isEqualTo("Guest Mu");
        assertThat(rows.get(0).get("unitNumber").asText()).isEqualTo("A1");
        assertThat(rows.get(0).get("result").asText()).isEqualTo("ALLOWED");
        assertThat(rows.get(0).get("direction").asText()).isEqualTo("ENTRY");
    }

    @Test
    void reportFiltersByProperty() {
        LandlordOrg org = makeOrg();
        Property p1 = makeProperty(org);
        Property p2 = makeProperty(org);
        Fixture f1 = makeRenterWithActiveLease(org, p1, "P1");
        Fixture f2 = makeRenterWithActiveLease(org, p2, "P2");
        User guard = makeGuard(org, p1, p2);

        call(HttpMethod.POST, "/api/v1/gatepass/scan", guard,
                scanBody(createPass(f1, "Guest P1", "SINGLE_USE").get("qrToken").asText(), null, "ENTRY"));
        call(HttpMethod.POST, "/api/v1/gatepass/scan", guard,
                scanBody(createPass(f2, "Guest P2", "SINGLE_USE").get("qrToken").asText(), null, "ENTRY"));

        User admin = makeUser(org, UserRole.TENANT_ADMIN);
        String window = "?from=" + Instant.now().minus(1, ChronoUnit.HOURS) + "&to="
                + Instant.now().plus(1, ChronoUnit.HOURS);

        assertThat(json(call(HttpMethod.GET, "/api/v1/gatepass/report" + window, admin, null))).hasSize(2);

        JsonNode filtered = json(call(HttpMethod.GET,
                "/api/v1/gatepass/report" + window + "&propertyId=" + p2.getId(), admin, null));
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("guestName").asText()).isEqualTo("Guest P2");
    }

    // ----------------------------------------------- guard property assignment

    @Test
    void managerReplacesGuardPropertyAssignments() {
        LandlordOrg org = makeOrg();
        Property p1 = makeProperty(org);
        Property p2 = makeProperty(org);
        User admin = makeUser(org, UserRole.TENANT_ADMIN);
        User guard = makeGuard(org, p1);

        // Replace-all: p1 is kept (exercising the delete/insert flush ordering against
        // uq_gpa_user_property) and p2 added.
        ResponseEntity<String> res = call(HttpMethod.PUT, "/api/v1/gatepass/guards/" + guard.getId() + "/properties",
                admin, List.of(p1.getId().toString(), p2.getId().toString()));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(json(call(HttpMethod.GET, "/api/v1/gatepass/guards/" + guard.getId() + "/properties", admin, null)))
                .hasSize(2);

        // Now shrink to nothing.
        call(HttpMethod.PUT, "/api/v1/gatepass/guards/" + guard.getId() + "/properties", admin, List.of());
        assertThat(json(call(HttpMethod.GET, "/api/v1/gatepass/guards/" + guard.getId() + "/properties", admin, null)))
                .isEmpty();
    }

    @Test
    void guardAssignmentRejectsNonGuardTarget() {
        Fixture f = makeFixture();
        User admin = makeUser(f.org(), UserRole.TENANT_ADMIN);

        // The target is a RENTER, not a SECURITY_GUARD.
        ResponseEntity<String> res = call(HttpMethod.PUT,
                "/api/v1/gatepass/guards/" + f.renterUser().getId() + "/properties", admin,
                List.of(f.property().getId().toString()));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void guardAssignmentRejectsCrossTenantTarget() {
        LandlordOrg orgA = makeOrg();
        LandlordOrg orgB = makeOrg();
        Property propertyA = makeProperty(orgA);
        User adminA = makeUser(orgA, UserRole.TENANT_ADMIN);
        User guardB = makeGuard(orgB, makeProperty(orgB));

        ResponseEntity<String> res = call(HttpMethod.PUT, "/api/v1/gatepass/guards/" + guardB.getId() + "/properties",
                adminA, List.of(propertyA.getId().toString()));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(assignmentRepo.findByUserId(guardB.getId())).hasSize(1); // untouched
    }

    @Test
    void guardAssignmentRejectsCrossTenantProperty() {
        LandlordOrg orgA = makeOrg();
        LandlordOrg orgB = makeOrg();
        User adminA = makeUser(orgA, UserRole.TENANT_ADMIN);
        User guardA = makeGuard(orgA);
        Property foreignProperty = makeProperty(orgB);

        ResponseEntity<String> res = call(HttpMethod.PUT, "/api/v1/gatepass/guards/" + guardA.getId() + "/properties",
                adminA, List.of(foreignProperty.getId().toString()));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(assignmentRepo.findByUserId(guardA.getId())).isEmpty();
    }

    // ----------------------------------------------------------- rate limiting

    /**
     * A rogue guard account must not be able to enumerate the 8-digit numeric code
     * space to harvest guest PII. Pinned here because the throttle lives in a filter,
     * far from the endpoint it protects, and is easy to drop by accident.
     */
    @Test
    void scanIsRateLimitedPerClient() {
        Fixture f = makeFixture();
        User guard = makeGuard(f.org(), f.property());
        String attackerIp = freshIp();

        int throttled = 0;
        int firstStatus = -1;
        // Bucket is 30/min; 60 attempts outrun the greedy refill even if this loop is slow.
        for (int i = 0; i < 60; i++) {
            RestClient.RequestBodySpec spec = client().method(HttpMethod.POST).uri("/api/v1/gatepass/scan")
                    .header("X-User-Id", guard.getId().toString())
                    .header("X-User-Role", guard.getRole().name())
                    .header("X-Tenant-Id", guard.getTenantId().toString())
                    .header("X-User-Tenant-Id", guard.getTenantId().toString())
                    .header("X-Forwarded-For", attackerIp) // one IP for the whole burst
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(scanBody(null, String.format("%08d", i), "ENTRY"));
            ResponseEntity<String> res = spec.retrieve()
                    .onStatus(status -> true, (req, rs) -> { })
                    .toEntity(String.class);
            if (firstStatus < 0) {
                firstStatus = res.getStatusCode().value();
            }
            if (res.getStatusCode().value() == 429) {
                throttled++;
            }
        }

        // A real guard's first scan is never throttled...
        assertThat(firstStatus).isEqualTo(200);
        // ...but a burst well past a busy gate's pace is.
        assertThat(throttled).isGreaterThan(0);
    }

    @Test
    void scanRateLimitIsPerClientNotGlobal() {
        Fixture f = makeFixture();
        User guard = makeGuard(f.org(), f.property());
        JsonNode pass = createPass(f, "Guest Nu", "SINGLE_USE");

        // freshIp() per call: a different gate must not be starved by the burst above.
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/gatepass/scan", guard,
                scanBody(pass.get("qrToken").asText(), null, "ENTRY"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
