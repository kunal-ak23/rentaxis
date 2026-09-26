package com.datagami.rentaxis.core.service.report;

import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.MaintenanceTicketService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.UnitService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.service.report.PnlPeriods.Compare;
import com.datagami.rentaxis.core.service.report.statement.PropertyStatementService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.entity.MaintenanceTicket;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.MaintenanceTicketRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * S16-02: towers are the Building entity. The units, leases and tickets lists filter
 * by building, and a property's P&L breaks down per building — derived from the unit
 * each journal line carries — with the property-level lines in a column of their
 * own, the whole tying to the property's P&L. The property statement carries the
 * per-building NOI.
 */
@SpringBootTest
class BuildingDimensionIT extends AbstractPostgresIT {

    @Autowired PropertyPnlService pnl;
    @Autowired PropertyStatementService statements;
    @Autowired UnitService unitService;
    @Autowired LeaseService leaseService;
    @Autowired MaintenanceTicketService tickets;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeasePostingService posting;
    @Autowired PostingService postingService;
    @Autowired RecognitionService recognition;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired BuildingRepository buildings;
    @Autowired MaintenanceTicketRepository ticketRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired TransactionTemplate tx;

    private LeaseTestFixtures fixtures;
    private Building towerA, towerB;
    private Unit a1, b1;
    private UUID leaseA, leaseB;

    private static final LocalDate CONTRACT = LocalDate.of(2026, 1, 5);
    private static final LocalDate START = LocalDate.of(2026, 1, 10);
    private static final LocalDate END = LocalDate.of(2027, 1, 9);
    private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate TO = LocalDate.of(2026, 3, 31);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, posting);
        tx.executeWithoutResult(s -> {
            towerA = building("Tower A");
            towerB = building("Tower B");
            a1 = fixtures.unit();
            a1.setBuilding(towerA);
            a1 = unitRepo.save(a1);
            b1 = fixtures.createUnit(fixtures.property(), "B-101");
            b1.setBuilding(towerB);
            b1 = unitRepo.save(b1);
            fixtures.createUnit(fixtures.property(), "V-01");   // a villa in no tower
        });
        leaseA = fixtures.postedLease(a1, fixtures.renter(), CONTRACT, START, END,
                List.of(line("RENT", "36500")), 4, null).lease().getId();
        leaseB = fixtures.postedLease(b1, fixtures.createRenter("Tower B Renter"), CONTRACT, START, END,
                List.of(line("RENT", "73000")), 4, null).lease().getId();
        recognition.runTo(TO, false);
        // A property-level cost: no unit, so no tower.
        postingService.post(new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 3, 15), "Lobby repairs",
                PostingRequest.Dimensions.ofProperty(fixtures.property().getId()), JournalSourceType.MANUAL, null, null,
                List.of(PostingRequest.dr(AccountRole.BANK_CHARGES, new BigDecimal("400")),
                        PostingRequest.cr(AccountRole.CASH, new BigDecimal("400")))));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private Building building(String name) {
        Building b = new Building();
        b.setProperty(fixtures.property());
        b.setNameEn(name);
        return buildings.save(b);
    }

    @Test
    void thePropertyPnlBreaksDownPerBuildingAndTiesToTheProperty() {
        PropertyPnlDTO byBuilding = pnl.buildingPnl(fixtures.property().getId(), FROM, TO, Compare.NONE);
        String a = towerA.getId().toString(), b = towerB.getId().toString();
        assertThat(byBuilding.columns()).extracting(PropertyPnlDTO.Column::key)
                .containsExactly(a, b, PropertyPnlService.NO_BUILDING, PropertyPnlDTO.TOTAL);
        // March: 31 days at 100 / 200 a day.
        assertThat(byBuilding.income().get(a).amount()).isEqualByComparingTo("3100.00");
        assertThat(byBuilding.income().get(b).amount()).isEqualByComparingTo("6200.00");
        assertThat(byBuilding.expenses().get(PropertyPnlService.NO_BUILDING).amount()).isEqualByComparingTo("400.00");
        assertThat(byBuilding.noi().get(PropertyPnlDTO.TOTAL).amount()).isEqualByComparingTo("8900.00");
        assertThat(byBuilding.check().ok()).isTrue();

        String p = fixtures.property().getId().toString();
        PropertyPnlDTO property = pnl.pnl(FROM, TO, List.of(fixtures.property().getId()), Compare.NONE,
                PnlAllocation.Basis.NONE);
        assertThat(property.noi().get(p).amount()).isEqualByComparingTo(byBuilding.noi().get(PropertyPnlDTO.TOTAL).amount());

        PropertyStatementDTO st = statements.statement(fixtures.property().getId(), FROM, TO, "test");
        PropertyStatementDTO.Table table = st.sections().get(0).tables().stream()
                .filter(t -> t.key().equals("buildings")).findFirst().orElseThrow();
        assertThat(table.rows()).hasSize(3);
        assertThat(table.rows().get(0).get(0).toString()).startsWith("Tower A");
        assertThat((BigDecimal) table.rows().get(0).get(2)).isEqualByComparingTo("3100.00");
    }

    @Test
    void unitsLeasesAndTicketsFilterByBuilding() {
        UUID prop = fixtures.property().getId();
        assertThat(tx.execute(s -> unitService.searchPaged(null, prop, towerA.getId(), null, null, 0, 50)).getContent())
                .extracting(Unit::getId).containsExactly(a1.getId());
        assertThat(tx.execute(s -> unitService.searchPaged(null, prop, null, null, null, 0, 50)).getTotalElements())
                .isEqualTo(3);
        assertThat(leaseService.getAllLeasesPaged(null, null, prop, towerB.getId(), PageRequest.of(0, 50)).getContent())
                .extracting(l -> l.getId()).containsExactly(leaseB);

        UUID reporter = fixtures.tenantId();
        tx.executeWithoutResult(s -> {
            for (Unit u : List.of(a1, b1)) {
                MaintenanceTicket t = new MaintenanceTicket();
                t.setProperty(fixtures.property());
                t.setUnit(unitRepo.findById(u.getId()).orElseThrow());
                t.setReportedBy(reporter);
                t.setTitle("Leak in " + u.getUnitNumber());
                ticketRepo.save(t);
            }
        });
        assertThat(tickets.searchPaged(null, null, prop, towerB.getId(), null, null, null, null, 0, 50).getContent())
                .extracting(t -> t.getTitle()).containsExactly("Leak in B-101");
        assertThat(tickets.searchPaged(null, null, prop, null, null, null, null, null, 0, 50).getTotalElements())
                .isEqualTo(2);
    }

    @Autowired com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository assignmentRepo;

    /**
     * PR #370 R1 P3-3: a property manager of property 1 cannot read another property's
     * tower — the per-building P&L is a 404 and the buildingId filters on units, leases
     * (both the query and the restricted in-memory path) and tickets come back empty —
     * while their own tower still reads.
     */
    @Test
    void aPropertyManagerReachesOnlyTheirPropertiesBuildings() {
        // A second property with its own tower + unit + lease.
        com.datagami.rentaxis.domain.entity.Property p2 = fixtures.createProperty("P2X");
        Building[] towerC = new Building[1];
        Unit[] c1 = new Unit[1];
        tx.executeWithoutResult(s -> {
            Building b = new Building(); b.setProperty(p2); b.setNameEn("Tower C"); towerC[0] = buildings.save(b);
            Unit u = fixtures.createUnit(p2, "C-101"); u.setBuilding(towerC[0]); c1[0] = unitRepo.save(u);
        });
        UUID leaseC = fixtures.postedLease(c1[0], fixtures.createRenter("C renter"), CONTRACT, START, END,
                List.of(line("RENT", "36500")), 4, null).lease().getId();
        recognition.runTo(TO, false);
        tx.executeWithoutResult(s -> {
            MaintenanceTicket t = new MaintenanceTicket();
            t.setProperty(p2); t.setUnit(unitRepo.findById(c1[0].getId()).orElseThrow());
            t.setReportedBy(fixtures.tenantId()); t.setTitle("Leak C"); ticketRepo.save(t);
        });
        // Sanity as admin: tower C has data.
        assertThat(leaseService.getAllLeasesPaged(null, null, null, towerC[0].getId(), PageRequest.of(0, 50)).getContent())
                .extracting(l -> l.getId()).containsExactly(leaseC);

        // PM of property 1 only.
        com.datagami.rentaxis.domain.entity.User pm = new com.datagami.rentaxis.domain.entity.User();
        pm.setEmail("pm-" + UUID.randomUUID() + "@t.io"); pm.setName("PM");
        pm.setRole(com.datagami.rentaxis.domain.entity.enums.UserRole.PROPERTY_MANAGER);
        pm.setStatus(com.datagami.rentaxis.domain.entity.enums.UserStatus.ACTIVE);
        pm.setPasswordHash("x"); pm.setTenantId(fixtures.tenantId());
        UUID pmId = userRepo.save(pm).getId();
        com.datagami.rentaxis.domain.entity.UserPropertyAssignment a = new com.datagami.rentaxis.domain.entity.UserPropertyAssignment();
        a.setUserId(pmId); a.setPropertyId(fixtures.property().getId()); assignmentRepo.save(a);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(pmId.toString(), null,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_PROPERTY_MANAGER"))));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> pnl.buildingPnl(p2.getId(), FROM, TO, Compare.NONE))
                .isInstanceOf(com.datagami.rentaxis.api.exception.NotFoundException.class);
        assertThat(pnl.buildingPnl(fixtures.property().getId(), FROM, TO, Compare.NONE).check().ok()).isTrue();
        assertThat(tx.execute(s -> unitService.searchPaged(null, null, towerC[0].getId(), null, null, 0, 50)).getContent()).isEmpty();
        assertThat(leaseService.getAllLeasesPaged(null, null, null, towerC[0].getId(), PageRequest.of(0, 50)).getContent()).isEmpty();
        assertThat(leaseService.getAllLeasesPaged("C", null, null, towerC[0].getId(), PageRequest.of(0, 50)).getContent()).isEmpty();
        assertThat(tickets.searchPaged(pmId, null, null, towerC[0].getId(), null, null, null, null, 0, 50).getContent()).isEmpty();
        assertThat(tx.execute(s -> unitService.searchPaged(null, null, towerA.getId(), null, null, 0, 50)).getContent()).hasSize(1);
    }
}
