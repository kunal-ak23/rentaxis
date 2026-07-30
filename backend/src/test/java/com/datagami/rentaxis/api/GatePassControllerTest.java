package com.datagami.rentaxis.api;

import com.datagami.rentaxis.domain.entity.GatePassScan;
import com.datagami.rentaxis.domain.entity.GuardPropertyAssignment;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.ScanResult;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.GatePassScanRepository;
import com.datagami.rentaxis.domain.repository.GuardPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.net.URI;
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
    @Autowired GatePassScanRepository scanRepo;
    @Autowired ObjectMapper objectMapper;
    @Autowired EntityManagerFactory entityManagerFactory;

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

    /**
     * The property's own financial field, which SOW §3.1 keeps away from the Security
     * role. Distinct from {@link #MONTHLY_RENT_SENTINEL} so a leak names which payload
     * it came through.
     */
    private static final BigDecimal FIXED_EXPENSES_SENTINEL = BigDecimal.valueOf(736451);

    /** Private property data — a Makani number locates the building, a guard needs no such thing. */
    private static final String MAKANI_SENTINEL = "MAKANI-2648172639";

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

    /**
     * Carries the sensitive fields on purpose: the guard-facing property payload is a
     * security boundary, and a fixture with them left null would let a leak pass
     * unnoticed.
     */
    private Property makeProperty(LandlordOrg org) {
        Property p = new Property();
        p.setNameEn("Prop-" + UUID.randomUUID());
        p.setEmirate(Emirate.DUBAI);
        p.setFixedExpenses(FIXED_EXPENSES_SENTINEL);
        p.setMakaniNumber(MAKANI_SENTINEL);
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

    // ------------------------------------------------------- request validation

    /**
     * A missing required field is a client error, and must be reported as one. Without
     * bean validation these nulls reached Hibernate, which threw
     * {@code PropertyValueException} — caught by the catch-all {@code handleRuntime} and
     * rendered as a 500 whose message echoed the entity's fully-qualified class name.
     * Both halves are asserted: the status, and that the response says nothing about the
     * persistence layer.
     */
    @ParameterizedTest
    @ValueSource(strings = {"guestName", "guestPhone", "passType"})
    void createRejectsMissingRequiredFieldWith400NotA500(String omitted) {
        Fixture f = makeFixture();
        Instant now = Instant.now();
        Map<String, Object> body = passBody(f.unit().getId(), "Guest Rho", "SINGLE_USE", now,
                now.plus(2, ChronoUnit.HOURS));
        body.remove(omitted);

        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/gatepass", f.renterUser(), body);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains(omitted);
        // Never echo the entity class back to the client.
        assertThat(res.getBody()).doesNotContain("com.datagami.rentaxis");
    }

    /**
     * Length overflow is the other way a body reached Hibernate as a 500 — the column is
     * {@code varchar(160)}, so a longer name failed at the DB, not the door.
     */
    @Test
    void createRejectsOverLongGuestNameWith400() {
        Fixture f = makeFixture();
        Instant now = Instant.now();
        Map<String, Object> body = passBody(f.unit().getId(), "G".repeat(161), "SINGLE_USE", now,
                now.plus(2, ChronoUnit.HOURS));

        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/gatepass", f.renterUser(), body);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).doesNotContain("com.datagami.rentaxis");
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

    /**
     * Property assignment bounds who may <i>admit</i> a guest; it must equally bound
     * who may <i>read</i> one. The numeric-code lookup is scoped by tenant, not by
     * property, so a pass at any property in the tenant resolves for any guard in it —
     * only this rejection stands between a rogue guard and the tenant's whole guest
     * book. The rejection must therefore carry the verdict and nothing else.
     */
    @Test
    void guardScanningPassAtUnassignedPropertyLearnsNothingAboutTheGuest() {
        LandlordOrg org = makeOrg();
        Property assigned = makeProperty(org);
        Property elsewhere = makeProperty(org);
        Fixture there = makeRenterWithActiveLease(org, elsewhere, "T1");
        JsonNode pass = createPass(there, "Guest Xi", "SINGLE_USE");

        // Posted to `assigned`, scanning a pass belonging to `elsewhere` — same tenant,
        // so the code resolves; only the assignment check rejects it.
        User guard = makeGuard(org, assigned);
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/gatepass/scan", guard,
                scanBody(null, pass.get("numericCode").asText(), "ENTRY"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(res);
        assertThat(body.get("result").asText()).isEqualTo("REJECTED");
        // The reason stays specific — the guard has to know why the gate said no, and
        // "not authorized for this property" is a property-assignment fact the guard
        // already knows about themselves, not a fact about the pass.
        assertThat(body.get("reason").asText()).isEqualTo("not authorized for this property");

        // ...but every guest-identifying field is blinded.
        for (String field : List.of("guestName", "guestPhone", "vehicleNumber", "purpose", "unitNumber",
                "passType", "validFrom", "validTo")) {
            assertThat(body.get(field).isNull())
                    .withFailMessage("%s leaked to a guard at an unassigned property: %s", field, body.get(field))
                    .isTrue();
        }
        // Belt and braces on the raw bytes, in case a field is ever renamed.
        assertThat(res.getBody()).doesNotContain("Guest Xi");
        assertThat(res.getBody()).doesNotContain("DXB-12345");
        assertThat(res.getBody()).doesNotContain("+971500000000");
    }

    /**
     * The audit trail is the reason the rejection above still loads the pass — blinding
     * the response must not blind the {@code gate_pass_scans} row.
     */
    @Test
    void unassignedPropertyRejectionIsStillAudited() {
        LandlordOrg org = makeOrg();
        Property assigned = makeProperty(org);
        Property elsewhere = makeProperty(org);
        Fixture there = makeRenterWithActiveLease(org, elsewhere, "T1");
        JsonNode pass = createPass(there, "Guest Omicron", "SINGLE_USE");
        UUID passId = UUID.fromString(pass.get("id").asText());

        User guard = makeGuard(org, assigned);
        call(HttpMethod.POST, "/api/v1/gatepass/scan", guard,
                scanBody(null, pass.get("numericCode").asText(), "ENTRY"));

        List<GatePassScan> scans = scanRepo.findAll().stream()
                .filter(s -> passId.equals(s.getGatePassId()))
                .toList();
        assertThat(scans).hasSize(1);
        assertThat(scans.get(0).getResult()).isEqualTo(ScanResult.REJECTED);
        assertThat(scans.get(0).getRejectionReason()).isEqualTo("not authorized for this property");
        assertThat(scans.get(0).getScannedByUserId()).isEqualTo(guard.getId());
    }

    /**
     * A rejection at a property the guard IS posted to must still name the guest: the
     * guard has to explain the refusal to the person at the barrier. This is the
     * counterweight to the test above — it fails if the blinding is ever widened from
     * "unassigned property" to "every rejection".
     */
    @Test
    void rejectionAtAnAssignedPropertyStillNamesTheGuest() {
        Fixture f = makeFixture();
        User guard = makeGuard(f.org(), f.property());
        JsonNode pass = createPass(f, "Guest Pi", "SINGLE_USE");
        String code = pass.get("numericCode").asText();

        // Burn the single-use pass, then scan it again → "already used" at an assigned gate.
        call(HttpMethod.POST, "/api/v1/gatepass/scan", guard, scanBody(null, code, "ENTRY"));
        JsonNode second = json(call(HttpMethod.POST, "/api/v1/gatepass/scan", guard, scanBody(null, code, "ENTRY")));

        assertThat(second.get("result").asText()).isEqualTo("REJECTED");
        assertThat(second.get("reason").asText()).isEqualTo("already used");
        assertThat(second.get("guestName").asText()).isEqualTo("Guest Pi");
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

    /**
     * The reason {@code propertyName} exists: a guard covering two buildings has to be
     * able to read which gate a guest is expected at. {@code propertyId} alone renders
     * as an id fragment, which is a discriminator rather than a name.
     */
    @Test
    void expectedTodayNamesThePropertySoAMultiPropertyGuardCanReadTheBoard() {
        LandlordOrg org = makeOrg();
        Property towerA = makeProperty(org);
        Property towerB = makeProperty(org);
        Fixture inA = makeRenterWithActiveLease(org, towerA, "A1");
        Fixture inB = makeRenterWithActiveLease(org, towerB, "B1");
        createPass(inA, "Guest In A", "SINGLE_USE");
        createPass(inB, "Guest In B", "SINGLE_USE");

        User guard = makeGuard(org, towerA, towerB);
        JsonNode res = json(call(HttpMethod.GET, "/api/v1/gatepass/expected-today", guard, null));

        assertThat(res).hasSize(2);
        Map<String, String> nameByGuest = new HashMap<>();
        res.forEach(row -> nameByGuest.put(row.get("guestName").asText(), row.get("propertyName").asText()));
        assertThat(nameByGuest).containsEntry("Guest In A", towerA.getNameEn());
        assertThat(nameByGuest).containsEntry("Guest In B", towerB.getNameEn());
    }

    /**
     * The property name must reach the approvals queue too — a guard approving a
     * recurring pass is deciding about a specific building.
     */
    @Test
    void approvalsNameTheProperty() {
        Fixture f = makeFixture();
        createPass(f, "Guest Sigma", "RECURRING");
        User guard = makeGuard(f.org(), f.property());

        JsonNode res = json(call(HttpMethod.GET, "/api/v1/gatepass/approvals", guard, null));

        assertThat(res).hasSize(1);
        assertThat(res.get(0).get("propertyName").asText()).isEqualTo(f.property().getNameEn());
    }

    /**
     * The N+1 guard. {@code expected-today} returns a list, so the property lookup has
     * to be batched — resolving per row would put one {@code properties} query behind
     * every pass on a busy gate's board.
     *
     * <p>Asserted through Hibernate's own statement counter rather than by eyeballing
     * the mapping code, because "it looks batched" is exactly how an N+1 gets
     * reintroduced. Ten passes across two properties must cost the same number of
     * property queries as one pass would.
     */
    @Test
    void expectedTodayResolvesPropertyNamesInOneQueryNotOnePerPass() {
        LandlordOrg org = makeOrg();
        Property towerA = makeProperty(org);
        Property towerB = makeProperty(org);
        User guard = makeGuard(org, towerA, towerB);

        for (int i = 0; i < 5; i++) {
            createPass(makeRenterWithActiveLease(org, towerA, "A" + i), "Guest A" + i, "SINGLE_USE");
            createPass(makeRenterWithActiveLease(org, towerB, "B" + i), "Guest B" + i, "SINGLE_USE");
        }

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        JsonNode res = json(call(HttpMethod.GET, "/api/v1/gatepass/expected-today", guard, null));
        assertThat(res).hasSize(10);

        // The whole endpoint: the pass query, one batched unit lookup, one batched
        // property lookup, plus the guard's assignments. A per-row property lookup
        // would add ten more on its own.
        assertThat(stats.getPrepareStatementCount())
                .as("10 passes must not cost a query per row — %d statements suggests an N+1",
                        stats.getPrepareStatementCount())
                .isLessThanOrEqualTo(6);
    }

    // -------------------------------------------------------- my properties

    /**
     * The guard's own posting, by name. Without this endpoint an unposted guard and a
     * quiet gate are the same {@code []} on the wire, and a guard can work a whole
     * shift assuming nobody is expected.
     */
    @Test
    void guardSeesOnlyTheirOwnAssignedProperties() {
        LandlordOrg org = makeOrg();
        Property assigned = makeProperty(org);
        Property alsoAssigned = makeProperty(org);
        Property elsewhere = makeProperty(org);

        User guard = makeGuard(org, assigned, alsoAssigned);
        JsonNode res = json(call(HttpMethod.GET, "/api/v1/gatepass/my-properties", guard, null));

        assertThat(res).hasSize(2);
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        res.forEach(row -> {
            ids.add(row.get("id").asText());
            names.add(row.get("name").asText());
        });
        assertThat(ids).containsExactlyInAnyOrder(assigned.getId().toString(), alsoAssigned.getId().toString());
        assertThat(names).containsExactlyInAnyOrder(assigned.getNameEn(), alsoAssigned.getNameEn());
        // Never a fallback to every property in the tenant.
        assertThat(ids).doesNotContain(elsewhere.getId().toString());
        assertThat(names).doesNotContain(elsewhere.getNameEn());
    }

    /**
     * The empty case is the whole point of the endpoint: it must answer, not 403 or
     * 404, so the app can distinguish "posted nowhere" from "nothing today".
     */
    @Test
    void unpostedGuardGetsAnEmptyListNotAnError() {
        LandlordOrg org = makeOrg();
        User guard = makeGuard(org); // no assignments

        ResponseEntity<String> res = call(HttpMethod.GET, "/api/v1/gatepass/my-properties", guard, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(res)).isEmpty();
    }

    /**
     * SOW §3.1: the Security role must never reach tenant financial or private
     * information. {@code Property} carries {@code fixedExpenses}, {@code makaniNumber}
     * and address data; a guard needs a name to know which gate they are on and
     * nothing more. Asserted on the serialized body rather than the record shape, so it
     * fails if a field is ever added to GuardProperty or the entity is serialized here
     * by accident.
     */
    @Test
    void myPropertiesCarriesNoFinancialOrPrivatePropertyFields() {
        LandlordOrg org = makeOrg();
        Property property = makeProperty(org);
        User guard = makeGuard(org, property);

        ResponseEntity<String> res = call(HttpMethod.GET, "/api/v1/gatepass/my-properties", guard, null);
        String raw = res.getBody();

        Set<String> actual = new HashSet<>();
        json(res).get(0).fieldNames().forEachRemaining(actual::add);
        assertThat(actual)
                .as("GuardProperty must stay id + name; anything else is a security decision")
                .containsExactlyInAnyOrder("id", "name");

        assertThat(raw).doesNotContain(FIXED_EXPENSES_SENTINEL.toPlainString());
        assertThat(raw).doesNotContain(MAKANI_SENTINEL);
        assertThat(raw).doesNotContainIgnoringCase("makani");
        assertThat(raw).doesNotContainIgnoringCase("expense");
        assertThat(raw).doesNotContainIgnoringCase("emirate");
        assertThat(raw).doesNotContainIgnoringCase("address");
    }

    /**
     * A guard must not be able to read another tenant's postings even if an assignment
     * row somehow points across the boundary — the name lookup is tenant-scoped in SQL,
     * so it resolves nothing rather than leaking a building name.
     */
    @Test
    void myPropertiesIsTenantScoped() {
        LandlordOrg orgA = makeOrg();
        LandlordOrg orgB = makeOrg();
        Property inA = makeProperty(orgA);
        Property inB = makeProperty(orgB);

        // A guard in tenant A, with a stray assignment to a property in tenant B.
        User guard = makeGuard(orgA, inA);
        GuardPropertyAssignment cross = new GuardPropertyAssignment();
        cross.setTenantId(orgA.getId());
        cross.setUserId(guard.getId());
        cross.setPropertyId(inB.getId());
        assignmentRepo.save(cross);

        ResponseEntity<String> res = call(HttpMethod.GET, "/api/v1/gatepass/my-properties", guard, null);

        assertThat(json(res)).hasSize(1);
        assertThat(json(res).get(0).get("id").asText()).isEqualTo(inA.getId().toString());
        assertThat(res.getBody()).doesNotContain(inB.getNameEn());
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"RENTER", "TENANT_ADMIN", "PROPERTY_MANAGER"})
    void myPropertiesIsGuardOnly(UserRole role) {
        LandlordOrg org = makeOrg();
        User caller = makeUser(org, role);

        // Guard-only: managers have PropertyController, and this endpoint's whole
        // contract is "the caller's own posting" — it means nothing for other roles.
        assertThat(call(HttpMethod.GET, "/api/v1/gatepass/my-properties", caller, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
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

    /**
     * Parameterized over both manager roles: {@code @PreAuthorize} names PROPERTY_MANAGER
     * alongside TENANT_ADMIN on this endpoint, and the controller branches only on
     * {@code isGuard()}, so the PROPERTY_MANAGER arm is a bare annotation literal that
     * nothing else pins. Dropping it from the annotation must fail a test.
     */
    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"TENANT_ADMIN", "PROPERTY_MANAGER"})
    void approvalsAreScopedForGuardsButTenantWideForManagers(UserRole managerRole) {
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

        User manager = makeUser(org, managerRole);
        JsonNode managerView = json(call(HttpMethod.GET, "/api/v1/gatepass/approvals", manager, null));
        assertThat(managerView).hasSize(2);
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

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"TENANT_ADMIN", "PROPERTY_MANAGER"})
    void managerApprovesRecurringPass(UserRole managerRole) {
        Fixture f = makeFixture();
        UUID id = UUID.fromString(createPass(f, "Guest Kappa", "RECURRING").get("id").asText());
        User manager = makeUser(f.org(), managerRole);

        JsonNode res = json(call(HttpMethod.POST, "/api/v1/gatepass/" + id + "/approval", manager,
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

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"TENANT_ADMIN", "PROPERTY_MANAGER"})
    void reportReturnsScansJoinedToPasses(UserRole managerRole) {
        Fixture f = makeFixture();
        User guard = makeGuard(f.org(), f.property());
        JsonNode pass = createPass(f, "Guest Mu", "SINGLE_USE");
        call(HttpMethod.POST, "/api/v1/gatepass/scan", guard, scanBody(pass.get("qrToken").asText(), null, "ENTRY"));

        User admin = makeUser(f.org(), managerRole);
        String window = "?from=" + Instant.now().minus(1, ChronoUnit.HOURS) + "&to="
                + Instant.now().plus(1, ChronoUnit.HOURS);
        JsonNode rows = json(call(HttpMethod.GET, "/api/v1/gatepass/report" + window, admin, null));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("guestName").asText()).isEqualTo("Guest Mu");
        assertThat(rows.get(0).get("unitNumber").asText()).isEqualTo("A1");
        assertThat(rows.get(0).get("result").asText()).isEqualTo("ALLOWED");
        assertThat(rows.get(0).get("direction").asText()).isEqualTo("ENTRY");
        // "Who scanned this" is the report's whole purpose, and a PROPERTY_MANAGER
        // cannot resolve the id itself — /api/admin/users excludes the role. Both arms
        // of the @EnumSource must get the name from the row or the report is unreadable
        // for one of the two roles allowed on it.
        assertThat(rows.get(0).get("scannedByUserId").asText()).isEqualTo(guard.getId().toString());
        assertThat(rows.get(0).get("scannedByName").asText()).isEqualTo(guard.getName());
    }

    /**
     * The name lookup is tenant-scoped in SQL, so a scan pointing at a user outside the
     * report's tenant resolves to no name rather than to that user's name.
     *
     * <p>This is the reachable half of "handle a missing user gracefully". The other
     * half — a hard-deleted guard — is not reachable at all: {@code fk_scan_guard}
     * RESTRICTs on user deletion by design (changeset 65), precisely so an audit row
     * keeps its attribution; a guard with scan history is deactivated, never deleted.
     * The null-safety in {@code guardNames} is therefore defence, and this is what it
     * defends against.
     *
     * <p>Constructed by writing the scan row directly, because no API path produces a
     * cross-tenant {@code scanned_by_user_id} — which is the point. The row must still
     * appear (the scan is part of the trail) with a null name and no leaked identity.
     */
    @Test
    void reportDoesNotNameAScanningUserFromAnotherTenant() {
        Fixture f = makeFixture();
        User guard = makeGuard(f.org(), f.property());
        JsonNode pass = createPass(f, "Guest Nu", "SINGLE_USE");
        call(HttpMethod.POST, "/api/v1/gatepass/scan", guard, scanBody(pass.get("qrToken").asText(), null, "ENTRY"));

        // A user in a DIFFERENT tenant, with a name a leak would spell out.
        LandlordOrg otherOrg = makeOrg();
        User foreigner = makeUser(otherOrg, UserRole.SECURITY_GUARD);
        foreigner.setName("Foreign Tenant Guard " + UUID.randomUUID());
        foreigner = userRepo.save(foreigner);

        // Re-point this tenant's scan at them. fk_scan_guard only requires the user to
        // exist — it carries no tenant predicate — so this is what the SQL scope stops.
        GatePassScan scan = scanRepo.findAll().stream()
                .filter(s -> f.tenantId().equals(s.getTenantId()))
                .findFirst().orElseThrow();
        scan.setScannedByUserId(foreigner.getId());
        scanRepo.save(scan);

        User admin = makeUser(f.org(), UserRole.TENANT_ADMIN);
        String window = "?from=" + Instant.now().minus(1, ChronoUnit.HOURS) + "&to="
                + Instant.now().plus(1, ChronoUnit.HOURS);
        ResponseEntity<String> response = call(HttpMethod.GET, "/api/v1/gatepass/report" + window, admin, null);
        JsonNode rows = json(response);

        assertThat(rows).as("the scan happened; an unresolvable scanner must not erase it").hasSize(1);
        assertThat(rows.get(0).get("scannedByName").isNull()).isTrue();
        assertThat(response.getBody())
                .as("a cross-tenant name must never cross into this report")
                .doesNotContain(foreigner.getName());
    }

    /**
     * The N+1 guard for the report, mirroring
     * {@link #expectedTodayResolvesPropertyNamesInOneQueryNotOnePerPass}.
     *
     * <p>Sharper than that one: the guard name is per-<i>scan</i>, not per-pass, and
     * this endpoint's row count is a month of gate traffic rather than a day's board.
     * A per-row lookup would be the biggest N+1 in the module and the least visible,
     * because it only hurts at real data volumes.
     */
    @Test
    void reportResolvesGuardNamesInOneQueryNotOnePerScan() {
        LandlordOrg org = makeOrg();
        Property property = makeProperty(org);
        // Several guards, several scans each — a single-guard fixture would pass even
        // with a per-row lookup that happened to be deduplicated.
        for (int g = 0; g < 3; g++) {
            User guard = makeGuard(org, property);
            for (int i = 0; i < 3; i++) {
                Fixture f = makeRenterWithActiveLease(org, property, "G" + g + "U" + i);
                JsonNode pass = createPass(f, "Guest " + g + i, "SINGLE_USE");
                call(HttpMethod.POST, "/api/v1/gatepass/scan", guard,
                        scanBody(pass.get("qrToken").asText(), null, "ENTRY"));
            }
        }

        User admin = makeUser(org, UserRole.TENANT_ADMIN);
        String window = "?from=" + Instant.now().minus(1, ChronoUnit.HOURS) + "&to="
                + Instant.now().plus(1, ChronoUnit.HOURS);

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        JsonNode rows = json(call(HttpMethod.GET, "/api/v1/gatepass/report" + window, admin, null));
        assertThat(rows).hasSize(9);

        // The whole endpoint: the scan query, one batched pass lookup, one batched unit
        // lookup, one batched guard-name lookup. Nine scans by three guards must cost
        // the same as one.
        assertThat(stats.getPrepareStatementCount())
                .as("9 scans must not cost a query per row — %d statements suggests an N+1",
                        stats.getPrepareStatementCount())
                .isLessThanOrEqualTo(6);
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

    /** Covers the PROPERTY_MANAGER arm of both the PUT and the GET on this path. */
    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"TENANT_ADMIN", "PROPERTY_MANAGER"})
    void managerReplacesGuardPropertyAssignments(UserRole managerRole) {
        LandlordOrg org = makeOrg();
        Property p1 = makeProperty(org);
        Property p2 = makeProperty(org);
        User admin = makeUser(org, managerRole);
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

    /**
     * The filter must agree with the dispatcher about what path this is.
     *
     * <p>{@code getRequestURI()} is raw per the Servlet spec, while Spring routes on the
     * decoded path — so a filter matching the raw URI against {@code /api/v1/gatepass/scan}
     * misses {@code /api/v1/gatepass/%73can}, hands it to the chain with no bucket
     * consumed, and the dispatcher then decodes {@code %73} to {@code s} and runs the
     * handler anyway. That is the throttle being <i>absent</i>, not loosened, and it is
     * exactly the enumeration the scan bucket exists to bound. StrictHttpFirewall permits
     * {@code %73}, so nothing else catches this.
     *
     * <p>Parameterized over both spellings: the plain path proves the burst really does
     * trip the limiter (guarding against a vacuous pass), the encoded one proves the
     * bypass is closed.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/gatepass/scan", "/api/v1/gatepass/%73can"})
    void scanIsRateLimitedRegardlessOfPercentEncoding(String rawPath) {
        Fixture f = makeFixture();
        User guard = makeGuard(f.org(), f.property());
        String attackerIp = freshIp();

        int throttled = 0;
        int firstStatus = -1;
        for (int i = 0; i < 60; i++) {
            // URI, not a String template: RestClient would otherwise re-encode the '%'
            // into '%25' and we would be testing a different path entirely.
            ResponseEntity<String> res = client().method(HttpMethod.POST)
                    .uri(URI.create("http://localhost:" + port + rawPath))
                    .header("X-User-Id", guard.getId().toString())
                    .header("X-User-Role", guard.getRole().name())
                    .header("X-Tenant-Id", guard.getTenantId().toString())
                    .header("X-User-Tenant-Id", guard.getTenantId().toString())
                    .header("X-Forwarded-For", attackerIp)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(scanBody(null, String.format("%08d", i), "ENTRY"))
                    .retrieve()
                    .onStatus(status -> true, (req, rs) -> { })
                    .toEntity(String.class);
            if (firstStatus < 0) {
                firstStatus = res.getStatusCode().value();
            }
            if (res.getStatusCode().value() == 429) {
                throttled++;
            }
        }

        // Either the encoding never reached the handler at all (404), or it did and was
        // throttled like any other scan. What must not happen is 60 unthrottled 200s.
        assertThat(throttled)
                .withFailMessage("%s ran %d/60 requests with no throttling (first status %d) — the scan "
                        + "rate limit is bypassable by percent-encoding the path", rawPath, 60 - throttled,
                        firstStatus)
                .isGreaterThan(0);
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
