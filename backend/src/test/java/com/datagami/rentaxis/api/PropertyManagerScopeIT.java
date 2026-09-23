package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.GatePass;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.GatePassOrigin;
import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import com.datagami.rentaxis.domain.entity.enums.GatePassType;
import com.datagami.rentaxis.domain.entity.enums.GateVisitorType;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.GatePassRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenewalOpportunityRepository;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 5 (audit P1-3..P1-6, #72, D-F7): a PROPERTY_MANAGER assigned to Marina gets
 * 404 or an empty list for Palm, in every area that hangs off a property, and the
 * same call on Marina still works. Full HTTP with the headers the Next.js proxy
 * sends, because {@code @PreAuthorize} and the principal only exist after the filter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PropertyManagerScopeIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LeaseService leaseService;
    @Autowired LeaseRepository leaseRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;
    @Autowired RenewalOpportunityRepository opportunityRepo;
    @Autowired GatePassRepository passRepo;
    @Autowired TenantFeatureService featureService;
    @Autowired TransactionTemplate tx;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.datagami.rentaxis.core.service.BlobStorageService blob;

    private final ObjectMapper json = new ObjectMapper();

    private LeaseTestFixtures fixtures;
    private User admin;
    private User pm;
    private Property marina;
    private Property palm;
    private Unit marinaUnit;
    private Unit palmUnit;
    private UUID marinaLease;
    private UUID palmLease;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, null, null);
        marina = fixtures.property();
        palm = fixtures.createProperty("PALM");
        marinaUnit = fixtures.unit();
        palmUnit = fixtures.createUnit(palm, "901");
        marinaLease = draft(marinaUnit);
        palmLease = draft(palmUnit);

        admin = user(UserRole.TENANT_ADMIN);
        pm = user(UserRole.PROPERTY_MANAGER);
        UserPropertyAssignment a = new UserPropertyAssignment();
        a.setUserId(pm.getId());
        a.setPropertyId(marina.getId());
        assignmentRepo.save(a);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ---------------------------------------------------------------- leases

    @Test
    void leaseInteractionsEventsAndFollowUpsStopAtTheManagersBuildings() throws Exception {
        Map<String, Object> note = Map.of("type", "CALL", "direction", "OUTBOUND",
                "occurredAt", Instant.now().minusSeconds(60).toString(), "summary", "Called about renewal",
                "followUpDate", LocalDate.now().toString());
        assertThat(call(admin, HttpMethod.POST, "/api/v1/leases/" + palmLease + "/interactions", note)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> marinaNote = call(admin, HttpMethod.POST,
                "/api/v1/leases/" + marinaLease + "/interactions", note);
        String marinaNoteId = body(marinaNote).get("id").asText();
        String palmNoteId = body(call(admin, HttpMethod.GET, "/api/v1/leases/" + palmLease + "/interactions", null))
                .get("content").get(0).get("id").asText();

        // Follow-ups: the manager sees Marina's, never Palm's.
        List<String> followUpLeases = new ArrayList<>();
        body(call(pm, HttpMethod.GET, "/api/v1/renewals/follow-ups?date=2099-12-31", null))
                .forEach(n -> followUpLeases.add(n.get("leaseId").asText()));
        assertThat(followUpLeases).contains(marinaLease.toString()).doesNotContain(palmLease.toString());

        assertThat(status(pm, HttpMethod.GET, "/api/v1/leases/" + marinaLease + "/interactions")).isEqualTo(HttpStatus.OK);
        assertThat(status(pm, HttpMethod.GET, "/api/v1/leases/" + palmLease + "/interactions")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(pm, HttpMethod.POST, "/api/v1/leases/" + palmLease + "/interactions", note).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(pm, HttpMethod.GET, "/api/v1/leases/" + marinaLease + "/events")).isEqualTo(HttpStatus.OK);
        assertThat(status(pm, HttpMethod.GET, "/api/v1/leases/" + palmLease + "/events")).isEqualTo(HttpStatus.NOT_FOUND);

        Map<String, Object> edit = Map.of("summary", "rewritten");
        // Palm's note, whether addressed under Palm's lease or smuggled under Marina's path.
        assertThat(call(pm, HttpMethod.PATCH, "/api/v1/leases/" + palmLease + "/interactions/" + palmNoteId, edit)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(pm, HttpMethod.PATCH, "/api/v1/leases/" + marinaLease + "/interactions/" + palmNoteId, edit)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // The path's lease binds the update even for an admin.
        assertThat(call(admin, HttpMethod.PATCH, "/api/v1/leases/" + palmLease + "/interactions/" + marinaNoteId, edit)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(pm, HttpMethod.PATCH, "/api/v1/leases/" + marinaLease + "/interactions/" + marinaNoteId, edit)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void markRenewedAndAttachmentsStopAtTheManagersBuildings() {
        openOpportunity(palmLease);
        openOpportunity(marinaLease);
        assertThat(call(pm, HttpMethod.POST, "/api/v1/leases/" + palmLease + "/renewal/mark-renewed", Map.of())
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(pm, HttpMethod.POST, "/api/v1/leases/" + marinaLease + "/renewal/mark-renewed", Map.of())
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(upload(pm, "/api/v1/leases/" + palmLease + "/attachments").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> uploaded = upload(admin, "/api/v1/leases/" + palmLease + "/attachments");
        assertThat(uploaded.getStatusCode().is2xxSuccessful()).isTrue();
        String attachmentId = body(uploaded).get("id").asText();
        assertThat(status(pm, HttpMethod.DELETE, "/api/v1/leases/attachments/" + attachmentId))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------- listings

    @Test
    void listingsStopAtTheManagersBuildings() {
        featureService.setEnabled(fixtures.tenantId(), TenantFeature.LISTINGS, true);
        String palmListing = body(call(admin, HttpMethod.POST, "/api/listings",
                Map.of("unitId", palmUnit.getId().toString(), "titleEn", "Palm 901"))).get("id").asText();
        String marinaListing = body(call(admin, HttpMethod.POST, "/api/listings",
                Map.of("unitId", marinaUnit.getId().toString(), "titleEn", "Marina 101"))).get("id").asText();

        List<String> listed = new ArrayList<>();
        body(call(pm, HttpMethod.GET, "/api/listings", null)).get("content")
                .forEach(n -> listed.add(n.get("id").asText()));
        assertThat(listed).containsExactly(marinaListing);

        assertThat(status(pm, HttpMethod.GET, "/api/listings/" + marinaListing)).isEqualTo(HttpStatus.OK);
        assertThat(status(pm, HttpMethod.GET, "/api/listings/" + palmListing)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(pm, HttpMethod.GET, "/api/listings/" + palmListing + "/interests")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(pm, HttpMethod.POST, "/api/listings/" + palmListing + "/unlist")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(pm, HttpMethod.DELETE, "/api/listings/" + palmListing)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(admin, HttpMethod.GET, "/api/listings/" + palmListing)).isEqualTo(HttpStatus.OK);

        // Nor may a manager list another building's unit.
        Unit palmOther = fixtures.createUnit(palm, "902");
        assertThat(call(pm, HttpMethod.POST, "/api/listings",
                Map.of("unitId", palmOther.getId().toString(), "titleEn", "Palm 902")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ----------------------------------------------------------------- gate

    @Test
    void gatePassesWalkInsAndGuardPostingsStopAtTheManagersBuildings() {
        GatePass palmPass = pendingPass(palm, palmUnit);
        GatePass marinaPass = pendingPass(marina, marinaUnit);

        List<String> queue = new ArrayList<>();
        body(call(pm, HttpMethod.GET, "/api/v1/gatepass/approvals", null)).forEach(n -> queue.add(n.get("id").asText()));
        assertThat(queue).contains(marinaPass.getId().toString()).doesNotContain(palmPass.getId().toString());

        assertThat(call(pm, HttpMethod.POST, "/api/v1/gatepass/" + palmPass.getId() + "/approval",
                Map.of("approved", true)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        scan(palmPass);
        scan(marinaPass);
        String window = "?from=2020-01-01T00:00:00Z&to=2099-01-01T00:00:00Z";
        // No property named: the manager's report is their buildings' traffic, not the tenant's.
        List<String> reported = new ArrayList<>();
        body(call(pm, HttpMethod.GET, "/api/v1/gatepass/report" + window, null))
                .forEach(n -> reported.add(n.get("gatePassId").asText()));
        assertThat(reported).containsExactly(marinaPass.getId().toString());
        assertThat(status(pm, HttpMethod.GET, "/api/v1/gatepass/report" + window + "&propertyId=" + palm.getId()))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(pm, HttpMethod.GET, "/api/v1/gatepass/report" + window + "&propertyId=" + marina.getId()))
                .isEqualTo(HttpStatus.OK);

        Map<String, Object> policy = Map.of("requireUnregisteredApproval", false, "requireRegisteredApproval", false,
                "notifyRegisteredEntry", false, "requireFreshPhoto", false, "approvalTimeoutMinutes", 10);
        assertThat(call(pm, HttpMethod.PUT, "/api/v1/gatepass/policies?propertyId=" + palm.getId(), policy)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(pm, HttpMethod.PUT, "/api/v1/gatepass/policies?propertyId=" + marina.getId(), policy)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        User guard = user(UserRole.SECURITY_GUARD);
        assertThat(call(admin, HttpMethod.PUT, "/api/v1/gatepass/guards/" + guard.getId() + "/properties",
                List.of(palm.getId().toString())).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(pm, HttpMethod.PUT, "/api/v1/gatepass/guards/" + guard.getId() + "/properties",
                List.of(palm.getId().toString())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Posting the guard at Marina keeps the Palm posting the manager cannot see.
        assertThat(call(pm, HttpMethod.PUT, "/api/v1/gatepass/guards/" + guard.getId() + "/properties",
                List.of(marina.getId().toString())).getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> postings = new ArrayList<>();
        body(call(admin, HttpMethod.GET, "/api/v1/gatepass/guards/" + guard.getId() + "/properties", null))
                .forEach(n -> postings.add(n.asText()));
        assertThat(postings).containsExactlyInAnyOrder(palm.getId().toString(), marina.getId().toString());
        List<String> pmView = new ArrayList<>();
        body(call(pm, HttpMethod.GET, "/api/v1/gatepass/guards/" + guard.getId() + "/properties", null))
                .forEach(n -> pmView.add(n.asText()));
        assertThat(pmView).containsExactly(marina.getId().toString());
    }

    @Test
    void walkInPhotosAndVisitorRegistrationsStopAtTheManagersBuildings() {
        org.mockito.Mockito.when(blob.download(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.datagami.rentaxis.core.service.BlobStorageService.DownloadResult(new byte[]{1}, "image/jpeg"));
        GatePass palmWalkIn = walkIn(palm, palmUnit);
        GatePass marinaWalkIn = walkIn(marina, marinaUnit);
        assertThat(status(pm, HttpMethod.GET, "/api/v1/gatepass/walk-in/" + palmWalkIn.getId() + "/photo"))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(pm, HttpMethod.GET, "/api/v1/gatepass/walk-in/" + marinaWalkIn.getId() + "/photo"))
                .isEqualTo(HttpStatus.OK);

        Map<String, Object> palmVisitor = Map.of("propertyId", palm.getId().toString(),
                "unitId", palmUnit.getId().toString(), "name", "Nanny", "phone", "+971502223344",
                "visitorType", "MAID", "active", true);
        assertThat(call(pm, HttpMethod.POST, "/api/v1/gatepass/visitors/registration", palmVisitor)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> registered = call(admin, HttpMethod.POST, "/api/v1/gatepass/visitors/registration", palmVisitor);
        assertThat(registered.getStatusCode()).as(registered.getBody()).isEqualTo(HttpStatus.OK);
        String profileId = body(registered).get("id").asText();
        assertThat(call(pm, HttpMethod.PUT, "/api/v1/gatepass/visitors/" + profileId + "/registration",
                Map.of("unitId", palmUnit.getId().toString(), "active", false)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(pm, HttpMethod.POST, "/api/v1/gatepass/visitors/registration", Map.of(
                "propertyId", marina.getId().toString(), "unitId", marinaUnit.getId().toString(), "name", "Driver",
                "phone", "+971502223355", "visitorType", "OTHER", "active", true)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // -------------------------------------------------------------- meetings

    @Test
    void meetingsStopAtTheManagersBuildings() {
        String palmMeeting = meeting(palm);
        String marinaMeeting = meeting(marina);

        List<String> listed = new ArrayList<>();
        body(call(pm, HttpMethod.GET, "/api/v1/meetings", null)).get("content")
                .forEach(n -> listed.add(n.get("id").asText()));
        assertThat(listed).contains(marinaMeeting).doesNotContain(palmMeeting);

        List<String> calendar = new ArrayList<>();
        body(call(pm, HttpMethod.GET, "/api/v1/meetings/calendar?start=" + Instant.now() + "&end="
                + Instant.now().plus(30, ChronoUnit.DAYS), null)).get("content")
                .forEach(n -> calendar.add(n.get("id").asText()));
        assertThat(calendar).contains(marinaMeeting).doesNotContain(palmMeeting);

        assertThat(status(pm, HttpMethod.GET, "/api/v1/meetings/" + palmMeeting)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(pm, HttpMethod.PUT, "/api/v1/meetings/" + palmMeeting + "/approve")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(pm, HttpMethod.PUT, "/api/v1/meetings/" + marinaMeeting + "/approve")).isEqualTo(HttpStatus.OK);
    }

    // --------------------------------------------------------------- tickets

    @Test
    void ticketsStopAtTheManagersBuildings() {
        String palmTicket = ticket(palm);
        String marinaTicket = ticket(marina);

        assertThat(status(pm, HttpMethod.GET, "/api/v1/tickets/" + palmTicket)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(pm, HttpMethod.GET, "/api/v1/tickets/" + palmTicket + "/replies")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(pm, HttpMethod.PUT, "/api/v1/tickets/" + palmTicket + "/assign",
                Map.of("assignTo", pm.getId().toString())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(pm, HttpMethod.PUT, "/api/v1/tickets/" + palmTicket + "/status",
                Map.of("status", "IN_PROGRESS")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(pm, HttpMethod.GET, "/api/v1/tickets/reports?propertyId=" + palm.getId()))
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(body(call(pm, HttpMethod.GET, "/api/v1/tickets/reports", null)).get("totalTickets").asLong())
                .isEqualTo(1);
        assertThat(call(pm, HttpMethod.POST, "/api/v1/tickets", Map.of("propertyId", palm.getId().toString(),
                "title", "Raised by the wrong manager")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(status(pm, HttpMethod.GET, "/api/v1/tickets/" + marinaTicket)).isEqualTo(HttpStatus.OK);
        assertThat(call(pm, HttpMethod.PUT, "/api/v1/tickets/" + marinaTicket + "/assign",
                Map.of("assignTo", pm.getId().toString())).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------- inventory

    @Test
    void unitsBuildingsPropertiesAndContactsStopAtTheManagersBuildings() {
        String palmTower = body(call(admin, HttpMethod.POST, "/api/v1/buildings",
                Map.of("property", Map.of("id", palm.getId().toString()), "nameEn", "Palm Tower"))).get("id").asText();

        assertThat(body(call(pm, HttpMethod.GET, "/api/v1/units/property/" + palm.getId(), null))).isEmpty();
        assertThat(body(call(pm, HttpMethod.GET, "/api/v1/units/property/" + marina.getId(), null))).isNotEmpty();
        List<String> units = new ArrayList<>();
        body(call(pm, HttpMethod.GET, "/api/v1/units", null)).forEach(n -> units.add(n.get("id").asText()));
        assertThat(units).contains(marinaUnit.getId().toString()).doesNotContain(palmUnit.getId().toString());

        assertThat(status(pm, HttpMethod.GET, "/api/v1/buildings/" + palmTower)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(call(pm, HttpMethod.GET, "/api/v1/buildings", null))).isEmpty();
        assertThat(body(call(pm, HttpMethod.GET, "/api/v1/buildings/property/" + palm.getId(), null))).isEmpty();
        assertThat(body(call(admin, HttpMethod.GET, "/api/v1/buildings/property/" + palm.getId(), null))).isNotEmpty();
        assertThat(status(admin, HttpMethod.GET, "/api/v1/buildings/" + palmTower)).isEqualTo(HttpStatus.OK);

        assertThat(status(pm, HttpMethod.GET, "/api/v1/properties/" + palm.getId())).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(pm, HttpMethod.GET, "/api/v1/properties/" + marina.getId())).isEqualTo(HttpStatus.OK);

        Map<String, Object> contact = Map.of("category", "PLUMBER", "name", "Front desk", "phone", "+97140000000");
        assertThat(call(pm, HttpMethod.POST, "/api/v1/properties/" + palm.getId() + "/contacts", contact)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Not a property of this tenant at all: no orphan contact pointing at nothing.
        assertThat(call(admin, HttpMethod.POST, "/api/v1/properties/" + UUID.randomUUID() + "/contacts", contact)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> palmContact = call(admin, HttpMethod.POST,
                "/api/v1/properties/" + palm.getId() + "/contacts", contact);
        assertThat(palmContact.getStatusCode()).isEqualTo(HttpStatus.OK);
        String contactId = body(palmContact).get("id").asText();
        assertThat(body(call(pm, HttpMethod.GET, "/api/v1/properties/" + palm.getId() + "/contacts", null))).isEmpty();
        assertThat(status(pm, HttpMethod.DELETE, "/api/v1/properties/" + palm.getId() + "/contacts/" + contactId))
                .isEqualTo(HttpStatus.NOT_FOUND);
        // The path's property binds the contact: Palm's contact is not Marina's to delete.
        assertThat(status(admin, HttpMethod.DELETE, "/api/v1/properties/" + marina.getId() + "/contacts/" + contactId))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(pm, HttpMethod.POST, "/api/v1/properties/" + marina.getId() + "/contacts", contact)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // -------------------------------------------------------------- plumbing

    private UUID draft(Unit unit) {
        CreateLeaseDTO dto = fixtures.draftDto(unit, fixtures.renter(), LocalDate.of(2026, 10, 2),
                LocalDate.of(2027, 10, 1), List.of(line("RENT", "51000")));
        dto.setContractDate(LocalDate.of(2026, 9, 18));
        dto.setFirstDueDate(LocalDate.of(2026, 10, 2));
        return leaseService.createDraftLease(dto).getId();
    }

    private void openOpportunity(UUID leaseId) {
        tx.executeWithoutResult(s -> {
            Lease lease = leaseRepo.findById(leaseId).orElseThrow();
            RenewalOpportunity o = new RenewalOpportunity();
            o.setTenantId(fixtures.tenantId());
            o.setLease(lease);
            opportunityRepo.save(o);
        });
    }

    private GatePass pendingPass(Property property, Unit unit) {
        GatePass pass = new GatePass();
        pass.setTenantId(fixtures.tenantId());
        pass.setPropertyId(property.getId());
        pass.setUnitId(unit.getId());
        pass.setCreatedByUserId(admin.getId());
        pass.setOrigin(GatePassOrigin.RENTER);
        pass.setGuestName("Guest " + property.getNameEn());
        pass.setGuestPhone("+971501234567");
        pass.setVisitorType(GateVisitorType.GUEST);
        pass.setPassType(GatePassType.SINGLE_USE);
        pass.setValidFrom(Instant.now());
        pass.setValidTo(Instant.now().plus(2, ChronoUnit.HOURS));
        pass.setStatus(GatePassStatus.PENDING_APPROVAL);
        pass.setQrToken(UUID.randomUUID().toString().replace("-", "") + "0123456789abcdef");
        pass.setNumericCode(String.format("%08d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000_000L)));
        return passRepo.save(pass);
    }

    private GatePass walkIn(Property property, Unit unit) {
        GatePass pass = pendingPass(property, unit);
        pass.setOrigin(GatePassOrigin.GUARD_WALK_IN);
        pass.setGuestPhotoBlobPath("gate/walk-ins/" + UUID.randomUUID() + ".jpg");
        return passRepo.save(pass);
    }

    @Autowired com.datagami.rentaxis.domain.repository.GatePassScanRepository scanRepo;

    private void scan(GatePass pass) {
        com.datagami.rentaxis.domain.entity.GatePassScan scan = new com.datagami.rentaxis.domain.entity.GatePassScan();
        scan.setTenantId(fixtures.tenantId());
        scan.setGatePassId(pass.getId());
        scan.setScannedByUserId(admin.getId());
        scan.setDirection(com.datagami.rentaxis.domain.entity.enums.ScanDirection.ENTRY);
        scan.setResult(com.datagami.rentaxis.domain.entity.enums.ScanResult.ALLOWED);
        scanRepo.save(scan);
    }

    private String meeting(Property property) {
        ZonedDateTime slot = ZonedDateTime.now(ZoneId.of("Asia/Dubai")).plusDays(3)
                .withHour(10).withMinute(0).withSecond(0).withNano(0)
                .plusMinutes(30L * (property == palm ? 1 : 2));
        ResponseEntity<String> res = call(admin, HttpMethod.POST, "/api/v1/meetings", Map.of(
                "type", "OFFICE_VISIT", "purpose", "OTHER", "slotStart", slot.toInstant().toString(),
                "hostUserId", admin.getId().toString(), "propertyId", property.getId().toString()));
        assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(HttpStatus.CREATED);
        return body(res).get("id").asText();
    }

    private String ticket(Property property) {
        ResponseEntity<String> res = call(admin, HttpMethod.POST, "/api/v1/tickets",
                Map.of("propertyId", property.getId().toString(), "title", "Leak at " + property.getNameEn()));
        assertThat(res.getStatusCode().is2xxSuccessful()).as(res.getBody()).isTrue();
        return body(res).get("id").asText();
    }

    private User user(UserRole role) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(fixtures.tenantId());
        if (role == UserRole.SECURITY_GUARD) {
            u.setPhoneNumber("+9715" + String.format("%08d", Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 100_000_000L)));
        }
        return userRepo.save(u);
    }

    private RestClient.RequestBodySpec request(User caller, HttpMethod method, String path) {
        return RestClient.builder().baseUrl("http://localhost:" + port).build()
                .method(method).uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", fixtures.tenantId().toString())
                .header("X-User-Tenant-Id", fixtures.tenantId().toString());
    }

    private ResponseEntity<String> call(User caller, HttpMethod method, String path, Object body) {
        RestClient.RequestBodySpec spec = request(caller, method, path);
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    private HttpStatus status(User caller, HttpMethod method, String path) {
        return HttpStatus.valueOf(call(caller, method, path, null).getStatusCode().value());
    }

    private ResponseEntity<String> upload(User caller, String path) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource("%PDF-1.4 test".getBytes()) {
            @Override public String getFilename() { return "contract.pdf"; }
        });
        form.add("name", "Contract");
        return request(caller, HttpMethod.POST, path).contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    private JsonNode body(ResponseEntity<String> res) {
        try {
            return json.readTree(res.getBody());
        } catch (Exception e) {
            throw new AssertionError("Not JSON (" + res.getStatusCode() + "): " + res.getBody(), e);
        }
    }
}
