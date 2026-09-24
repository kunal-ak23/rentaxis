package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.DashboardSummaryDTO;
import com.datagami.rentaxis.api.dto.lease.PostLeaseDryRunResponse;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.DashboardService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.UnitService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit occupancy by date (round 14): F14-01 (occupied = a posted lease covers
 * today; a later one reserves), F14-13 (the dry run reports the double-let the post
 * refuses) and F14-14 (a back-to-back lease is allowed at draft and post, an
 * overlapping one is not).
 */
@SpringBootTest
class LeaseUnitOccupancyIT extends AbstractPostgresIT {

    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService cheques;
    @Autowired LeaseService leaseService;
    @Autowired DashboardService dashboard;
    @Autowired UnitService unitService;
    @Autowired LeaseRepository leaseRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired TransactionTemplate tx;
    @Autowired LeaseRenewalService renewal;
    @Autowired LeaseTerminationService termination;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    private static final LocalDate TODAY = LocalDate.now();
    /** The sitting renter's lease covers today. */
    private static final LocalDate CUR_START = TODAY.minusDays(60);
    private static final LocalDate CUR_END = TODAY.plusDays(30);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, cheques, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private UUID posted(Unit unit, Renter renter, LocalDate start, LocalDate end) {
        return fixtures.postedLease(unit, renter, CUR_START, start, end, List.of(line("RENT", "36000")), 2, null)
                .lease().getId();
    }

    private LeaseStatus statusOf(UUID id) {
        return tx.execute(s -> leaseRepo.findById(id).orElseThrow().getStatus());
    }

    @Test
    void aBackToBackLeaseCanBeDraftedAndPosted() {
        Unit unit = fixtures.unit();
        posted(unit, fixtures.renter(), CUR_START, CUR_END);

        Renter next = fixtures.createRenter("Next Renter");
        UUID draft = fixtures.draftLease(unit, next, CUR_START, CUR_END.plusDays(1), CUR_END.plusYears(1),
                List.of(line("RENT", "40000")));
        fixtures.generateGrid(draft, 2, CUR_END.plusDays(1));

        PostLeaseDryRunResponse dry = posting.dryRun(draft);
        assertThat(dry.errors()).isEmpty();
        posting.post(draft);

        assertThat(statusOf(draft)).isEqualTo(LeaseStatus.ACTIVE);
        Unit reread = tx.execute(s -> unitRepo.findById(unit.getId()).orElseThrow());
        assertThat(reread.getCurrentTenantName()).as("the sitting renter keeps the unit until they leave")
                .isEqualTo(fixtures.renter().getNameEn());
    }

    @Test
    void anOverlappingLeaseIsRefusedAtDraft() {
        Unit unit = fixtures.unit();
        posted(unit, fixtures.renter(), CUR_START, CUR_END);

        Renter next = fixtures.createRenter("Overlap Renter");
        assertThatThrownBy(() -> fixtures.draftLease(unit, next, CUR_START, CUR_END, CUR_END.plusYears(1),
                List.of(line("RENT", "40000"))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cannot create lease. Unit is not vacant.")
                .hasMessageContaining("overlapping dates");
    }

    /** F14-13: two drafts cut while the unit was free; the second's dry run names the first once it posts. */
    @Test
    void theDryRunReportsTheDoubleLetThePostRefuses() {
        Unit unit = fixtures.createUnit(fixtures.property(), "OV-" + UUID.randomUUID().toString().substring(0, 4));
        Renter first = fixtures.createRenter("First Renter");
        Renter second = fixtures.createRenter("Second Renter");
        UUID a = fixtures.draftLease(unit, first, CUR_START, CUR_START, CUR_END, List.of(line("RENT", "36000")));
        UUID b = fixtures.draftLease(unit, second, CUR_START, CUR_START.plusDays(10), CUR_END.plusDays(10),
                List.of(line("RENT", "36000")));
        fixtures.generateGrid(a, 2, CUR_START);
        fixtures.generateGrid(b, 2, CUR_START.plusDays(10));
        posting.post(a);

        PostLeaseDryRunResponse dry = posting.dryRun(b);
        assertThat(dry.ok()).isFalse();
        assertThat(dry.errors()).anyMatch(e -> e.contains("already has an active lease"));
        assertThatThrownBy(() -> posting.post(b))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already has an active lease");
        assertThat(statusOf(b)).isEqualTo(LeaseStatus.DRAFT);
    }

    /** F14-01: a posted lease starting later reserves its unit; it does not occupy it. */
    @Test
    void occupancyCountsLeasesThatCoverToday() {
        Unit current = fixtures.unit();
        Unit future = fixtures.createUnit(fixtures.property(), "FU-" + UUID.randomUUID().toString().substring(0, 4));
        fixtures.createUnit(fixtures.property(), "VA-" + UUID.randomUUID().toString().substring(0, 4));
        posted(current, fixtures.renter(), CUR_START, CUR_END);
        posted(future, fixtures.createRenter("Future Renter"), TODAY.plusDays(20), TODAY.plusDays(385));

        fixtures.asTenantAdmin();
        DashboardSummaryDTO s = dashboard.getSummary();
        assertThat(s.getTotalUnits()).isEqualTo(3);
        assertThat(s.getOccupiedUnits()).isEqualTo(1);
        assertThat(s.getReservedUnits()).isEqualTo(1);
        assertThat(s.getVacantUnits()).isEqualTo(1);

        List<Unit> units = unitService.getUnitsByProperty(fixtures.property().getId());
        assertThat(units).filteredOn(u -> u.getId().equals(current.getId()))
                .extracting(Unit::getOccupancy).containsExactly("OCCUPIED");
        assertThat(units).filteredOn(u -> u.getId().equals(future.getId()))
                .extracting(Unit::getOccupancy).containsExactly("RESERVED");
        assertThat(units).filteredOn(u -> u.getId().equals(future.getId()))
                .extracting(Unit::getNextLeaseStart).containsExactly(TODAY.plusDays(20));
    }

    /**
     * F14-35: a renewal that starts before its predecessor ends is refused at draft,
     * dry run and post with one message; back-to-back is allowed.
     */
    @Test
    void aRenewalMustStartAfterThePredecessorEnds() {
        UUID current = posted(fixtures.unit(), fixtures.renter(), CUR_START, CUR_END);
        String refusal = "A renewal must start after the lease it renews ends";

        assertThatThrownBy(() -> renewal.renew(current, new com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest(
                CUR_START, CUR_END.minusDays(20), CUR_END.plusYears(1), null, false)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining(refusal);
        assertThatThrownBy(() -> renewal.renew(current, new com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest(
                CUR_START, CUR_END, CUR_END.plusYears(1), null, false)))
                .as("sharing the last day").isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining(refusal);

        UUID draft = renewal.renew(current, new com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest(
                CUR_START, CUR_END.plusDays(1), CUR_END.plusYears(1), null, false)).getId();
        fixtures.generateGrid(draft, 2, CUR_END.plusDays(1));
        assertThat(posting.dryRun(draft).errors()).as("back-to-back").isEmpty();

        // A start moved earlier after drafting is caught by the dry run and the post.
        jdbc.update("update leases set start_date = ? where id = ?", CUR_END.minusDays(20), draft);
        PostLeaseDryRunResponse dry = posting.dryRun(draft);
        assertThat(dry.ok()).isFalse();
        assertThat(dry.errors()).anyMatch(e -> e.startsWith(refusal));
        assertThatThrownBy(() -> posting.post(draft))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining(refusal);
        assertThat(statusOf(current)).isEqualTo(LeaseStatus.ACTIVE);
    }

    /** R1 P2-1: an extension may not run into the next renter's back-to-back lease. */
    @Test
    void anExtensionIntoABackToBackLeaseIsRefused() {
        Unit unit = fixtures.unit();
        UUID current = posted(unit, fixtures.renter(), CUR_START, CUR_END);
        posted(unit, fixtures.createRenter("Next Renter"), CUR_END.plusDays(1), CUR_END.plusYears(1));

        assertThatThrownBy(() -> renewal.extend(current, new com.datagami.rentaxis.api.dto.lease.ExtendLeaseRequest(
                CUR_END.plusDays(60), TODAY, List.of(line("RENT", "6000")),
                List.of(LeaseTestFixtures.chequeRow("6000", CUR_END.plusDays(1))))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("This unit already has an active lease for these dates");
        LocalDate end = tx.execute(s -> leaseRepo.findById(current).orElseThrow().getEndDate());
        assertThat(end).isEqualTo(CUR_END);
    }

    /** R1 P2-4: a termination dated ahead holds the unit through that date. */
    @Test
    void aLeaseTerminatedWithADateAheadStillHoldsTheUnit() {
        Unit unit = fixtures.createUnit(fixtures.property(), "TN-" + UUID.randomUUID().toString().substring(0, 4));
        UUID leaving = posted(unit, fixtures.createRenter("Leaving Renter"), CUR_START, CUR_END);
        LocalDate t = TODAY.plusDays(10);
        termination.terminate(leaving, new com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest(t, null, null, null), null);
        assertThat(statusOf(leaving)).isEqualTo(LeaseStatus.TERMINATED);

        assertThatThrownBy(() -> fixtures.draftLease(unit, fixtures.createRenter("Too Early"), TODAY, t, t.plusYears(1),
                List.of(line("RENT", "36000"))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("overlapping dates");
        fixtures.draftLease(unit, fixtures.createRenter("On Time"), TODAY, t.plusDays(1), t.plusYears(1),
                List.of(line("RENT", "36000")));

        fixtures.asTenantAdmin();
        assertThat(unitService.getUnitsByProperty(fixtures.property().getId()))
                .filteredOn(u -> u.getId().equals(unit.getId()))
                .extracting(Unit::getOccupancy).containsExactly("OCCUPIED");
    }

    /** R1 P2-5: a renewal posted ahead takes over the unit's rent on its start date. */
    @Test
    void aRenewalTakesOverTheUnitsRentOnItsStartDate() {
        Unit unit = fixtures.createUnit(fixtures.property(), "RN-" + UUID.randomUUID().toString().substring(0, 4));
        UUID current = posted(unit, fixtures.renter(), CUR_START, CUR_END);
        UUID next = renewal.renew(current, new com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest(
                CUR_START, CUR_END.plusDays(1), CUR_END.plusYears(1), List.of(line("RENT", "48000")), false)).getId();
        fixtures.generateGrid(next, 2, CUR_END.plusDays(1));
        posting.post(next);

        // F14-57: the unit carries the holder's rent annualised.
        java.math.BigDecimal oldRent = tx.execute(s -> com.datagami.rentaxis.core.service.LeaseService.annualRent(
                leaseRepo.findById(current).orElseThrow()));
        java.math.BigDecimal newRent = tx.execute(s -> com.datagami.rentaxis.core.service.LeaseService.annualRent(
                leaseRepo.findById(next).orElseThrow()));
        assertThat(newRent).isNotEqualByComparingTo(oldRent);
        assertThat(rentOf(unit))
                .as("the current lease still runs").isEqualByComparingTo(oldRent);

        leaseService.syncUnitHolders(CUR_END);
        assertThat(rentOf(unit))
                .isEqualByComparingTo(oldRent);
        leaseService.syncUnitHolders(CUR_END.plusDays(1));
        assertThat(rentOf(unit))
                .as("the renewal's start date").isEqualByComparingTo(newRent);
    }

    private java.math.BigDecimal rentOf(Unit unit) {
        return tx.execute(s -> unitRepo.findById(unit.getId()).orElseThrow().getActualRent());
    }

    private Unit unitNow(Unit unit) {
        return tx.execute(s -> unitRepo.findById(unit.getId()).orElseThrow());
    }

    /**
     * R2 N-1: a termination dated ahead keeps the unit held — stored OCCUPIED with
     * the leaving renter — through the termination date, a nightly sync included,
     * and the sync the day after releases it.
     */
    @Test
    void aUnitTerminatedAheadIsHeldUntilTheDateAndReleasedTheDayAfter() {
        Unit unit = fixtures.createUnit(fixtures.property(), "TR-" + UUID.randomUUID().toString().substring(0, 4));
        Renter leaving = fixtures.createRenter("Leaving Lina");
        UUID lease = posted(unit, leaving, CUR_START, CUR_END);
        LocalDate t = TODAY.plusDays(10);
        termination.terminate(lease, new com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest(t, null, null, null), null);

        Unit afterTerminate = unitNow(unit);
        assertThat(afterTerminate.getStatus()).as("still held on the day of the termination")
                .isEqualTo(com.datagami.rentaxis.domain.entity.enums.UnitStatus.OCCUPIED);
        assertThat(afterTerminate.getCurrentTenantName()).isEqualTo(leaving.getNameEn());

        leaseService.syncUnitHolders(TODAY);
        assertThat(unitNow(unit).getCurrentTenantName()).as("a nightly sync keeps it").isEqualTo(leaving.getNameEn());
        leaseService.syncUnitHolders(t);
        assertThat(unitNow(unit).getStatus()).as("on the termination date")
                .isEqualTo(com.datagami.rentaxis.domain.entity.enums.UnitStatus.OCCUPIED);

        leaseService.syncUnitHolders(t.plusDays(1));
        Unit released = unitNow(unit);
        assertThat(released.getStatus()).as("the day after").isEqualTo(com.datagami.rentaxis.domain.entity.enums.UnitStatus.VACANT);
        assertThat(released.getCurrentTenantName()).isNull();
        assertThat(released.getActualRent()).isEqualByComparingTo("0");
    }

    /** R2 N-5: MAINTENANCE is never overwritten, and an unchanged unit is not saved or counted again. */
    @Test
    void theSyncLeavesMaintenanceAloneAndIsQuietWhenNothingChanged() {
        Unit unit = fixtures.createUnit(fixtures.property(), "MN-" + UUID.randomUUID().toString().substring(0, 4));
        posted(unit, fixtures.createRenter("Mona Maintenance"), CUR_START, CUR_END);
        tx.executeWithoutResult(s -> {
            Unit u = unitRepo.findById(unit.getId()).orElseThrow();
            u.setStatus(com.datagami.rentaxis.domain.entity.enums.UnitStatus.MAINTENANCE);
            unitRepo.save(u);
        });

        leaseService.syncUnitHolders(TODAY);
        assertThat(leaseService.syncUnitHolders(TODAY)).as("second run: nothing left to change").isZero();
        assertThat(unitNow(unit).getStatus()).isEqualTo(com.datagami.rentaxis.domain.entity.enums.UnitStatus.MAINTENANCE);
    }
}
