package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.DashboardSummaryDTO;
import com.datagami.rentaxis.api.dto.MonthlyCollectionDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeSummaryDTO;
import com.datagami.rentaxis.core.service.cheque.ChequeQueryService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard's money is the register's money, for whoever is looking.
 *
 * <p>A headline tile that covers the whole organisation above a register list
 * scoped to one building is two bugs at once: the tile is a leak — it is somebody
 * else's rent — and the screen contradicts itself, which is how a manager learns
 * not to trust either number. So the assertion here is an equality: for one
 * property manager, the dashboard's totals and the register summary's totals are
 * the same figures, and neither includes the building they were not assigned.</p>
 */
@SpringBootTest
class DashboardServiceScopingIT extends AbstractPostgresIT {

    @Autowired DashboardService dashboard;
    @Autowired ChequeQueryService query;
    @Autowired ChequeService chequeService;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService generation;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired ChequeRepository chequeRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;
    @Autowired TransactionTemplate tx;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 1, 5);
    private static final LocalDate START = LocalDate.of(2026, 2, 1);
    private static final LocalDate END = LocalDate.of(2027, 1, 31);

    private LeaseTestFixtures fixtures;
    private UUID mineLeaseId;
    private Property theirProperty;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);

        mineLeaseId = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000")), 4, "100040").lease().getId();

        theirProperty = fixtures.createProperty("OTH");
        Unit theirUnit = fixtures.createUnit(theirProperty, "909");
        Renter theirRenter = fixtures.createRenter("Other Renter");
        fixtures.postedLease(theirUnit, theirRenter, CONTRACT_DATE, START, END,
                List.of(line("RENT", "24000")), 2, "200010");
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private List<Cheque> register(UUID leaseId) {
        return tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId));
    }

    /**
     * The manager's dashboard and their register summary report the same money, and
     * it is only their building's.
     */
    @Test
    void aManagersDashboardTotalsEqualTheirRegisterSummary() {
        // Bank and clear one of the manager's own cheques, so "collected" and
        // "received this month" are non-zero and could be got wrong.
        List<Cheque> mine = register(mineLeaseId);
        chequeService.deposit(mine.getFirst().getId(), ChequeActionRequest.on(LocalDate.of(2026, 2, 2)));
        chequeService.clear(mine.getFirst().getId(), ChequeActionRequest.on(LocalDate.now()));
        BigDecimal myClearedAmount = mine.getFirst().getAmount();

        DashboardSummaryDTO wholeEstate = dashboard.getSummary();

        asPropertyManagerFor(fixtures.property().getId());
        DashboardSummaryDTO scoped = dashboard.getSummary();
        ChequeSummaryDTO register = query.summary(null, LocalDate.now());

        // The manager's tiles are their own building's, and they agree with the
        // register screen they sit above.
        assertThat(scoped.getCollectedAmount()).isEqualByComparingTo(myClearedAmount);
        assertThat(scoped.getPendingAmount())
                .isEqualByComparingTo(register.registeredAmount().add(register.depositedAmount()));
        assertThat(scoped.getOverdueAmount()).isEqualByComparingTo(register.overdueAmount());
        assertThat(scoped.getReceivedThisMonth()).isEqualByComparingTo(register.clearedThisMonthAmount());

        // And they are strictly less than the organisation's, so the scoping is
        // actually removing the other property rather than being a no-op.
        assertThat(scoped.getPendingAmount()).isLessThan(wholeEstate.getPendingAmount());
        assertThat(scoped.getPendingAmount()).isEqualByComparingTo("38250"); // 51,000 less the cleared 12,750
    }

    /** The activity feed cannot narrate another building's collections either. */
    @Test
    void theActivityFeedIsScopedToo() {
        List<Cheque> mine = register(mineLeaseId);
        chequeService.deposit(mine.getFirst().getId(), ChequeActionRequest.on(LocalDate.of(2026, 2, 2)));

        // Organisation-wide, both buildings' movements are in the feed.
        assertThat(dashboard.getSummary().getRecentActivity())
                .extracting(DashboardSummaryDTO.RecentActivityItem::getDescription)
                .anySatisfy(d -> assertThat(d).contains("Unit 101"))
                .anySatisfy(d -> assertThat(d).contains("Unit 909"));

        // A manager of the other building sees their own and none of ours — the
        // deposit above is the newest movement in the organisation, so an unscoped
        // feed would put it at the top of their screen.
        asPropertyManagerFor(theirProperty.getId());
        assertThat(dashboard.getSummary().getRecentActivity())
                .isNotEmpty()
                .extracting(DashboardSummaryDTO.RecentActivityItem::getDescription)
                .allSatisfy(d -> assertThat(d).contains("Unit 909"));
    }

    /** The twelve-month chart is scoped on the same terms. */
    @Test
    void theMonthlyChartIsScopedToo() {
        BigDecimal wholeEstate = expectedTotal(dashboard.getMonthlyCollections());

        asPropertyManagerFor(fixtures.property().getId());
        BigDecimal scoped = expectedTotal(dashboard.getMonthlyCollections());

        assertThat(scoped).isLessThan(wholeEstate);
        assertThat(scoped.signum()).isPositive();
    }

    /**
     * The portfolio tiles are the manager's buildings too.
     *
     * <p>These counts were the last part of the screen left organisation-wide: a
     * manager assigned to one of two buildings was shown both properties, both
     * buildings' units, every lease and the whole estate's contracted rent, above
     * money tiles that had already been narrowed to theirs. Occupancy was the worst
     * of it — an average over buildings they cannot act on, presented as their
     * number.</p>
     */
    @Test
    void aManagersPortfolioTilesCountOnlyTheirOwnProperties() {
        DashboardSummaryDTO wholeEstate = dashboard.getSummary();
        assertThat(wholeEstate.getTotalProperties()).isEqualTo(2);
        assertThat(wholeEstate.getTotalUnits()).isEqualTo(2);
        assertThat(wholeEstate.getActiveLeases()).isEqualTo(2);
        assertThat(wholeEstate.getTotalRentRevenue()).isEqualByComparingTo("75000"); // 51,000 + 24,000

        asPropertyManagerFor(fixtures.property().getId());
        DashboardSummaryDTO scoped = dashboard.getSummary();

        assertThat(scoped.getTotalProperties()).isEqualTo(1);
        assertThat(scoped.getTotalUnits()).isEqualTo(1);
        assertThat(scoped.getOccupiedUnits()).isEqualTo(1);
        assertThat(scoped.getVacantUnits()).isZero();
        assertThat(scoped.getOccupancyRate()).isEqualTo(100.0);
        assertThat(scoped.getActiveLeases()).isEqualTo(1);
        assertThat(scoped.getDraftLeases()).isZero();
        // Their building's contracted rent, not the estate's.
        assertThat(scoped.getTotalRentRevenue()).isEqualByComparingTo("51000");
    }

    /** The manager of the other building sees that one, and its rent. */
    @Test
    void theOtherManagerSeesTheOtherBuilding() {
        asPropertyManagerFor(theirProperty.getId());
        DashboardSummaryDTO scoped = dashboard.getSummary();

        assertThat(scoped.getTotalProperties()).isEqualTo(1);
        assertThat(scoped.getTotalUnits()).isEqualTo(1);
        assertThat(scoped.getActiveLeases()).isEqualTo(1);
        assertThat(scoped.getTotalRentRevenue()).isEqualByComparingTo("24000");
    }

    /** A DRAFT lease counts on its own tile and contributes no revenue. */
    @Test
    void aDraftLeaseCountsAsADraftAndNotAsRevenue() {
        // A second, vacant unit in the manager's own building: the one their posted
        // lease sits on is already occupied.
        Unit spare = fixtures.createUnit(fixtures.property(), "102");
        fixtures.draftLease(spare, fixtures.createRenter("Draft Renter"),
                CONTRACT_DATE, START, END, List.of(line("RENT", "9000")));

        DashboardSummaryDTO wholeEstate = dashboard.getSummary();
        assertThat(wholeEstate.getDraftLeases()).isEqualTo(1);
        assertThat(wholeEstate.getTotalRentRevenue()).isEqualByComparingTo("75000");

        asPropertyManagerFor(fixtures.property().getId());
        assertThat(dashboard.getSummary().getDraftLeases()).isEqualTo(1);
        assertThat(dashboard.getSummary().getTotalRentRevenue()).isEqualByComparingTo("51000");
    }

    /**
     * A caller scoped to nothing — a manager with no assignment — gets zeros and an
     * empty chart, not the organisation's books.
     */
    @Test
    void aCallerScopedToNothingSeesZeros() {
        asPropertyManagerFor(null);

        DashboardSummaryDTO summary = dashboard.getSummary();
        // The portfolio block too: an unassigned manager is not shown the estate.
        assertThat(summary.getTotalProperties()).isZero();
        assertThat(summary.getTotalUnits()).isZero();
        assertThat(summary.getOccupiedUnits()).isZero();
        assertThat(summary.getOccupancyRate()).isEqualTo(0.0);
        assertThat(summary.getActiveLeases()).isZero();
        assertThat(summary.getDraftLeases()).isZero();
        assertThat(summary.getExpiringLeases()).isZero();
        assertThat(summary.getTotalRentRevenue()).isEqualByComparingTo("0");
        assertThat(summary.getPendingAmount()).isEqualByComparingTo("0");
        assertThat(summary.getCollectedAmount()).isEqualByComparingTo("0");
        assertThat(summary.getOverdueAmount()).isEqualByComparingTo("0");
        assertThat(summary.getReceivedThisMonth()).isEqualByComparingTo("0");
        assertThat(summary.getPendingThisMonthAmount()).isEqualByComparingTo("0");
        assertThat(summary.getDueThisMonth()).isEqualByComparingTo("0");
        assertThat(summary.getCollectedForThisMonth()).isEqualByComparingTo("0");
        assertThat(summary.getCollectedAgainstDueThisMonth()).isEqualByComparingTo("0");
        assertThat(summary.getCollectedArrears()).isEqualByComparingTo("0");
        assertThat(summary.getCollectedAdvance()).isEqualByComparingTo("0");
        assertThat(summary.getRecentActivity()).isEmpty();
        assertThat(expectedTotal(dashboard.getMonthlyCollections())).isEqualByComparingTo("0");
    }

    /**
     * The collection tile's identity (gap #59): what was banked this month is
     * exactly this month's dues collected, plus arrears, plus advance — and the
     * headline is on one basis, so a catch-up banking run of old cheques reads as
     * arrears instead of as 3,800% of the month.
     */
    @Test
    void receivedThisMonthIsAgainstDuePlusArrearsPlusAdvance() {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate start = monthStart.minusMonths(3);
        Unit unit = fixtures.createUnit(fixtures.property(), "103");
        UUID leaseId = fixtures.postedLease(unit, fixtures.createRenter("Monthly Renter"),
                start.minusDays(10), start, start.plusYears(1).minusDays(1),
                List.of(line("RENT", "12000")), 12, "300010").lease().getId();

        List<Cheque> rows = register(leaseId);
        Cheque twoMonthsAgo = byDate(rows, monthStart.minusMonths(2));
        Cheque lastMonth = byDate(rows, monthStart.minusMonths(1));
        Cheque thisMonth = byDate(rows, monthStart);
        Cheque nextMonth = byDate(rows, monthStart.plusMonths(1));

        // Arrears: an old cheque banked today.
        chequeService.deposit(twoMonthsAgo.getId(), ChequeActionRequest.on(twoMonthsAgo.getChequeDate()));
        chequeService.clear(twoMonthsAgo.getId(), ChequeActionRequest.on(today));
        // Last month's cheque cleared last month: not this month's money at all.
        chequeService.deposit(lastMonth.getId(), ChequeActionRequest.on(lastMonth.getChequeDate()));
        chequeService.clear(lastMonth.getId(), ChequeActionRequest.on(monthStart.minusDays(1)));
        // This month's due, collected.
        chequeService.deposit(thisMonth.getId(), ChequeActionRequest.on(thisMonth.getChequeDate()));
        chequeService.clear(thisMonth.getId(), ChequeActionRequest.on(today));
        // Advance: a post-dated cheque cannot be banked early, so the row is marked
        // collected directly — the shape an early cash payment leaves.
        tx.executeWithoutResult(s -> {
            Cheque c = chequeRepo.findById(nextMonth.getId()).orElseThrow();
            c.setStatus(com.datagami.rentaxis.domain.entity.enums.ChequeStatus.CLEARED);
            c.setClearedAt(today);
            chequeRepo.save(c);
        });

        asPropertyManagerFor(fixtures.property().getId());
        DashboardSummaryDTO summary = dashboard.getSummary();

        assertThat(summary.getCollectedArrears()).isEqualByComparingTo(twoMonthsAgo.getAmount());
        assertThat(summary.getCollectedAgainstDueThisMonth()).isEqualByComparingTo(thisMonth.getAmount());
        assertThat(summary.getCollectedAdvance()).isEqualByComparingTo(nextMonth.getAmount());
        assertThat(summary.getReceivedThisMonth()).isEqualByComparingTo(
                summary.getCollectedAgainstDueThisMonth()
                        .add(summary.getCollectedArrears())
                        .add(summary.getCollectedAdvance()));
        // Due this month is every live row dated in the month, in this building:
        // the monthly lease's row plus whatever the quarterly lease has dated here.
        BigDecimal quarterlyDue = register(mineLeaseId).stream()
                .filter(c -> !c.getChequeDate().isBefore(monthStart)
                        && c.getChequeDate().isBefore(monthStart.plusMonths(1)))
                .map(Cheque::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(summary.getDueThisMonth()).isEqualByComparingTo(thisMonth.getAmount().add(quarterlyDue));
        // Nothing dated this month was paid ahead here, so the headline and the
        // against-due part agree.
        assertThat(summary.getCollectedForThisMonth()).isEqualByComparingTo(thisMonth.getAmount());

        // The other building's manager sees none of it.
        asPropertyManagerFor(theirProperty.getId());
        DashboardSummaryDTO theirs = dashboard.getSummary();
        assertThat(theirs.getCollectedArrears()).isEqualByComparingTo("0");
        assertThat(theirs.getCollectedAgainstDueThisMonth()).isEqualByComparingTo("0");
        assertThat(theirs.getCollectedAdvance()).isEqualByComparingTo("0");
        assertThat(theirs.getCollectedForThisMonth()).isEqualByComparingTo("0");
        assertThat(theirs.getReceivedThisMonth()).isEqualByComparingTo("0");
    }

    /**
     * Review P2-1: an instalment dated this month that was paid last month is
     * this month's money in the bank. The headline (collectedForThisMonth) counts
     * it whenever it cleared; the against-due part of the identity, which only
     * counts what cleared this month, does not, and last month's tile had it as
     * advance.
     */
    @Test
    void aRowDatedThisMonthPaidLastMonthCountsInTheHeadline() {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate start = monthStart.minusMonths(3);
        Unit unit = fixtures.createUnit(fixtures.property(), "104");
        UUID leaseId = fixtures.postedLease(unit, fixtures.createRenter("Early Payer"),
                start.minusDays(10), start, start.plusYears(1).minusDays(1),
                List.of(line("RENT", "12000")), 12, "300020").lease().getId();

        Cheque thisMonth = byDate(register(leaseId), monthStart);
        // Paid ahead on the last day of last month (cash or transfer), so it
        // cleared before its own date.
        tx.executeWithoutResult(s -> {
            Cheque c = chequeRepo.findById(thisMonth.getId()).orElseThrow();
            c.setStatus(com.datagami.rentaxis.domain.entity.enums.ChequeStatus.CLEARED);
            c.setClearedAt(monthStart.minusDays(1));
            chequeRepo.save(c);
        });

        asPropertyManagerFor(fixtures.property().getId());
        DashboardSummaryDTO summary = dashboard.getSummary();

        assertThat(summary.getCollectedForThisMonth()).isEqualByComparingTo(thisMonth.getAmount());
        assertThat(summary.getCollectedAgainstDueThisMonth()).isEqualByComparingTo("0");
        assertThat(summary.getDueThisMonth()).isGreaterThanOrEqualTo(thisMonth.getAmount());
        // The identity still holds: nothing cleared this month.
        assertThat(summary.getReceivedThisMonth()).isEqualByComparingTo(
                summary.getCollectedAgainstDueThisMonth()
                        .add(summary.getCollectedArrears())
                        .add(summary.getCollectedAdvance()));
    }

    private static Cheque byDate(List<Cheque> rows, LocalDate date) {
        return rows.stream().filter(c -> date.equals(c.getChequeDate())).findFirst()
                .orElseThrow(() -> new AssertionError("no row dated " + date + " in " + rows.stream()
                        .map(Cheque::getChequeDate).toList()));
    }

    private static BigDecimal expectedTotal(List<MonthlyCollectionDTO> series) {
        return series.stream().map(MonthlyCollectionDTO::expected)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** A PROPERTY_MANAGER in the security context, assigned to {@code propertyId} or to nothing. */
    private void asPropertyManagerFor(UUID propertyId) {
        User pm = new User();
        pm.setEmail("pm-" + UUID.randomUUID() + "@t.io");
        pm.setName("PM");
        pm.setRole(UserRole.PROPERTY_MANAGER);
        pm.setStatus(UserStatus.ACTIVE);
        pm.setPasswordHash("x");
        pm.setTenantId(fixtures.tenantId());
        UUID userId = userRepo.save(pm).getId();
        if (propertyId != null) {
            UserPropertyAssignment assignment = new UserPropertyAssignment();
            assignment.setUserId(userId);
            assignment.setPropertyId(propertyId);
            assignmentRepo.save(assignment);
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_PROPERTY_MANAGER"))));
    }
}
