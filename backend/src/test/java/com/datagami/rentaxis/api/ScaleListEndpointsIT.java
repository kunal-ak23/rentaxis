package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.MaintenanceTicket;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.Vendor;
import com.datagami.rentaxis.domain.entity.enums.TicketCategory;
import com.datagami.rentaxis.domain.entity.enums.TicketPriority;
import com.datagami.rentaxis.domain.entity.enums.TicketStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.MaintenanceTicketRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scale PR A (P1-3 / P1-6 / #14): the paged list and lookup endpoints, over HTTP with the
 * headers the Next.js proxy sends. For each: pagination, its filters, a mixed-case search
 * term (the term is lowercased server-side — "SEMI" finds "R14 Semi Salem"), tenant
 * isolation (another organisation's matching rows never appear) and a property manager's
 * scope (Marina only; Palm is out).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScaleListEndpointsIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService generation;
    @Autowired LeasePostingService posting;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;
    @Autowired MaintenanceTicketRepository ticketRepo;
    @Autowired VendorService vendorService;
    @Autowired com.datagami.rentaxis.domain.repository.ChequeRepository chequeRepo;

    private final ObjectMapper json = new ObjectMapper();

    private LeaseTestFixtures a;
    private User admin;
    private User pm;
    private Property marina;
    private Property palm;
    private Renter semi;
    private Renter palmRenter;
    private Renter fresh;
    private Renter otherTenantsSemi;
    private Unit u0701;
    private Unit palmUnit;
    private Unit otherTenants0709;
    private UUID postedLease;
    private UUID draftLease;

    @BeforeEach
    void setUp() {
        // Organisation B first: a renter, a unit, a ticket and a vendor that match every search below.
        LeaseTestFixtures b = fixtures().bootstrap();
        otherTenantsSemi = b.createRenter("Semi Other Org");
        otherTenants0709 = b.createUnit(b.property(), "07-09");
        ticket(b.property(), otherTenants0709, "Leak in the other org", TicketStatus.OPEN, UUID.randomUUID());
        Vendor bv = new Vendor();
        bv.setNameEn("Emrill Other Org");
        vendorService.createVendor(bv);

        // Organisation A.
        a = fixtures().bootstrap().withLeaseServices(leaseService, generation, posting);
        marina = a.property();
        palm = a.createProperty("PALM");
        u0701 = a.createUnit(marina, "07-01");
        Unit u0702 = a.createUnit(marina, "07-02");
        a.createUnit(marina, "08-01");
        palmUnit = a.createUnit(palm, "901");
        semi = a.createRenter("R14 Semi Salem");
        palmRenter = a.createRenter("Palm Person");
        fresh = a.createRenter("Fresh Renter");
        postedLease = a.postedLease(u0701, semi, LocalDate.now().minusDays(10), LocalDate.now().plusMonths(1).withDayOfMonth(1),
                LocalDate.now().plusMonths(1).withDayOfMonth(1).plusYears(1).minusDays(1),
                List.of(line("RENT", "48000")), 4, "SW8kesf2").lease().getId();
        draftLease = a.draftLease(u0702, semi, LocalDate.now(), LocalDate.now().plusMonths(2),
                LocalDate.now().plusMonths(2).plusYears(1).minusDays(1), List.of(line("RENT", "36000")));
        a.draftLease(palmUnit, palmRenter, LocalDate.now(), LocalDate.now().plusMonths(2),
                LocalDate.now().plusMonths(2).plusYears(1).minusDays(1), List.of(line("RENT", "30000")));

        admin = user(UserRole.TENANT_ADMIN);
        pm = user(UserRole.PROPERTY_MANAGER);
        UserPropertyAssignment asg = new UserPropertyAssignment();
        asg.setUserId(pm.getId());
        asg.setPropertyId(marina.getId());
        assignmentRepo.save(asg);

        ticket(marina, u0701, "Water LEAK under the sink", TicketStatus.OPEN, admin.getId());
        ticket(marina, null, "Lift noise", TicketStatus.RESOLVED, admin.getId());
        ticket(palm, palmUnit, "Leaking tap", TicketStatus.OPEN, admin.getId());
        Vendor av = new Vendor();
        av.setNameEn("Emrill Services LLC");
        vendorService.createVendor(av);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ------------------------------------------------------------------ renters

    @Test
    void rentersPagedSearchesInAnyCasePagesAndStaysInTheOrganisation() {
        JsonNode page = get(admin, "/api/v1/renters/paged?q=SEMI&page=0&size=10");
        assertThat(names(page, "nameEn")).containsExactly("R14 Semi Salem");

        JsonNode all = get(admin, "/api/v1/renters/paged?page=0&size=2");
        assertThat(all.get("content")).hasSize(2);
        assertThat(total(all)).isEqualTo(4);   // fixture renter + three of ours; never B's
        assertThat(names(get(admin, "/api/v1/renters/paged?page=1&size=2"), "nameEn")).hasSize(2);
        assertThat(names(get(admin, "/api/v1/renters/paged?q=OTHER"), "nameEn")).isEmpty();
    }

    @Test
    void rentersPagedOrdersByCreatedAtThenNameThenId() {
        // Controller ruling (Scale PR B2 final-review fix, task 5, revised): every entity
        // list sorts by createdAt ascending, with nameEn as the tiebreak (not a raw id/UUID
        // tiebreak — every renter migrated by changeset 155 ties on the same backfilled
        // created_at, and a UUID tiebreak would show them in effectively random order).
        //
        // Renters created with distinct timestamps sort oldest first: fixture creation order
        // for org A is the bootstrap's own "Test Renter", then Semi/Palm/Fresh (setUp, in
        // that order) — alphabetically that would be Fresh/Palm/Test/R14, so this also proves
        // the list isn't secretly still sorting by nameEn alone.
        assertThat(names(get(admin, "/api/v1/renters/paged?page=0&size=10"), "nameEn"))
                .containsExactly("Test Renter", "R14 Semi Salem", "Palm Person", "Fresh Renter");

        // Two renters sharing the same createdAt (as every renter backfilled by the same
        // migration run does) tie-break on nameEn — "Amy Tied" before "Zed Tied" — not on
        // their random UUID id.
        Renter zed = a.createRenter("Zed Tied");
        Renter amy = a.createRenter("Amy Tied");
        Instant tiedAt = Instant.now();
        zed.setCreatedAt(tiedAt);
        amy.setCreatedAt(tiedAt);
        renterRepo.save(zed);
        renterRepo.save(amy);

        assertThat(names(get(admin, "/api/v1/renters/paged?page=0&size=10"), "nameEn"))
                .containsExactly("Test Renter", "R14 Semi Salem", "Palm Person", "Fresh Renter",
                        "Amy Tied", "Zed Tied");
    }

    @Test
    void aManagerSeesRentersOfTheirBuildingsAndRentersWithNoContract() {
        List<String> seen = names(get(pm, "/api/v1/renters/paged?size=50"), "nameEn");
        assertThat(seen).contains("R14 Semi Salem", "Fresh Renter").doesNotContain("Palm Person");
        assertThat(names(get(pm, "/api/v1/renters/paged?q=PALM"), "nameEn")).isEmpty();
    }

    @Test
    void renterSearchAndNamesAreScopedAndCapped() {
        assertThat(names(get(admin, "/api/v1/renters/search?q=sEmI&limit=5"), null, "nameEn"))
                .containsExactly("R14 Semi Salem");
        String ids = semi.getId() + "," + palmRenter.getId() + "," + otherTenantsSemi.getId();
        assertThat(names(get(admin, "/api/v1/renters/names?ids=" + ids), null, "nameEn"))
                .containsExactlyInAnyOrder("R14 Semi Salem", "Palm Person");
        assertThat(names(get(pm, "/api/v1/renters/names?ids=" + ids), null, "nameEn"))
                .containsExactly("R14 Semi Salem");

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 201; i++) tooMany.add(UUID.randomUUID().toString());
        assertThat(call(admin, "/api/v1/renters/names?ids=" + String.join(",", tooMany)).getStatusCode().is4xxClientError())
                .isTrue();
    }

    // ------------------------------------------------------------------ units

    @Test
    void unitsPagedFiltersByFloorStatusAndPropertyAndSearchesInAnyCase() {
        assertThat(names(get(admin, "/api/v1/units/paged?q=07-&size=10"), "unitNumber"))
                .containsExactly("07-01", "07-02");
        assertThat(names(get(admin, "/api/v1/units/paged?floor=07&size=10"), "unitNumber"))
                .containsExactly("07-01", "07-02");
        assertThat(names(get(admin, "/api/v1/units/paged?propertyId=" + palm.getId()), "unitNumber"))
                .containsExactly("901");
        assertThat(names(get(admin, "/api/v1/units/paged?q=MARINA&size=50"), "unitNumber")).contains("07-01", "08-01");
        assertThat(total(get(admin, "/api/v1/units/paged?status=" + UnitStatus.MAINTENANCE))).isZero();

        JsonNode first = get(admin, "/api/v1/units/paged?size=2");
        assertThat(first.get("content")).hasSize(2);
        assertThat(total(first)).isEqualTo(5);  // 101, 07-01, 07-02, 08-01, 901 — not B's 07-09
        assertThat(first.get("content").get(0).get("property").get("nameEn").asText()).isNotBlank();
    }

    @Test
    void aManagersUnitListStopsAtTheirBuildings() {
        assertThat(names(get(pm, "/api/v1/units/paged?size=50"), "unitNumber")).doesNotContain("901").contains("07-01");
        assertThat(total(get(pm, "/api/v1/units/paged?propertyId=" + palm.getId()))).isZero();
        // Property names end in 8 random hex characters, so "90" can match the manager's own buildings
        // by name; what matters is that Palm's 901 never shows.
        assertThat(names(get(pm, "/api/v1/units/search?q=90"), null, "unitNumber")).doesNotContain("901");
        String ids = u0701.getId() + "," + palmUnit.getId() + "," + otherTenants0709.getId();
        assertThat(names(get(pm, "/api/v1/units/names?ids=" + ids), null, "unitNumber")).containsExactly("07-01");
        assertThat(names(get(admin, "/api/v1/units/names?ids=" + ids), null, "unitNumber"))
                .containsExactlyInAnyOrder("07-01", "901");
        assertThat(names(get(admin, "/api/v1/units/search?q=07&limit=1"), null, "unitNumber")).hasSize(1);
    }

    @Test
    void unitSearchFiltersByStatusAndCarriesThePropertyType() {
        Unit vacant = a.createUnit(marina, "50-01");
        Unit occupied = a.createUnit(marina, "50-02");
        occupied.setStatus(UnitStatus.OCCUPIED);
        unitRepo.save(occupied);

        assertThat(names(get(admin, "/api/v1/units/search?q=50-&status=" + UnitStatus.VACANT), null, "unitNumber"))
                .containsExactly("50-01");
        assertThat(names(get(admin, "/api/v1/units/search?q=50-"), null, "unitNumber"))
                .containsExactlyInAnyOrder("50-01", "50-02");

        JsonNode rows = get(admin, "/api/v1/units/search?q=50-&status=" + UnitStatus.VACANT);
        assertThat(rows.get(0).get("propertyType").asText()).isEqualTo(marina.getType().name());

        assertThat(names(get(pm, "/api/v1/units/search?q=90&status=" + UnitStatus.VACANT), null, "unitNumber"))
                .doesNotContain("901");
    }

    // ------------------------------------------------------------------ tickets

    @Test
    void ticketsPagedFiltersSearchesAndScopes() {
        assertThat(names(get(admin, "/api/v1/tickets/paged?q=leak&size=10"), "title"))
                .containsExactlyInAnyOrder("Water LEAK under the sink", "Leaking tap");
        assertThat(names(get(admin, "/api/v1/tickets/paged?status=RESOLVED"), "title")).containsExactly("Lift noise");
        assertThat(names(get(admin, "/api/v1/tickets/paged?q=07-01"), "title")).containsExactly("Water LEAK under the sink");
        assertThat(total(get(admin, "/api/v1/tickets/paged?from=" + LocalDate.now().plusDays(1)))).isZero();
        assertThat(total(get(admin, "/api/v1/tickets/paged?priority=MEDIUM&size=1"))).isEqualTo(3);
        JsonNode one = get(admin, "/api/v1/tickets/paged?q=SINK");
        assertThat(one.get("content").get(0).get("reporterName").asText()).isEqualTo("TENANT_ADMIN");

        assertThat(names(get(pm, "/api/v1/tickets/paged?q=leak"), "title")).containsExactly("Water LEAK under the sink");
        assertThat(total(get(pm, "/api/v1/tickets/paged?propertyId=" + palm.getId()))).isZero();
    }

    // ------------------------------------------------------------------ finance lists

    @Test
    void vendorsPaymentRunsIssuedChequesAndPostDatedArePaged() {
        assertThat(names(get(admin, "/api/v1/vendors/paged?q=EMRILL"), "nameEn")).containsExactly("Emrill Services LLC");
        assertThat(total(get(admin, "/api/v1/finance/payment-runs/paged?status=POSTED"))).isZero();
        assertThat(total(get(admin, "/api/v1/finance/issued-cheques/paged?duePresent=true"))).isZero();

        String month = LocalDate.now().plusMonths(1).toString().substring(0, 7);
        JsonNode pd = get(admin, "/api/v1/cheques/post-dated/paged?month=" + month + "&size=1");
        assertThat(total(pd)).isEqualTo(1);
        JsonNode year = get(admin, "/api/v1/cheques/post-dated/paged?from=" + LocalDate.now() + "&to="
                + LocalDate.now().plusYears(2) + "&size=2");
        assertThat(total(year)).isEqualTo(4);
        assertThat(year.get("content")).hasSize(2);
        assertThat(total(get(pm, "/api/v1/cheques/post-dated/paged?propertyId=" + palm.getId()))).isZero();
    }

    /**
     * Review P2-1: the post-dated book's scope, pinned with cheques it must leave out — four on
     * Palm (a property the manager is not assigned) and four in another organisation, all in the
     * window. The admin sees Marina + Palm and never the other organisation; the manager sees
     * Marina only, on the page, in its total and in every per-day subtotal.
     */
    @Test
    void thePostDatedPageLeavesOutOtherPropertiesAndOrganisations() {
        OutsideCheques outside = seedOutsideCheques();
        String window = "from=" + LocalDate.now() + "&to=" + LocalDate.now().plusYears(1).minusDays(1);

        JsonNode adminPage = get(admin, "/api/v1/cheques/post-dated/paged?" + window + "&size=50");
        assertThat(total(adminPage)).isEqualTo(8);
        assertThat(ids(adminPage)).containsAll(outside.palm()).doesNotContainAnyElementsOf(outside.otherOrg());

        JsonNode pmPage = get(pm, "/api/v1/cheques/post-dated/paged?" + window + "&size=50");
        assertThat(total(pmPage)).isEqualTo(4);
        assertThat(ids(pmPage)).doesNotContainAnyElementsOf(outside.palm()).doesNotContainAnyElementsOf(outside.otherOrg());
        for (JsonNode row : pmPage.get("content")) {
            assertThat(row.get("propertyId").asText()).isEqualTo(marina.getId().toString());
        }
        assertThat(total(get(pm, "/api/v1/cheques/post-dated/paged?" + window + "&propertyId=" + palm.getId()))).isZero();
    }

    @Test
    void thePostDatedTotalsLeaveOutOtherPropertiesAndOrganisations() {
        seedOutsideCheques();
        String window = "from=" + LocalDate.now() + "&to=" + LocalDate.now().plusYears(1).minusDays(1);

        JsonNode adminTotals = get(admin, "/api/v1/cheques/post-dated/totals?" + window);
        assertThat(adminTotals.get("count").asLong()).isEqualTo(8);
        assertThat(adminTotals.get("total").decimalValue()).isEqualByComparingTo("72000");   // 48,000 Marina + 24,000 Palm
        assertThat(sumOfDays(adminTotals)).isEqualByComparingTo("72000");

        JsonNode pmTotals = get(pm, "/api/v1/cheques/post-dated/totals?" + window);
        assertThat(pmTotals.get("count").asLong()).isEqualTo(4);
        assertThat(pmTotals.get("total").decimalValue()).isEqualByComparingTo("48000");
        assertThat(sumOfDays(pmTotals)).isEqualByComparingTo("48000");

        // amountBefore, page by page, is the sum of the rows ahead in the caller's own book.
        for (User caller : List.of(admin, pm)) {
            java.math.BigDecimal running = java.math.BigDecimal.ZERO;
            for (int page = 0; page < 4; page++) {
                JsonNode t = get(caller, "/api/v1/cheques/post-dated/totals?" + window + "&offset=" + (page * 2));
                assertThat(t.get("amountBefore").decimalValue()).as("%s page %d", caller.getRole(), page)
                        .isEqualByComparingTo(running);
                for (JsonNode row : get(caller, "/api/v1/cheques/post-dated/paged?" + window + "&page=" + page + "&size=2")
                        .get("content")) {
                    running = running.add(row.get("amount").decimalValue());
                }
            }
        }
        assertThat(get(pm, "/api/v1/cheques/post-dated/totals?" + window + "&offset=99").get("amountBefore")
                .decimalValue()).isEqualByComparingTo("48000");
        assertThat(get(admin, "/api/v1/cheques/post-dated/totals?" + window + "&offset=99").get("amountBefore")
                .decimalValue()).isEqualByComparingTo("72000");
    }

    private record OutsideCheques(List<String> palm, List<String> otherOrg) {
    }

    /** Four post-dated cheques on Palm (organisation A) and four in a third organisation, same dates as Marina's. */
    private OutsideCheques seedOutsideCheques() {
        LocalDate start = LocalDate.now().plusMonths(1).withDayOfMonth(1);
        LocalDate end = start.plusYears(1).minusDays(1);
        TenantContextHolder.setTenantId(a.tenantId());
        LeaseTestFixtures.authenticateAsTenantAdmin();
        UUID palmLease = a.postedLease(a.createUnit(palm, "902"), a.createRenter("Palm PDC Payer"), LocalDate.now().minusDays(10),
                start, end, List.of(line("RENT", "24000")), 4, LeaseTestFixtures.nextChequeNumber()).lease().getId();
        List<String> palmCheques = chequeIdsOf(palmLease);

        LeaseTestFixtures c = fixtures().bootstrap().withLeaseServices(leaseService, generation, posting);
        UUID otherLease = c.postedLease(c.unit(), c.renter(), LocalDate.now().minusDays(10), start, end,
                List.of(line("RENT", "36000")), 4, LeaseTestFixtures.nextChequeNumber()).lease().getId();
        List<String> otherCheques = chequeIdsOf(otherLease);

        TenantContextHolder.setTenantId(a.tenantId());
        LeaseTestFixtures.authenticateAsTenantAdmin();
        assertThat(palmCheques).hasSize(4);
        assertThat(otherCheques).hasSize(4);
        return new OutsideCheques(palmCheques, otherCheques);
    }

    private List<String> chequeIdsOf(UUID leaseId) {
        return chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId).stream().map(ch -> ch.getId().toString()).toList();
    }

    private static List<String> ids(JsonNode page) {
        List<String> out = new java.util.ArrayList<>();
        page.get("content").forEach(r -> out.add(r.get("id").asText()));
        return out;
    }

    private static java.math.BigDecimal sumOfDays(JsonNode totals) {
        java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
        for (JsonNode d : totals.get("days")) sum = sum.add(d.get("amount").decimalValue());
        return sum;
    }

    /**
     * Scale follow-up: the paged post-dated screen reads the window's totals, each day's full
     * subtotal and the running total ahead of its page from {@code /post-dated/totals}; they add
     * up to what the rows say, page by page, and a manager outside the building gets nothing.
     */
    @Test
    void postDatedTotalsAddUpWhatThePagesHold() {
        String window = "from=" + LocalDate.now() + "&to=" + LocalDate.now().plusYears(1).minusDays(1);
        JsonNode totals = get(admin, "/api/v1/cheques/post-dated/totals?" + window);
        assertThat(totals.get("count").asLong()).isEqualTo(4);
        assertThat(totals.get("total").decimalValue()).isEqualByComparingTo("48000");
        assertThat(totals.get("days")).hasSize(4);
        assertThat(totals.get("amountBefore").decimalValue()).isEqualByComparingTo("0");

        java.math.BigDecimal running = java.math.BigDecimal.ZERO;
        for (int page = 0; page < 2; page++) {
            JsonNode before = get(admin, "/api/v1/cheques/post-dated/totals?" + window + "&offset=" + (page * 2));
            assertThat(before.get("amountBefore").decimalValue()).isEqualByComparingTo(running);
            for (JsonNode row : get(admin, "/api/v1/cheques/post-dated/paged?" + window + "&page=" + page + "&size=2")
                    .get("content")) {
                running = running.add(row.get("amount").decimalValue());
            }
        }
        assertThat(running).isEqualByComparingTo("48000");
        assertThat(get(admin, "/api/v1/cheques/post-dated/totals?" + window + "&offset=99").get("amountBefore")
                .decimalValue()).isEqualByComparingTo("48000");

        assertThat(get(pm, "/api/v1/cheques/post-dated/totals?" + window).get("count").asLong()).isEqualTo(4);
        JsonNode outside = get(pm, "/api/v1/cheques/post-dated/totals?" + window + "&propertyId=" + palm.getId());
        assertThat(outside.get("count").asLong()).isZero();
        assertThat(outside.get("days")).isEmpty();
    }

    /** Scale follow-up: the vendors list pages by the house order and the pickers offer active suppliers only. */
    @Test
    void vendorsPagedFiltersByActiveAndOrdersOldestFirst() {
        Vendor dormant = new Vendor();
        dormant.setNameEn("Emrill Dormant");
        dormant.setActive(false);
        vendorService.createVendor(dormant);
        Vendor late = new Vendor();
        late.setNameEn("Aardvark Emrill Late");
        vendorService.createVendor(late);

        assertThat(names(get(admin, "/api/v1/vendors/paged?q=emrill"), "nameEn"))
                .containsExactly("Aardvark Emrill Late", "Emrill Dormant", "Emrill Services LLC");
        assertThat(names(get(admin, "/api/v1/vendors/paged?q=emrill&order=oldest"), "nameEn"))
                .containsExactly("Emrill Services LLC", "Emrill Dormant", "Aardvark Emrill Late");
        assertThat(names(get(admin, "/api/v1/vendors/paged?q=emrill&active=true"), "nameEn"))
                .containsExactly("Aardvark Emrill Late", "Emrill Services LLC");
        assertThat(names(get(admin, "/api/v1/vendors/paged?q=emrill&active=false"), "nameEn"))
                .containsExactly("Emrill Dormant");
    }

    /** The register search the coordinator reported from production: any capital letter found nothing. */
    @Test
    void theChequeRegisterSearchIgnoresCase() {
        assertThat(total(get(admin, "/api/v1/cheques?search=Semi"))).isEqualTo(4);
        String number = get(admin, "/api/v1/cheques?search=semi&size=1").get("content").get(0).get("chequeNumber").asText();
        assertThat(number).matches(".*[A-Za-z].*");
        assertThat(total(get(admin, "/api/v1/cheques?search=" + number.toUpperCase()))).isEqualTo(1);
        assertThat(total(get(admin, "/api/v1/cheques?search=" + number.toLowerCase()))).isEqualTo(1);
    }

    @Test
    void leaseSearchIgnoresCase() {
        assertThat(total(get(admin, "/api/v1/leases/paged?search=sEMI"))).isEqualTo(2);
    }

    // ------------------------------------------------------------------ #14 stats

    @Test
    void statsByLeasesCarriesUnclearedAndLiveAmountsAndDraftsWhenAsked() {
        String body = "[\"" + postedLease + "\",\"" + draftLease + "\"]";
        JsonNode plain = post(admin, "/api/v1/cheques/stats-by-leases", body);
        assertThat(plain).hasSize(1);
        assertThat(plain.get(0).get("unclearedAmount").decimalValue()).isEqualByComparingTo("48000");
        assertThat(plain.get(0).get("liveAmount").decimalValue()).isEqualByComparingTo("48000");
        assertThat(plain.get(0).get("liveCount").asLong()).isEqualTo(4);
        assertThat(plain.get(0).get("liveClearedAmount").decimalValue()).isEqualByComparingTo("0");

        JsonNode withDrafts = post(admin, "/api/v1/cheques/stats-by-leases?includeDrafts=true", body);
        assertThat(withDrafts).hasSize(1);   // the draft's grid rows are DRAFT: still not instruments
        assertThat(withDrafts.get(0).get("leaseId").asText()).isEqualTo(postedLease.toString());
    }

    // ------------------------------------------------------------------ properties

    @Test
    void thePropertiesListCountsUnitsVacanciesAndRentInOneAggregate() {
        TenantContextHolder.setTenantId(a.tenantId());
        Unit nullRent = a.createUnit(palm, "902");
        nullRent.setExpectedRent(null);
        nullRent.setActualRent(null);
        unitRepo.save(nullRent);
        Unit rented = unitRepo.findById(palmUnit.getId()).orElseThrow();
        rented.setExpectedRent(new BigDecimal("5000"));
        unitRepo.save(rented);

        JsonNode list = get(admin, "/api/v1/properties");
        JsonNode p = null;
        for (JsonNode n : list) if (n.get("property").get("id").asText().equals(palm.getId().toString())) p = n;
        assertThat(p).isNotNull();
        assertThat(p.get("propertyCount").asLong()).isEqualTo(2);
        assertThat(p.get("revenueAtCapacity").decimalValue()).isEqualByComparingTo("5000");
        assertThat(get(pm, "/api/v1/properties")).hasSize(1);
    }

    // ------------------------------------------------------------------ super admin, no organisation

    /**
     * PR #366 review P2-3. A SUPER_ADMIN with no organisation selected: the new paged /
     * search / names endpoints refuse (400, "Select an organisation first") rather than
     * answer an empty page; the dashboard, register tiles and aging keep reading across
     * organisations, as they did before their overdue figures moved to SQL. (Batch 5
     * ruling: those three stay platform-wide for SUPER_ADMIN; see
     * {@code OrganisationRequiredInterceptor.SUPER_ADMIN_PLATFORM_READS}.)
     */
    @Test
    void aSuperAdminWithNoOrganisationIsToldToPickOneOnTheNewListsAndKeepsTheOldTotals() {
        User sa = new User();
        sa.setEmail("sa-" + UUID.randomUUID() + "@t.io");
        sa.setName("SA");
        sa.setRole(UserRole.SUPER_ADMIN);
        sa.setStatus(UserStatus.ACTIVE);
        sa.setPasswordHash("x");
        sa = userRepo.save(sa);
        for (String path : List.of("/api/v1/renters/paged", "/api/v1/renters/search?q=a", "/api/v1/units/paged",
                "/api/v1/units/names?ids=" + u0701.getId(), "/api/v1/tickets/paged", "/api/v1/vendors/paged")) {
            assertThat(noTenant(sa, path).getStatusCode().value()).as(path).isEqualTo(400);
        }
        ResponseEntity<String> dash = noTenant(sa, "/api/v1/dashboard/summary");
        assertThat(dash.getStatusCode().is2xxSuccessful()).as(dash.getBody()).isTrue();
        assertThat(noTenant(sa, "/api/v1/cheques/aging").getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode summary = read(noTenant(sa, "/api/v1/cheques/summary").getBody());
        assertThat(summary.get("registeredCount").asLong()).isGreaterThanOrEqualTo(4);
    }

    private ResponseEntity<String> noTenant(User caller, String path) {
        return RestClient.builder().baseUrl("http://localhost:" + port).build().method(HttpMethod.GET).uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    // ------------------------------------------------------------------ helpers

    private LeaseTestFixtures fixtures() {
        return new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService);
    }

    private void ticket(Property property, Unit unit, String title, TicketStatus status, UUID reportedBy) {
        MaintenanceTicket t = new MaintenanceTicket();
        t.setProperty(propertyRepo.findById(property.getId()).orElseThrow());
        if (unit != null) t.setUnit(unitRepo.findById(unit.getId()).orElseThrow());
        t.setReportedBy(reportedBy);
        t.setTitle(title);
        t.setCategory(TicketCategory.PLUMBING);
        t.setPriority(TicketPriority.MEDIUM);
        t.setStatus(status);
        ticketRepo.save(t);
    }

    private User user(UserRole role) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(a.tenantId());
        return userRepo.save(u);
    }

    private RestClient.RequestBodySpec request(User caller, HttpMethod method, String path) {
        return RestClient.builder().baseUrl("http://localhost:" + port).build()
                .method(method).uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", a.tenantId().toString())
                .header("X-User-Tenant-Id", a.tenantId().toString());
    }

    private ResponseEntity<String> call(User caller, String path) {
        return request(caller, HttpMethod.GET, path).retrieve().onStatus(s -> true, (req, res) -> { })
                .toEntity(String.class);
    }

    private JsonNode get(User caller, String path) {
        ResponseEntity<String> res = call(caller, path);
        assertThat(res.getStatusCode().is2xxSuccessful()).as("%s -> %s %s", path, res.getStatusCode(), res.getBody())
                .isTrue();
        return read(res.getBody());
    }

    private JsonNode post(User caller, String path, String body) {
        ResponseEntity<String> res = request(caller, HttpMethod.POST, path).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().onStatus(s -> true, (req, r) -> { }).toEntity(String.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).as("%s -> %s %s", path, res.getStatusCode(), res.getBody())
                .isTrue();
        return read(res.getBody());
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("Not JSON: " + body, e);
        }
    }

    /** A page's total, whichever shape the page serialises to. */
    private static long total(JsonNode page) {
        if (page.has("totalElements")) return page.get("totalElements").asLong();
        return page.get("page").get("totalElements").asLong();
    }

    private static List<String> names(JsonNode page, String field) {
        return names(page, "content", field);
    }

    private static List<String> names(JsonNode node, String container, String field) {
        JsonNode rows = container == null ? node : node.get(container);
        List<String> out = new ArrayList<>();
        rows.forEach(n -> out.add(n.get(field).asText()));
        return out;
    }
}
