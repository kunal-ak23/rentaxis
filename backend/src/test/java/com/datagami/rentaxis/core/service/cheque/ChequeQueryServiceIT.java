package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.cheque.AgingReportDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeSummaryDTO;
import com.datagami.rentaxis.api.dto.cheque.LeaseChequeStatsDTO;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
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
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The register's read side against a real database and a real posted lease.
 *
 * <p>Two things are being pinned down here and neither can be mocked. The first is
 * that the tiles agree with the list under them: every "due" and "overdue" number
 * the API reports has to be what {@link ChequeDueRules} says about the very same
 * rows, because the screen shows both at once and a disagreement reads as a bug in
 * the money. The second is that a property manager's scope is applied by the
 * <em>query</em> — a page filtered after the database counted it reports totals
 * covering buildings the caller may not see.</p>
 *
 * <p><b>Transactions.</b> {@code TenantAspect} only enables the Hibernate tenant
 * filter inside one, so every read-back goes through {@link #tx}.</p>
 */
@SpringBootTest
class ChequeQueryServiceIT extends AbstractPostgresIT {

    @Autowired ChequeQueryService query;
    @Autowired ChequeService chequeService;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService generation;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired ChequeRepository chequeRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;
    @Autowired TransactionTemplate tx;

    private LeaseTestFixtures fixtures;

    /** The whole test runs "as of" a fixed day, so grace arithmetic is not clock-dependent. */
    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 1, 5);
    private static final LocalDate START = LocalDate.of(2026, 2, 1);
    private static final LocalDate END = LocalDate.of(2027, 1, 31);
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 15);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    /** 51,000 over four numbered instruments, on the books. */
    private PostLeaseResponse posted() {
        return fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000")), 4, "100040");
    }

    private List<Cheque> register(UUID leaseId) {
        return tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId));
    }

    // ------------------------------------------------------------------
    // the tiles agree with the rule
    // ------------------------------------------------------------------

    /**
     * The summary after the register has been worked: one banked, one cleared, one
     * returned, one still in the drawer.
     */
    @Test
    void summaryCountsWhatTheRegisterActuallyHolds() {
        UUID leaseId = posted().lease().getId();
        List<Cheque> rows = register(leaseId);

        chequeService.deposit(rows.get(0).getId(), ChequeActionRequest.on(LocalDate.of(2026, 2, 2)));
        chequeService.clear(rows.get(0).getId(), ChequeActionRequest.on(LocalDate.of(2026, 9, 3)));
        chequeService.deposit(rows.get(1).getId(), ChequeActionRequest.on(LocalDate.of(2026, 5, 2)));
        chequeService.bounce(rows.get(1).getId(), new ChequeActionRequest(
                LocalDate.of(2026, 5, 8), null, ChequeFailureReason.BOUNCE, null));

        ChequeSummaryDTO summary = query.summary(null, AS_OF);

        // Two rows never moved: 100042 and 100043.
        assertThat(summary.registeredCount()).isEqualTo(2);
        assertThat(summary.depositedCount()).isZero();
        assertThat(summary.bouncedCount()).isEqualTo(1);
        assertThat(summary.bouncedAmount()).isEqualByComparingTo(rows.get(1).getAmount());
        // Cleared on 3 September, which is the month AS_OF falls in.
        assertThat(summary.clearedThisMonthAmount()).isEqualByComparingTo(rows.get(0).getAmount());
    }

    /**
     * The mutation check that matters most: every due/overdue number the API
     * reports is {@link ChequeDueRules} applied to the rows the API listed.
     *
     * <p>Asserted by recomputing rather than by hard-coding, so a change to either
     * the SQL predicate or the rule that made them disagree fails here.</p>
     */
    @Test
    void dueAndOverdueCountsEqualTheRuleOverTheSameRows() {
        UUID leaseId = posted().lease().getId();
        List<Cheque> rows = register(leaseId);
        // A bounce is due whatever its date — the debt is live from the moment it
        // failed — so it has to appear in both the list and the count.
        chequeService.deposit(rows.get(3).getId(), ChequeActionRequest.on(LocalDate.of(2026, 11, 2)));
        chequeService.bounce(rows.get(3).getId(), new ChequeActionRequest(
                LocalDate.of(2026, 11, 8), null, ChequeFailureReason.BOUNCE, null));

        List<ChequeDTO> listed = query.due(null, AS_OF, Pageable.unpaged()).getContent();
        ChequeSummaryDTO summary = query.summary(null, AS_OF);

        int graceDays = tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow().getGracePeriodDays());
        List<Cheque> expectedDue = tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId).stream()
                .filter(c -> ChequeDueRules.due(c, AS_OF))
                .toList());
        long expectedOverdue = expectedDue.stream()
                .filter(c -> ChequeDueRules.overdue(c, graceDays, AS_OF))
                .count();

        assertThat(listed).hasSize(expectedDue.size());
        assertThat(summary.dueCount()).isEqualTo(expectedDue.size());
        assertThat(summary.overdueCount()).isEqualTo(expectedOverdue);
        assertThat(listed).extracting(ChequeDTO::id)
                .containsExactlyInAnyOrderElementsOf(expectedDue.stream().map(Cheque::getId).toList());
        // And the bounced row is in there, although its date is two months away.
        assertThat(listed).extracting(ChequeDTO::id).contains(rows.get(3).getId());
    }

    /**
     * The aging report's buckets are the due rows, split by how far past grace they
     * are, and its total is their total.
     */
    @Test
    void agingBucketsTheDueRowsByLatenessAndNothingElse() {
        UUID leaseId = posted().lease().getId();

        AgingReportDTO report = query.aging(null, AS_OF);
        List<ChequeDTO> due = query.due(null, AS_OF, Pageable.unpaged()).getContent();

        assertThat(report.buckets()).extracting(AgingReportDTO.Bucket::label)
                .containsExactly("Current", "1-30", "31-60", "61-90", "90+");
        assertThat(report.totalCount()).isEqualTo(due.size());
        BigDecimal expectedTotal = due.stream()
                .map(ChequeDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(report.totalOutstanding()).isEqualByComparingTo(expectedTotal);
        long bucketed = report.buckets().stream().mapToLong(AgingReportDTO.Bucket::count).sum();
        assertThat(bucketed).isEqualTo(report.totalCount());
        // Nothing on this lease has cleared, so the register's own rows are all here.
        assertThat(register(leaseId)).isNotEmpty();
    }

    // ------------------------------------------------------------------
    // property-manager scoping
    // ------------------------------------------------------------------

    /**
     * A manager assigned to one building gets that building's register and no other
     * — in the list, in the tiles and in the aging report.
     *
     * <p>Drop the scoping from {@code summary} and this fails on the counts, not on
     * a 403: the point is that the numbers themselves are narrowed.</p>
     */
    @Test
    void aPropertyManagerSeesOnlyTheirOwnPropertysRegister() {
        UUID mine = posted().lease().getId();
        Property otherProperty = fixtures.createProperty("OTH");
        Unit otherUnit = fixtures.createUnit(otherProperty, "909");
        Renter otherRenter = fixtures.createRenter("Other Renter");
        UUID theirs = fixtures.postedLease(otherUnit, otherRenter, CONTRACT_DATE, START, END,
                List.of(line("RENT", "24000")), 2, "200010").lease().getId();

        ChequeSummaryDTO wholeEstate = query.summary(null, AS_OF);
        assertThat(wholeEstate.registeredCount()).isEqualTo(6);

        asPropertyManagerFor(fixtures.property().getId());

        ChequeSummaryDTO scoped = query.summary(null, AS_OF);
        assertThat(scoped.registeredCount()).isEqualTo(4);
        assertThat(scoped.registeredAmount()).isEqualByComparingTo("51000");

        assertThat(query.search(null, null, null, null, null, null, PageRequest.of(0, 50)).getTotalElements())
                .isEqualTo(4);
        // Aging counts what is *due*, not what is registered: the instalments are
        // spaced Feb/May/Aug/Nov and only three have matured by AS_OF.
        assertThat(query.aging(null, AS_OF).totalCount()).isEqualTo(3);
        assertThat(query.statsByLeases(List.of(mine, theirs), AS_OF))
                .extracting(LeaseChequeStatsDTO::leaseId).containsExactly(mine);
    }

    /**
     * Naming a property outside the manager's set answers empty, not that
     * property's data. Without this, the assignment is a default rather than a
     * boundary — anyone who knows a property id is out of it.
     */
    @Test
    void anExplicitPropertyOutsideTheManagersSetYieldsNothing() {
        posted();
        Property otherProperty = fixtures.createProperty("OTH");
        Unit otherUnit = fixtures.createUnit(otherProperty, "909");
        Renter otherRenter = fixtures.createRenter("Other Renter");
        fixtures.postedLease(otherUnit, otherRenter, CONTRACT_DATE, START, END,
                List.of(line("RENT", "24000")), 2, "200010");

        asPropertyManagerFor(fixtures.property().getId());

        assertThat(query.summary(otherProperty.getId(), AS_OF).registeredCount()).isZero();
        assertThat(query.search(otherProperty.getId(), null, null, null, null, null,
                PageRequest.of(0, 50)).getContent()).isEmpty();
        assertThat(query.due(otherProperty.getId(), AS_OF, Pageable.unpaged()).getContent()).isEmpty();
        assertThat(query.postDated(otherProperty.getId(), YearMonth.of(2026, 11))).isEmpty();
    }

    // ------------------------------------------------------------------
    // the rest of the table
    // ------------------------------------------------------------------

    /** The forward book: what matures in the named month and has not settled. */
    @Test
    void postDatedReturnsTheMonthsUnsettledInstrumentsInMaturityOrder() {
        UUID leaseId = posted().lease().getId();
        List<Cheque> rows = register(leaseId);
        YearMonth month = YearMonth.from(rows.get(1).getChequeDate());

        List<ChequeDTO> book = query.postDated(null, month);

        assertThat(book).isNotEmpty();
        assertThat(book).extracting(ChequeDTO::chequeDate)
                .allSatisfy(d -> assertThat(YearMonth.from(d)).isEqualTo(month));
        assertThat(book).isSortedAccordingTo(
                java.util.Comparator.comparing(ChequeDTO::chequeDate));
    }

    /** Per-lease totals, in the order the caller asked for them. */
    @Test
    void statsByLeasesFoldTheRegisterPerLease() {
        UUID leaseId = posted().lease().getId();
        List<Cheque> rows = register(leaseId);
        chequeService.deposit(rows.get(0).getId(), ChequeActionRequest.on(LocalDate.of(2026, 2, 2)));
        chequeService.clear(rows.get(0).getId(), ChequeActionRequest.on(LocalDate.of(2026, 2, 4)));

        List<LeaseChequeStatsDTO> stats = query.statsByLeases(List.of(leaseId), AS_OF);

        assertThat(stats).hasSize(1);
        LeaseChequeStatsDTO s = stats.getFirst();
        assertThat(s.total()).isEqualTo(4);
        assertThat(s.cleared()).isEqualTo(1);
        assertThat(s.uncleared()).isEqualTo(3);
        assertThat(s.bounced()).isZero();
        assertThat(s.totalAmount()).isEqualByComparingTo("51000");
        assertThat(s.clearedAmount()).isEqualByComparingTo(rows.get(0).getAmount());
    }

    /**
     * A page the caller did not sort comes back in schedule order.
     *
     * <p>Postgres is free to return an unsorted page in any order, which makes page
     * two overlap page one — the register's rows would appear twice and others not
     * at all.</p>
     */
    @Test
    void pagedListsGetTheRegistersOwnOrderWhenTheRequestHasNone() {
        posted();

        List<ChequeDTO> page = query.search(null, null, null, null, null, null,
                PageRequest.of(0, 50)).getContent();

        assertThat(page).hasSize(4);
        assertThat(page).isSortedAccordingTo(
                java.util.Comparator.comparing(ChequeDTO::chequeDate).thenComparing(ChequeDTO::seqNo));
    }

    /**
     * The combination the portfolio import creates: a lease that is ACTIVE carrying
     * rows that are still DRAFT.
     *
     * <p>Filtering on the lease's status alone let these onto the register as
     * instruments with no {@code PDR} behind them — paper the landlord was told it
     * held and nobody had handed over. A DRAFT row is a grid row wherever it sits.</p>
     */
    @Test
    void draftRowsOnAPostedLeaseAreInvisibleToTheRegister() {
        UUID leaseId = posted().lease().getId();
        ChequeSummaryDTO before = query.summary(null, AS_OF);

        // A grid row added to the ACTIVE lease behind the register's back, exactly
        // as the import leaves one.
        UUID draftRowId = tx.execute(s -> {
            Cheque live = chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId).getFirst();
            Cheque draft = new Cheque();
            draft.setTenantId(live.getTenantId());
            draft.setLease(live.getLease());
            draft.setUnit(live.getUnit());
            draft.setProperty(live.getProperty());
            draft.setRenter(live.getRenter());
            draft.setSeqNo(99);
            draft.setPostingDate(CONTRACT_DATE);
            draft.setChequeDate(LocalDate.of(2026, 3, 1));
            draft.setAmount(new BigDecimal("9999"));
            draft.setStatus(ChequeStatus.DRAFT);
            draft.setStatusChangedAt(java.time.Instant.now());
            return chequeRepo.save(draft).getId();
        });

        // Nothing moved: not the list, not a tile, not the per-lease stats.
        assertThat(query.search(null, null, null, null, null, null, PageRequest.of(0, 50)).getContent())
                .extracting(ChequeDTO::id).doesNotContain(draftRowId);
        ChequeSummaryDTO after = query.summary(null, AS_OF);
        assertThat(after.registeredCount()).isEqualTo(before.registeredCount());
        assertThat(after.dueAmount()).isEqualByComparingTo(before.dueAmount());
        assertThat(query.statsByLeases(List.of(leaseId), AS_OF).getFirst().total()).isEqualTo(4);
        assertThat(query.due(null, AS_OF, Pageable.unpaged()).getContent())
                .extracting(ChequeDTO::id).doesNotContain(draftRowId);

        // And it is not a register row you can fetch by id either — the grid is read
        // through the lease.
        assertThatThrownBy(() -> query.get(draftRowId)).isInstanceOf(NotFoundException.class);
    }

    /**
     * A manager may not read a row belonging to a building they were not assigned.
     * Not found rather than forbidden, so the error code cannot be used to discover
     * that the cheque exists.
     */
    @Test
    void aManagerCannotFetchAForeignPropertysRow() {
        UUID mine = posted().lease().getId();
        Property otherProperty = fixtures.createProperty("OTH");
        Unit otherUnit = fixtures.createUnit(otherProperty, "909");
        Renter otherRenter = fixtures.createRenter("Other Renter");
        UUID theirs = fixtures.postedLease(otherUnit, otherRenter, CONTRACT_DATE, START, END,
                List.of(line("RENT", "24000")), 2, "200010").lease().getId();
        UUID ownRow = register(mine).getFirst().getId();
        UUID foreignRow = register(theirs).getFirst().getId();

        asPropertyManagerFor(fixtures.property().getId());

        // Their building: gone. Ours: there, so the refusal is about the property
        // and not about the manager being unable to read anything.
        assertThatThrownBy(() -> query.get(foreignRow)).isInstanceOf(NotFoundException.class);
        assertThat(query.get(ownRow).id()).isEqualTo(ownRow);
    }

    /** A batch bigger than the cap is refused rather than silently truncated. */
    @Test
    void statsByLeasesRefusesAnOversizedBatch() {
        List<UUID> tooMany = java.util.stream.Stream
                .generate(UUID::randomUUID)
                .limit(ChequeQueryService.MAX_STATS_LEASES + 1)
                .toList();

        assertThatThrownBy(() -> query.statsByLeases(tooMany, AS_OF))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining(String.valueOf(ChequeQueryService.MAX_STATS_LEASES));
    }

    /** A cheque on a lease nobody has signed is a proposal, not money owed. */
    @Test
    void aDraftLeasesRowsAreInvisibleToEveryRegisterQuery() {
        UUID draftLeaseId = fixtures.draftLease(CONTRACT_DATE, START, END, List.of(line("RENT", "12000")));
        fixtures.generateGrid(draftLeaseId, 2, START);

        assertThat(query.search(null, null, null, null, null, null, PageRequest.of(0, 50)).getContent()).isEmpty();
        assertThat(query.summary(null, AS_OF).registeredCount()).isZero();
        assertThat(query.due(null, AS_OF, Pageable.unpaged()).getContent()).isEmpty();
        assertThat(register(draftLeaseId)).hasSize(2).allSatisfy(
                c -> assertThat(c.getStatus()).isEqualTo(ChequeStatus.DRAFT));
    }

    /**
     * A property manager with no assignment at all sees nothing, and the query is
     * never asked: an empty {@code in} list is not a question worth putting to
     * Postgres, and "no restriction" must never be confused with "no properties".
     */
    @Test
    void anUnassignedManagerSeesNothing() {
        posted();
        asPropertyManagerFor(null);

        assertThat(query.summary(null, AS_OF).registeredCount()).isZero();
        assertThat(query.search(null, null, null, null, null, null, PageRequest.of(0, 50)).getContent()).isEmpty();
        assertThat(query.aging(null, AS_OF).totalCount()).isZero();
        assertThat(query.statsByLeases(List.of(UUID.randomUUID()), AS_OF)).isEmpty();
    }

    /** A PROPERTY_MANAGER in the security context, assigned to {@code propertyId} or to nothing. */
    private void asPropertyManagerFor(UUID propertyId) {
        // A real user row: user_property_assignments has an FK to users, and a
        // manager the policy cannot resolve falls through to "nobody", which would
        // make these tests pass for the wrong reason.
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
