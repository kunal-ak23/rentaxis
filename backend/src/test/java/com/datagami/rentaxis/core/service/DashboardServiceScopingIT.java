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
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
@Testcontainers
class DashboardServiceScopingIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

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
     * A caller scoped to nothing — a manager with no assignment — gets zeros and an
     * empty chart, not the organisation's books.
     */
    @Test
    void aCallerScopedToNothingSeesZeros() {
        asPropertyManagerFor(null);

        DashboardSummaryDTO summary = dashboard.getSummary();
        assertThat(summary.getPendingAmount()).isEqualByComparingTo("0");
        assertThat(summary.getCollectedAmount()).isEqualByComparingTo("0");
        assertThat(summary.getOverdueAmount()).isEqualByComparingTo("0");
        assertThat(summary.getReceivedThisMonth()).isEqualByComparingTo("0");
        assertThat(summary.getPendingThisMonthAmount()).isEqualByComparingTo("0");
        assertThat(summary.getRecentActivity()).isEmpty();
        assertThat(expectedTotal(dashboard.getMonthlyCollections())).isEqualByComparingTo("0");
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
