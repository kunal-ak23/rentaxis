package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.entity.GateAccessPolicy;
import com.datagami.rentaxis.domain.entity.GatePass;
import com.datagami.rentaxis.domain.entity.GatePassScan;
import com.datagami.rentaxis.domain.entity.GuardPropertyAssignment;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.ScanDirection;
import com.datagami.rentaxis.domain.entity.enums.ScanResult;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import com.datagami.rentaxis.domain.repository.GateAccessPolicyRepository;
import com.datagami.rentaxis.domain.repository.GatePassRepository;
import com.datagami.rentaxis.domain.repository.GatePassScanRepository;
import com.datagami.rentaxis.domain.repository.GuardPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Covers {@link GateWalkInController} — the guard-raised visitor flow, which is
 * where the gate stops being a scanner and starts being an identity desk.
 *
 * <p>{@code GateWalkInService} is unit-tested separately, so the weight here is on
 * what only the HTTP layer does: RBAC across four roles, guard-to-property scoping,
 * the resident's approval queue (which is scoped by ACTIVE lease, not by tenant),
 * the walk-in/renter-pass separation, and the lazy expiry that {@code requireWalkIn}
 * performs on read.
 *
 * <p>Auth context normally injected by the Next.js proxy is simulated via the
 * X-User-* / X-Tenant-* headers that {@code ApiSecurityFilter} reads, mirroring
 * {@link GatePassControllerTest}. {@code BlobStorageService} is the one collaborator
 * mocked: the visitor photo is Azure-backed and there is no emulator in this context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GateWalkInControllerTest extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository userPropertyAssignmentRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired BuildingRepository buildingRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired GuardPropertyAssignmentRepository assignmentRepo;
    @Autowired GatePassRepository passRepo;
    @Autowired GatePassScanRepository scanRepo;
    @Autowired GateAccessPolicyRepository policyRepo;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean BlobStorageService blobStorageService;

    /** Same reason as {@link GatePassControllerTest}: per-IP buckets in a shared filter bean. */
    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    private static final String PHOTO_URL = "https://blob.test/gate-visitors/fresh.jpg";
    private static final String PHOTO_PATH = "gate-visitors/profile/fresh.jpg";
    private static final byte[] PHOTO_BYTES = "JPEGBYTES".getBytes(StandardCharsets.UTF_8);

    private static String freshIp() {
        int n = IP_SEQ.incrementAndGet();
        return "10.91." + (n / 250) + "." + (n % 250 + 1);
    }

    @BeforeEach
    void stubBlobStorage() {
        when(blobStorageService.uploadGateVisitor(any(), any(), any()))
                .thenReturn(new BlobStorageService.UploadResult(PHOTO_URL, PHOTO_PATH));
        when(blobStorageService.download(any(), any()))
                .thenReturn(new BlobStorageService.DownloadResult(PHOTO_BYTES, "image/jpeg"));
    }

    // ------------------------------------------------------------- fixtures

    /** One tenant's worth of walk-in fixture: a resident holding an active lease on a unit. */
    private record Fixture(LandlordOrg org, Property property, Unit unit, User renterUser, Renter renter) {
        UUID tenantId() {
            return org.getId();
        }
    }

    private LandlordOrg makeOrg() {
        LandlordOrg org = new LandlordOrg();
        org.setName("WalkIn-" + UUID.randomUUID());
        return orgRepo.save(org);
    }

    /** A property manager acts only on the buildings assigned to them (round 5, #72). */
    private void assignIfManager(User user, Property... properties) {
        if (user.getRole() != UserRole.PROPERTY_MANAGER) return;
        for (Property p : properties) {
            com.datagami.rentaxis.domain.entity.UserPropertyAssignment a =
                    new com.datagami.rentaxis.domain.entity.UserPropertyAssignment();
            a.setUserId(user.getId());
            a.setPropertyId(p.getId());
            userPropertyAssignmentRepo.save(a);
        }
    }

    private User makeUser(LandlordOrg org, UserRole role) {
        User u = new User();
        u.setEmail("wi-" + UUID.randomUUID() + "@test");
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

    private Building makeBuilding(LandlordOrg org, Property property, String name) {
        Building building = new Building();
        building.setProperty(property);
        building.setNameEn(name);
        building.setTenantId(org.getId());
        return buildingRepo.save(building);
    }

    private Unit makeUnit(LandlordOrg org, Property property, Building building, String number) {
        Unit unit = new Unit();
        unit.setProperty(property);
        unit.setBuilding(building);
        unit.setUnitNumber(number);
        unit.setTenantId(org.getId());
        return unitRepo.save(unit);
    }

    private Fixture makeResidentWithActiveLease(LandlordOrg org, Property property, String unitNumber) {
        User renterUser = makeUser(org, UserRole.RENTER);
        Renter renter = new Renter();
        renter.setUserId(renterUser.getId());
        renter.setNameEn(renterUser.getName());
        renter.setTenantId(org.getId());
        renter = renterRepo.save(renter);

        Unit unit = makeUnit(org, property, null, unitNumber);
        leaseRepo.save(activeLease(org, unit, renter));
        return new Fixture(org, property, unit, renterUser, renter);
    }

    private Lease activeLease(LandlordOrg org, Unit unit, Renter renter) {
        Lease lease = new Lease();
        lease.setUnit(unit);
        lease.setRenter(renter);
        lease.setTenantId(org.getId());
        lease.setStartDate(LocalDate.now().minusMonths(1));
        lease.setEndDate(LocalDate.now().plusMonths(11));
        lease.setRentAmount(java.math.BigDecimal.valueOf(12000));
        lease.setStatus(LeaseStatus.ACTIVE);
        return lease;
    }

    private Fixture makeFixture() {
        LandlordOrg org = makeOrg();
        return makeResidentWithActiveLease(org, makeProperty(org), "A1");
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

    /**
     * Writes the gate's policy directly rather than through the manager endpoint, so a
     * test about admission is not also a test about policy authoring.
     */
    private void setPolicy(LandlordOrg org, Property property, Building building,
                           boolean requireUnregisteredApproval, boolean requireFreshPhoto) {
        GateAccessPolicy policy = new GateAccessPolicy();
        policy.setTenantId(org.getId());
        policy.setPropertyId(property.getId());
        policy.setBuildingId(building == null ? null : building.getId());
        policy.setRequireUnregisteredApproval(requireUnregisteredApproval);
        policy.setRequireRegisteredApproval(false);
        policy.setNotifyRegisteredEntry(true);
        policy.setRequireFreshPhoto(requireFreshPhoto);
        policy.setApprovalTimeoutMinutes(15);
        policyRepo.save(policy);
    }

    /** Admits every unregistered walk-in immediately and asks for no photo. */
    private void openGate(Fixture f) {
        setPolicy(f.org(), f.property(), null, false, false);
    }

    /** Holds every unregistered walk-in for the resident, and asks for no photo. */
    private void approvalGate(Fixture f) {
        setPolicy(f.org(), f.property(), null, true, false);
    }

    // -------------------------------------------------------------- plumbing

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private ResponseEntity<String> call(HttpMethod method, String path, User caller, Object body) {
        // A URI rather than a String template: the visitor lookup carries a phone whose
        // leading '+' has to arrive percent-encoded, and RestClient would re-encode the
        // '%' of %2B into %25 — the endpoint would then see a number with no country
        // code and reject a request the guard typed correctly.
        RestClient.RequestBodySpec spec = client().method(method)
                .uri(URI.create("http://localhost:" + port + path))
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
                .onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> multipartCall(String path, User caller,
                                                 MultiValueMap<String, HttpEntity<?>> parts) {
        RestClient.RequestBodySpec spec = client().method(HttpMethod.POST).uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Forwarded-For", freshIp());
        if (caller.getTenantId() != null) {
            spec = spec.header("X-Tenant-Id", caller.getTenantId().toString())
                    .header("X-User-Tenant-Id", caller.getTenantId().toString());
        }
        return spec.contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve()
                .onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);
    }

    private JsonNode json(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new AssertionError("Response was not JSON: " + response.getBody(), e);
        }
    }

    private MultiValueMap<String, HttpEntity<?>> walkInParts(UUID propertyId, UUID unitId, String name,
                                                             String phone, String visitorType, boolean withPhoto) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("propertyId", propertyId.toString());
        builder.part("unitId", unitId.toString());
        builder.part("name", name);
        builder.part("phone", phone);
        builder.part("visitorType", visitorType);
        builder.part("purpose", "Delivery");
        builder.part("vehicleNumber", "DXB-4242");
        if (withPhoto) {
            builder.part("photo", new ByteArrayResource(PHOTO_BYTES) {
                @Override
                public String getFilename() {
                    return "visitor.jpg";
                }
            }).contentType(MediaType.IMAGE_JPEG);
        }
        return builder.build();
    }

    /** Raises a walk-in as {@code guard} and asserts it was accepted, returning the body. */
    private JsonNode raiseWalkIn(User guard, Fixture f, String name, String phone, boolean withPhoto) {
        ResponseEntity<String> res = multipartCall("/api/v1/gatepass/walk-in", guard,
                walkInParts(f.property().getId(), f.unit().getId(), name, phone, "DELIVERY", withPhoto));
        assertThat(res.getStatusCode()).as("walk-in create failed: %s", res.getBody()).isEqualTo(HttpStatus.OK);
        return json(res);
    }

    private Map<String, Object> policyBody(boolean requireUnregisteredApproval, boolean requireRegisteredApproval,
                                           boolean notifyRegisteredEntry, boolean requireFreshPhoto,
                                           int approvalTimeoutMinutes) {
        Map<String, Object> body = new HashMap<>();
        body.put("requireUnregisteredApproval", requireUnregisteredApproval);
        body.put("requireRegisteredApproval", requireRegisteredApproval);
        body.put("notifyRegisteredEntry", notifyRegisteredEntry);
        body.put("requireFreshPhoto", requireFreshPhoto);
        body.put("approvalTimeoutMinutes", approvalTimeoutMinutes);
        return body;
    }

    // ------------------------------------------------------------------ RBAC

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"RENTER", "TENANT_ADMIN", "PROPERTY_MANAGER"})
    void walkInDeskIsGuardOnly(UserRole role) {
        Fixture f = makeFixture();
        User caller = makeUser(f.org(), role);
        UUID passId = UUID.randomUUID();

        // The whole guard-facing desk: pick a destination, look the visitor up, raise
        // the visit, watch it, admit it. None of it means anything off the gate.
        assertThat(call(HttpMethod.GET, "/api/v1/gatepass/walk-in/destinations?propertyId=" + f.property().getId(),
                caller, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/v1/gatepass/walk-in/visitor?propertyId=" + f.property().getId()
                + "&phone=%2B971501234567", caller, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(multipartCall("/api/v1/gatepass/walk-in", caller,
                walkInParts(f.property().getId(), f.unit().getId(), "Ramesh", "+971501234567", "DELIVERY", false))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/v1/gatepass/walk-in/" + passId + "/status", caller, null)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/api/v1/gatepass/walk-in/" + passId + "/admit", caller, null)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"SECURITY_GUARD", "TENANT_ADMIN", "PROPERTY_MANAGER"})
    void residentApprovalQueueIsRenterOnly(UserRole role) {
        Fixture f = makeFixture();
        User caller = makeUser(f.org(), role);
        UUID passId = UUID.randomUUID();

        // The resident is the approver for a walk-in; nobody else gets the queue, not
        // even the guard standing next to the visitor.
        assertThat(call(HttpMethod.GET, "/api/v1/gatepass/resident-approvals", caller, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/api/v1/gatepass/resident-approvals/" + passId, caller,
                Map.of("approved", true)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"RENTER", "SECURITY_GUARD"})
    void policyAndVisitorRegistrationAreManagerOnly(UserRole role) {
        Fixture f = makeFixture();
        User caller = makeUser(f.org(), role);
        String propertyQuery = "?propertyId=" + f.property().getId();

        assertThat(call(HttpMethod.GET, "/api/v1/gatepass/policies/effective" + propertyQuery, caller, null)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.PUT, "/api/v1/gatepass/policies" + propertyQuery, caller,
                policyBody(true, false, true, true, 15)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.PUT, "/api/v1/gatepass/visitors/" + UUID.randomUUID() + "/registration",
                caller, Map.of("unitId", f.unit().getId().toString(), "active", true)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/api/v1/gatepass/visitors/registration", caller,
                Map.of("propertyId", f.property().getId().toString(), "unitId", f.unit().getId().toString(),
                        "name", "Milk Man", "phone", "+971501234567", "visitorType", "MILK_VENDOR",
                        "active", true)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class,
            names = {"RENTER", "SECURITY_GUARD", "SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER"})
    void visitorPhotoIsOpenToEveryGateRole(UserRole role) {
        Fixture f = makeFixture();
        User caller = makeUser(f.org(), role);

        ResponseEntity<String> res = call(HttpMethod.GET,
                "/api/v1/gatepass/walk-in/" + UUID.randomUUID() + "/photo", caller, null);

        // All four roles have a legitimate reason to see the face at the barrier — the
        // scoping that separates them is per-pass, not per-role, so the door itself
        // must not 403 any of them.
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void visitorPhotoIsClosedToRolesOutsideTheGate() {
        Fixture f = makeFixture();
        User tenantUser = makeUser(f.org(), UserRole.TENANT_USER);

        assertThat(call(HttpMethod.GET, "/api/v1/gatepass/walk-in/" + UUID.randomUUID() + "/photo",
                tenantUser, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------- destinations

    @Test
    void destinationsRejectAPropertyTheGuardIsNotPostedTo() {
        LandlordOrg org = makeOrg();
        Property assigned = makeProperty(org);
        Property elsewhere = makeProperty(org);
        makeUnit(org, elsewhere, null, "E1");
        User guard = makeGuard(org, assigned);

        // 404, not 403 — a guard must not be able to enumerate the tenant's properties
        // by watching which ones answer.
        assertThat(call(HttpMethod.GET, "/api/v1/gatepass/walk-in/destinations?propertyId=" + elsewhere.getId(),
                guard, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void destinationsAreOrderedByTowerThenUnitNumber() {
        LandlordOrg org = makeOrg();
        Property property = makeProperty(org);
        Building towerB = makeBuilding(org, property, "B Tower");
        Building towerA = makeBuilding(org, property, "A Tower");
        makeUnit(org, property, towerB, "B-01");
        makeUnit(org, property, towerA, "A-02");
        makeUnit(org, property, towerA, "A-01");
        // A villa-style unit with no tower at all: the gate still has to be able to
        // send a visitor there, so it sorts first rather than being dropped.
        makeUnit(org, property, null, "Z-01");
        User guard = makeGuard(org, property);

        JsonNode rows = json(call(HttpMethod.GET,
                "/api/v1/gatepass/walk-in/destinations?propertyId=" + property.getId(), guard, null));

        List<String> order = new ArrayList<>();
        rows.forEach(row -> order.add(row.get("unitNumber").asText()));
        assertThat(order).containsExactly("Z-01", "A-01", "A-02", "B-01");
        assertThat(rows.get(0).get("buildingName").isNull()).isTrue();
        assertThat(rows.get(1).get("buildingName").asText()).isEqualTo("A Tower");
        assertThat(rows.get(1).get("buildingId").asText()).isEqualTo(towerA.getId().toString());
        assertThat(rows.get(1).get("propertyId").asText()).isEqualTo(property.getId().toString());
    }

    // ---------------------------------------------------------------- lookup

    @Test
    void lookupIsNotFoundForAPhoneTheGateHasNeverSeen() {
        Fixture f = makeFixture();
        User guard = makeGuard(f.org(), f.property());

        assertThat(call(HttpMethod.GET, "/api/v1/gatepass/walk-in/visitor?propertyId=" + f.property().getId()
                + "&phone=%2B971509999999", guard, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void lookupRejectsAUnitAtAnotherProperty() {
        LandlordOrg org = makeOrg();
        Property assigned = makeProperty(org);
        Property elsewhere = makeProperty(org);
        Unit foreignUnit = makeUnit(org, elsewhere, null, "E1");
        User guard = makeGuard(org, assigned);

        assertThat(call(HttpMethod.GET, "/api/v1/gatepass/walk-in/visitor?propertyId=" + assigned.getId()
                        + "&phone=%2B971501234567&unitId=" + foreignUnit.getId(), guard, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void lookupRecallsAPreviousVisitorAndTheirRegistrationForTheSelectedUnit() {
        Fixture f = makeFixture();
        openGate(f);
        User guard = makeGuard(f.org(), f.property());
        raiseWalkIn(guard, f, "Ramesh Kumar", "+971501234567", true);

        String base = "/api/v1/gatepass/walk-in/visitor?propertyId=" + f.property().getId()
                + "&phone=%2B971501234567&unitId=" + f.unit().getId();
        JsonNode before = json(call(HttpMethod.GET, base, guard, null));
        assertThat(before.get("name").asText()).isEqualTo("Ramesh Kumar");
        assertThat(before.get("phone").asText()).isEqualTo("+971501234567");
        assertThat(before.get("visitorType").asText()).isEqualTo("DELIVERY");
        assertThat(before.get("vehicleNumber").asText()).isEqualTo("DXB-4242");
        assertThat(before.get("lastUnitId").asText()).isEqualTo(f.unit().getId().toString());
        assertThat(before.get("photoUrl").asText()).isEqualTo(PHOTO_URL);
        assertThat(before.get("registeredForSelectedUnit").asBoolean()).isFalse();

        // The manager marks them as a standing vendor for that unit...
        User manager = makeUser(f.org(), UserRole.PROPERTY_MANAGER);
        assignIfManager(manager, f.property());
        UUID profileId = UUID.fromString(before.get("id").asText());
        assertThat(call(HttpMethod.PUT, "/api/v1/gatepass/visitors/" + profileId + "/registration", manager,
                Map.of("unitId", f.unit().getId().toString(), "active", true)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // ...and the same lookup now tells the guard so, which is what decides whether
        // the next visit waits for approval.
        assertThat(json(call(HttpMethod.GET, base, guard, null)).get("registeredForSelectedUnit").asBoolean())
                .isTrue();
    }

    // --------------------------------------------------------- walk-in create

    @Test
    void walkInRejectsAGuardWhoIsNotPostedToTheProperty() {
        Fixture f = makeFixture();
        openGate(f);
        User unpostedGuard = makeGuard(f.org());

        assertThat(multipartCall("/api/v1/gatepass/walk-in", unpostedGuard,
                walkInParts(f.property().getId(), f.unit().getId(), "Ramesh", "+971501234567", "DELIVERY", false))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void walkInRejectsAUnitThatIsNotAtTheNamedProperty() {
        LandlordOrg org = makeOrg();
        Property assigned = makeProperty(org);
        Property elsewhere = makeProperty(org);
        Unit foreignUnit = makeUnit(org, elsewhere, null, "E1");
        User guard = makeGuard(org, assigned, elsewhere);

        // Posted to both properties, so only the unit-at-property check can reject
        // this — without it a guard could route a visitor into any unit in the tenant.
        assertThat(multipartCall("/api/v1/gatepass/walk-in", guard,
                walkInParts(assigned.getId(), foreignUnit.getId(), "Ramesh", "+971501234567", "DELIVERY", false))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void walkInReturnsTheVisitorPassWithoutAnyAdmissionCredentials() {
        Fixture f = makeFixture();
        openGate(f);
        User guard = makeGuard(f.org(), f.property());

        ResponseEntity<String> res = multipartCall("/api/v1/gatepass/walk-in", guard,
                walkInParts(f.property().getId(), f.unit().getId(), "Ramesh Kumar", "+971 50 123 4567",
                        "DELIVERY", true));
        JsonNode pass = json(res);

        Set<String> fields = new HashSet<>();
        pass.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "propertyId", "unitId", "unitNumber", "guestName",
                "guestPhone", "visitorType", "purpose", "vehicleNumber", "guestPhotoUrl", "status", "validTo",
                "createdAt");
        // The guard admits this pass through /admit, never by scanning a code, so the
        // walk-in payload must not carry one.
        assertThat(res.getBody()).doesNotContain("qrToken");
        assertThat(res.getBody()).doesNotContain("numericCode");

        assertThat(pass.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(pass.get("unitNumber").asText()).isEqualTo("A1");
        assertThat(pass.get("guestPhone").asText()).isEqualTo("+971501234567");
        assertThat(pass.get("guestPhotoUrl").asText()).isEqualTo(PHOTO_URL);
        assertThat(pass.get("propertyId").asText()).isEqualTo(f.property().getId().toString());
    }

    @Test
    void walkInWithoutAPhotoIsRefusedWhereTheGateRequiresOne() {
        Fixture f = makeFixture();
        // No policy row at all: the hard default requires a fresh photo.
        User guard = makeGuard(f.org(), f.property());

        ResponseEntity<String> res = multipartCall("/api/v1/gatepass/walk-in", guard,
                walkInParts(f.property().getId(), f.unit().getId(), "Ramesh", "+971501234567", "DELIVERY", false));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("fresh visitor photo");
    }

    // ---------------------------------------------------------------- status

    @Test
    void statusExpiresAPendingWalkInWhoseWindowHasClosed() {
        Fixture f = makeFixture();
        approvalGate(f);
        User guard = makeGuard(f.org(), f.property());
        UUID passId = UUID.fromString(raiseWalkIn(guard, f, "Ramesh", "+971501234567", false).get("id").asText());

        GatePass pass = passRepo.findById(passId).orElseThrow();
        assertThat(pass.getStatus()).isEqualTo(GatePassStatus.PENDING_APPROVAL);
        pass.setValidTo(Instant.now().minus(1, ChronoUnit.MINUTES));
        passRepo.save(pass);

        JsonNode res = json(call(HttpMethod.GET, "/api/v1/gatepass/walk-in/" + passId + "/status", guard, null));

        // Read-time expiry: there is no scheduler behind this, so the guard's own
        // polling is what closes an unanswered request.
        assertThat(res.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(passRepo.findById(passId).orElseThrow().getStatus()).isEqualTo(GatePassStatus.EXPIRED);
    }

    @Test
    void statusRejectsAGuardPostedToAnotherProperty() {
        LandlordOrg org = makeOrg();
        Property elsewhere = makeProperty(org);
        Fixture f = makeResidentWithActiveLease(org, elsewhere, "T1");
        openGate(f);
        User localGuard = makeGuard(org, elsewhere);
        UUID passId = UUID.fromString(raiseWalkIn(localGuard, f, "Ramesh", "+971501234567", false)
                .get("id").asText());

        User otherGuard = makeGuard(org, makeProperty(org));

        assertThat(call(HttpMethod.GET, "/api/v1/gatepass/walk-in/" + passId + "/status", otherGuard, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void statusRejectsAPassThatIsNotAWalkIn() {
        Fixture f = makeFixture();
        User guard = makeGuard(f.org(), f.property());
        UUID renterPassId = UUID.fromString(createRenterPass(f, "Guest Alpha", "SINGLE_USE").get("id").asText());

        // A renter's own pass is read through /gatepass/{id} by its creator. Letting it
        // through the walk-in path would hand the guard a pass with no walk-in identity
        // behind it, and put a resident's guest into the wrong review flow.
        assertThat(call(HttpMethod.GET, "/api/v1/gatepass/walk-in/" + renterPassId + "/status", guard, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ----------------------------------------------------------------- photo

    @Test
    void residentReadsTheVisitorPhotoForTheirOwnUnit() {
        Fixture f = makeFixture();
        approvalGate(f);
        User guard = makeGuard(f.org(), f.property());
        UUID passId = UUID.fromString(raiseWalkIn(guard, f, "Ramesh", "+971501234567", true).get("id").asText());

        ResponseEntity<String> res = call(HttpMethod.GET, "/api/v1/gatepass/walk-in/" + passId + "/photo",
                f.renterUser(), null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo(new String(PHOTO_BYTES, StandardCharsets.UTF_8));
        assertThat(res.getHeaders().getFirst("Cache-Control")).contains("private");
    }

    @Test
    void visitorPhotoIsHiddenFromAResidentOfAnotherUnit() {
        Fixture f = makeFixture();
        approvalGate(f);
        Fixture neighbour = makeResidentWithActiveLease(f.org(), f.property(), "A2");
        User guard = makeGuard(f.org(), f.property());
        UUID passId = UUID.fromString(raiseWalkIn(guard, f, "Ramesh", "+971501234567", true).get("id").asText());

        assertThat(call(HttpMethod.GET, "/api/v1/gatepass/walk-in/" + passId + "/photo",
                neighbour.renterUser(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void visitorPhotoIsHiddenFromAGuardAtAnotherProperty() {
        LandlordOrg org = makeOrg();
        Property elsewhere = makeProperty(org);
        Fixture f = makeResidentWithActiveLease(org, elsewhere, "T1");
        approvalGate(f);
        User localGuard = makeGuard(org, elsewhere);
        UUID passId = UUID.fromString(raiseWalkIn(localGuard, f, "Ramesh", "+971501234567", true)
                .get("id").asText());

        User otherGuard = makeGuard(org, makeProperty(org));

        assertThat(call(HttpMethod.GET, "/api/v1/gatepass/walk-in/" + passId + "/photo", otherGuard, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void visitorPhotoIsNotFoundWhenTheVisitStoredNoImage() {
        Fixture f = makeFixture();
        openGate(f);
        User guard = makeGuard(f.org(), f.property());
        UUID passId = UUID.fromString(raiseWalkIn(guard, f, "Ramesh", "+971501234567", false).get("id").asText());

        assertThat(call(HttpMethod.GET, "/api/v1/gatepass/walk-in/" + passId + "/photo", guard, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ----------------------------------------------------------------- admit

    @Test
    void guardAdmitsAnActiveWalkInAndTheEntryIsAudited() {
        Fixture f = makeFixture();
        openGate(f);
        User guard = makeGuard(f.org(), f.property());
        UUID passId = UUID.fromString(raiseWalkIn(guard, f, "Ramesh", "+971501234567", false).get("id").asText());

        JsonNode res = json(call(HttpMethod.POST, "/api/v1/gatepass/walk-in/" + passId + "/admit", guard, null));

        assertThat(res.get("status").asText()).isEqualTo("USED");
        assertThat(res.get("unitNumber").asText()).isEqualTo("A1");

        List<GatePassScan> scans = scanRepo.findAll().stream()
                .filter(s -> passId.equals(s.getGatePassId()))
                .toList();
        assertThat(scans).hasSize(1);
        assertThat(scans.get(0).getDirection()).isEqualTo(ScanDirection.ENTRY);
        assertThat(scans.get(0).getResult()).isEqualTo(ScanResult.ALLOWED);
        assertThat(scans.get(0).getScannedByUserId()).isEqualTo(guard.getId());
    }

    @Test
    void admittingAWalkInStillAwaitingTheResidentIsRefused() {
        Fixture f = makeFixture();
        approvalGate(f);
        User guard = makeGuard(f.org(), f.property());
        UUID passId = UUID.fromString(raiseWalkIn(guard, f, "Ramesh", "+971501234567", false).get("id").asText());

        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/gatepass/walk-in/" + passId + "/admit",
                guard, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // The scan service's own verdict, surfaced verbatim — the guard has to be able
        // to tell the visitor what they are waiting for.
        assertThat(res.getBody()).contains("pending approval");
    }

    @Test
    void admittingTheSameWalkInTwiceIsRefused() {
        Fixture f = makeFixture();
        openGate(f);
        User guard = makeGuard(f.org(), f.property());
        UUID passId = UUID.fromString(raiseWalkIn(guard, f, "Ramesh", "+971501234567", false).get("id").asText());

        assertThat(call(HttpMethod.POST, "/api/v1/gatepass/walk-in/" + passId + "/admit", guard, null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> second = call(HttpMethod.POST, "/api/v1/gatepass/walk-in/" + passId + "/admit",
                guard, null);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(second.getBody()).contains("already used");
    }

    // ---------------------------------------------------- resident approvals

    @Test
    void residentSeesOnlyWalkInsAwaitingTheirOwnDecision() {
        Fixture f = makeFixture();
        approvalGate(f);
        Fixture neighbour = makeResidentWithActiveLease(f.org(), f.property(), "A2");
        User guard = makeGuard(f.org(), f.property());

        UUID mine = UUID.fromString(raiseWalkIn(guard, f, "Mine", "+971501111111", false).get("id").asText());
        raiseWalkIn(guard, neighbour, "Theirs", "+971502222222", false);
        // A renter-origin RECURRING pass is also PENDING_APPROVAL on the same unit —
        // it belongs to the manager/guard queue, not to this one.
        createRenterPass(f, "Recurring Guest", "RECURRING");
        // ...and a walk-in whose window has already closed is nobody's decision now.
        UUID lapsed = UUID.fromString(raiseWalkIn(guard, f, "Lapsed", "+971503333333", false).get("id").asText());
        GatePass expired = passRepo.findById(lapsed).orElseThrow();
        expired.setValidTo(Instant.now().minus(1, ChronoUnit.MINUTES));
        passRepo.save(expired);

        JsonNode rows = json(call(HttpMethod.GET, "/api/v1/gatepass/resident-approvals", f.renterUser(), null));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("id").asText()).isEqualTo(mine.toString());
        assertThat(rows.get(0).get("guestName").asText()).isEqualTo("Mine");
        assertThat(rows.get(0).get("unitNumber").asText()).isEqualTo("A1");
    }

    @Test
    void residentWithNoActiveLeaseSeesAnEmptyQueue() {
        Fixture f = makeFixture();
        approvalGate(f);
        User guard = makeGuard(f.org(), f.property());
        raiseWalkIn(guard, f, "Ramesh", "+971501234567", false);

        // A renter profile with no lease at all: the queue must be empty rather than
        // falling back to the tenant's pending walk-ins.
        User strangerUser = makeUser(f.org(), UserRole.RENTER);
        Renter stranger = new Renter();
        stranger.setUserId(strangerUser.getId());
        stranger.setNameEn(strangerUser.getName());
        stranger.setTenantId(f.tenantId());
        renterRepo.save(stranger);

        ResponseEntity<String> res = call(HttpMethod.GET, "/api/v1/gatepass/resident-approvals",
                strangerUser, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(res)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void residentDecisionSettlesTheWalkIn(boolean approved) {
        Fixture f = makeFixture();
        approvalGate(f);
        User guard = makeGuard(f.org(), f.property());
        UUID passId = UUID.fromString(raiseWalkIn(guard, f, "Ramesh", "+971501234567", false).get("id").asText());

        JsonNode res = json(call(HttpMethod.POST, "/api/v1/gatepass/resident-approvals/" + passId,
                f.renterUser(), Map.of("approved", approved)));

        assertThat(res.get("status").asText()).isEqualTo(approved ? "ACTIVE" : "CANCELLED");
        assertThat(passRepo.findById(passId).orElseThrow().getStatus())
                .isEqualTo(approved ? GatePassStatus.ACTIVE : GatePassStatus.CANCELLED);
    }

    @Test
    void aResidentOfAnotherUnitCannotDecideTheWalkIn() {
        Fixture f = makeFixture();
        approvalGate(f);
        Fixture neighbour = makeResidentWithActiveLease(f.org(), f.property(), "A2");
        User guard = makeGuard(f.org(), f.property());
        UUID passId = UUID.fromString(raiseWalkIn(guard, f, "Ramesh", "+971501234567", false).get("id").asText());

        // 404 rather than 403: a neighbour must not learn that somebody is at A1's door.
        assertThat(call(HttpMethod.POST, "/api/v1/gatepass/resident-approvals/" + passId,
                neighbour.renterUser(), Map.of("approved", true)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(passRepo.findById(passId).orElseThrow().getStatus())
                .isEqualTo(GatePassStatus.PENDING_APPROVAL);
    }

    // -------------------------------------------------------------- policies

    @ParameterizedTest
    // SUPER_ADMIN acting in an organisation is tenant-wide, like a tenant admin (#88).
    @EnumSource(value = UserRole.class, names = {"SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER"})
    void managerWritesAndReadsBackThePropertyWidePolicy(UserRole managerRole) {
        LandlordOrg org = makeOrg();
        Property property = makeProperty(org);
        User manager = makeUser(org, managerRole);
        assignIfManager(manager, property);
        String query = "?propertyId=" + property.getId();

        JsonNode written = json(call(HttpMethod.PUT, "/api/v1/gatepass/policies" + query, manager,
                policyBody(false, true, false, false, 25)));
        assertThat(written.get("inherited").asBoolean()).isFalse();
        assertThat(written.get("id").isNull()).isFalse();

        JsonNode effective = json(call(HttpMethod.GET, "/api/v1/gatepass/policies/effective" + query,
                manager, null));
        assertThat(effective.get("requireUnregisteredApproval").asBoolean()).isFalse();
        assertThat(effective.get("requireRegisteredApproval").asBoolean()).isTrue();
        assertThat(effective.get("notifyRegisteredEntry").asBoolean()).isFalse();
        assertThat(effective.get("requireFreshPhoto").asBoolean()).isFalse();
        assertThat(effective.get("approvalTimeoutMinutes").asInt()).isEqualTo(25);
    }

    @Test
    void aTowerPolicyOverridesThePropertyWideOneForThatTowerOnly() {
        LandlordOrg org = makeOrg();
        Property property = makeProperty(org);
        Building tower = makeBuilding(org, property, "A Tower");
        User manager = makeUser(org, UserRole.TENANT_ADMIN);

        call(HttpMethod.PUT, "/api/v1/gatepass/policies?propertyId=" + property.getId(), manager,
                policyBody(true, false, true, true, 15));
        call(HttpMethod.PUT, "/api/v1/gatepass/policies?propertyId=" + property.getId()
                + "&buildingId=" + tower.getId(), manager, policyBody(false, false, false, false, 90));

        JsonNode towerView = json(call(HttpMethod.GET, "/api/v1/gatepass/policies/effective?propertyId="
                + property.getId() + "&buildingId=" + tower.getId(), manager, null));
        assertThat(towerView.get("approvalTimeoutMinutes").asInt()).isEqualTo(90);
        assertThat(towerView.get("requireFreshPhoto").asBoolean()).isFalse();

        // The property-wide row is untouched — writing a tower override must not be a
        // way to relax every other gate on the estate.
        JsonNode propertyView = json(call(HttpMethod.GET, "/api/v1/gatepass/policies/effective?propertyId="
                + property.getId(), manager, null));
        assertThat(propertyView.get("approvalTimeoutMinutes").asInt()).isEqualTo(15);
        assertThat(propertyView.get("requireFreshPhoto").asBoolean()).isTrue();
    }

    @Test
    void effectivePolicyFallsBackToTheHardDefaultsWhenNothingIsConfigured() {
        LandlordOrg org = makeOrg();
        Property property = makeProperty(org);
        User manager = makeUser(org, UserRole.TENANT_ADMIN);

        JsonNode effective = json(call(HttpMethod.GET,
                "/api/v1/gatepass/policies/effective?propertyId=" + property.getId(), manager, null));

        assertThat(effective.get("inherited").asBoolean()).isTrue();
        assertThat(effective.get("requireUnregisteredApproval").asBoolean()).isTrue();
        assertThat(effective.get("requireRegisteredApproval").asBoolean()).isFalse();
        assertThat(effective.get("notifyRegisteredEntry").asBoolean()).isTrue();
        assertThat(effective.get("requireFreshPhoto").asBoolean()).isTrue();
        assertThat(effective.get("approvalTimeoutMinutes").asInt()).isEqualTo(15);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5, 1441})
    void policyRejectsAnApprovalTimeoutOutsideTheSupportedRange(int minutes) {
        LandlordOrg org = makeOrg();
        Property property = makeProperty(org);
        User manager = makeUser(org, UserRole.TENANT_ADMIN);

        ResponseEntity<String> res = call(HttpMethod.PUT,
                "/api/v1/gatepass/policies?propertyId=" + property.getId(), manager,
                policyBody(true, false, true, true, minutes));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(policyRepo.findByTenantIdAndPropertyId(org.getId(), property.getId())).isEmpty();
    }

    @Test
    void policyRejectsAPropertyOrTowerOutsideTheCallersTenant() {
        LandlordOrg orgA = makeOrg();
        LandlordOrg orgB = makeOrg();
        Property foreignProperty = makeProperty(orgB);
        Property ownProperty = makeProperty(orgA);
        Building foreignTower = makeBuilding(orgB, foreignProperty, "B Tower");
        Building otherPropertyTower = makeBuilding(orgA, makeProperty(orgA), "Wrong Estate");
        User manager = makeUser(orgA, UserRole.TENANT_ADMIN);

        assertThat(call(HttpMethod.GET, "/api/v1/gatepass/policies/effective?propertyId="
                + foreignProperty.getId(), manager, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PUT, "/api/v1/gatepass/policies?propertyId=" + foreignProperty.getId(),
                manager, policyBody(true, false, true, true, 15)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PUT, "/api/v1/gatepass/policies?propertyId=" + ownProperty.getId()
                        + "&buildingId=" + foreignTower.getId(), manager, policyBody(true, false, true, true, 15))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // A tower in the caller's own tenant, but on a different estate: the pair has
        // to be consistent or the override would land on the wrong gate.
        assertThat(call(HttpMethod.PUT, "/api/v1/gatepass/policies?propertyId=" + ownProperty.getId()
                        + "&buildingId=" + otherPropertyTower.getId(), manager,
                policyBody(true, false, true, true, 15)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --------------------------------------------------- visitor registration

    @Test
    void managerRegistersAStandingVisitorAndTheGateAdmitsThemWithoutApproval() {
        Fixture f = makeFixture();
        // Strangers wait; the point of the registration is that this one does not.
        approvalGate(f);
        User manager = makeUser(f.org(), UserRole.PROPERTY_MANAGER);
        assignIfManager(manager, f.property());
        User guard = makeGuard(f.org(), f.property());

        JsonNode created = json(call(HttpMethod.POST, "/api/v1/gatepass/visitors/registration", manager,
                Map.of("propertyId", f.property().getId().toString(), "unitId", f.unit().getId().toString(),
                        "name", "Milk Man", "phone", "00971505555555", "visitorType", "MILK_VENDOR",
                        "active", true)));
        assertThat(created.get("phone").asText()).isEqualTo("+971505555555");
        assertThat(created.get("registeredForSelectedUnit").asBoolean()).isTrue();

        JsonNode pass = raiseWalkIn(guard, f, "Milk Man", "+971505555555", false);
        assertThat(pass.get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void visitorRegistrationRejectsAUnitOutsideTheCallersTenant() {
        LandlordOrg orgA = makeOrg();
        LandlordOrg orgB = makeOrg();
        Property foreignProperty = makeProperty(orgB);
        Unit foreignUnit = makeUnit(orgB, foreignProperty, null, "B1");
        User manager = makeUser(orgA, UserRole.TENANT_ADMIN);

        assertThat(call(HttpMethod.PUT, "/api/v1/gatepass/visitors/" + UUID.randomUUID() + "/registration",
                manager, Map.of("unitId", foreignUnit.getId().toString(), "active", true)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST, "/api/v1/gatepass/visitors/registration", manager,
                Map.of("propertyId", foreignProperty.getId().toString(), "unitId", foreignUnit.getId().toString(),
                        "name", "Milk Man", "phone", "+971505555555", "visitorType", "MILK_VENDOR",
                        "active", true)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------- helpers

    /** Creates an ordinary renter-origin pass through the public API. */
    private JsonNode createRenterPass(Fixture f, String guestName, String type) {
        Instant now = Instant.now();
        Map<String, Object> body = new HashMap<>();
        body.put("unitId", f.unit().getId().toString());
        body.put("guestName", guestName);
        body.put("guestPhone", "+971500000000");
        body.put("purpose", "Visit");
        body.put("passType", type);
        body.put("validFrom", now.minus(1, ChronoUnit.HOURS).toString());
        body.put("validTo", now.plus(6, ChronoUnit.HOURS).toString());
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/gatepass", f.renterUser(), body);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json(res);
    }
}
