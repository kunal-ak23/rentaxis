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
}
