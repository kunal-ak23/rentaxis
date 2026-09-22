package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.core.service.cheque.ChequeDueRules;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cheque register's read side. Every query here backs a screen or a job that
 * decides money movement — "what do I bank today", "what is overdue" — so the
 * filters (lease status, cheque status, mode, date window) are asserted against
 * rows that differ in exactly one dimension at a time.
 */
@SpringBootTest
@Testcontainers
class ChequeRepositoryIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ChequeRepository cheques;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired TransactionTemplate transactionTemplate;

    static final LocalDate TODAY = LocalDate.of(2026, 9, 18);
    static final Pageable PAGE = PageRequest.of(0, 20);
    /** Big enough for one row per status on both sides of its date, unpaged in effect. */
    static final Pageable BIG_PAGE = PageRequest.of(0, 200);

    UUID tenantId, propertyId, unitId, renterId, leaseId;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("CHQ-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);

        Property p = new Property();
        p.setNameEn("L'Olivier");
        p.setEmirate(Emirate.DUBAI);
        propertyId = propertyRepo.save(p).getId();

        Renter r = new Renter();
        r.setNameEn("Prabhjot Singh");
        renterId = renterRepo.save(r).getId();

        Unit u = new Unit();
        u.setProperty(propertyRepo.findById(propertyId).orElseThrow());
        u.setUnitNumber("304");
        unitId = unitRepo.save(u).getId();

        Lease lease = new Lease();
        lease.setUnit(unitRepo.findById(unitId).orElseThrow());
        lease.setRenter(renterRepo.findById(renterId).orElseThrow());
        lease.setStartDate(LocalDate.of(2026, 9, 1));
        lease.setEndDate(LocalDate.of(2027, 8, 31));
        lease.setRentAmount(new BigDecimal("61000"));
        lease.setDepositAmount(new BigDecimal("3000"));
        lease.setStatus(LeaseStatus.ACTIVE);
        leaseId = leaseRepo.save(lease).getId();
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    /**
     * {@code TenantAspect} enables the Hibernate tenant filter on the session bound
     * to the <em>current</em> transaction, so a repository call made with no
     * transaction already open reads unfiltered — the aspect's session and the one
     * Spring Data opens for the call are not the same. Production callers are
     * services inside a transaction; these assertions have to be too, or every
     * "returns exactly these rows" case below would be asserting the unscoped query
     * and would see the other test methods' tenants.
     */
    private <T> T inTx(Supplier<T> body) {
        return transactionTemplate.execute(status -> body.get());
    }

    private List<Cheque> due(UUID property) {
        return inTx(() -> cheques.findDue(property, TODAY, true, List.of(), PAGE).getContent());
    }

    private List<Cheque> dueForLease(UUID lease) {
        return inTx(() -> cheques.findDueForLease(lease, TODAY));
    }

    private List<Cheque> toDeposit(UUID property) {
        return inTx(() -> cheques.findToDeposit(property, TODAY, true, List.of(), PAGE).getContent());
    }

    private List<Cheque> search(UUID property, ChequeStatus status, ChequeMode mode,
                                LocalDate from, LocalDate to, String term) {
        return searchPage(property, status, mode, from, to, term).getContent();
    }

    private Page<Cheque> searchPage(UUID property, ChequeStatus status, ChequeMode mode,
                                    LocalDate from, LocalDate to, String term) {
        return inTx(() -> cheques.search(property, status, mode, from, to, term, true, List.of(), PAGE));
    }

    private Cheque cheque(int seqNo, String number, LocalDate chequeDate, ChequeStatus status, ChequeMode mode) {
        Cheque c = new Cheque();
        c.setLease(leaseRepo.findById(leaseId).orElseThrow());
        c.setProperty(propertyRepo.findById(propertyId).orElseThrow());
        c.setUnit(unitRepo.findById(unitId).orElseThrow());
        c.setRenter(renterRepo.findById(renterId).orElseThrow());
        c.setSeqNo(seqNo);
        c.setPostingDate(LocalDate.of(2026, 9, 1));
        c.setChequeNumber(number);
        c.setChequeDate(chequeDate);
        c.setAmount(new BigDecimal("13700"));
        c.setStatus(status);
        c.setMode(mode);
        return cheques.save(c);
    }

    /** The three cheques the register cases below are read against. */
    private void seedRegister() {
        cheque(1, "000001", TODAY.minusDays(1), ChequeStatus.REGISTERED, ChequeMode.PDC);
        cheque(2, "000002", TODAY.plusMonths(1), ChequeStatus.REGISTERED, ChequeMode.PDC);
        cheque(3, "000003", TODAY.minusMonths(1), ChequeStatus.CLEARED, ChequeMode.PDC);
    }

    @Test
    void findDue_countsOnlyUnclearedChequesOnOrBeforeToday() {
        seedRegister();

        assertThat(due(null)).extracting(Cheque::getChequeNumber).containsExactly("000001");
    }

    @Test
    void findToDeposit_returnsRegisteredPdcMaturedToday() {
        seedRegister();

        assertThat(toDeposit(null)).extracting(Cheque::getChequeNumber).containsExactly("000001");
    }

    /** A cheque dated today matures today: it belongs in both runs, not tomorrow's. */
    @Test
    void chequeDatedTodayIsDueAndDepositableToday() {
        cheque(1, "000001", TODAY, ChequeStatus.REGISTERED, ChequeMode.PDC);

        assertThat(due(null)).hasSize(1);
        assertThat(toDeposit(null)).hasSize(1);
    }

    /**
     * A cheque already sitting at the bank is not one to bank again, and a cash or
     * transfer receipt is never deposited as paper — both drop out of the deposit run
     * while staying in {@link ChequeRepository#findDue}.
     */
    @Test
    void findToDeposit_excludesAlreadyDepositedAndNonPdcModes() {
        cheque(1, "000001", TODAY.minusDays(1), ChequeStatus.DEPOSITED, ChequeMode.PDC);
        cheque(2, "000002", TODAY.minusDays(1), ChequeStatus.REGISTERED, ChequeMode.TRANSFER);

        assertThat(toDeposit(null)).isEmpty();
        assertThat(due(null)).extracting(Cheque::getChequeNumber)
                .containsExactlyInAnyOrder("000001", "000002");
    }

    /**
     * A cheque on an unsigned draft lease is a plan, not a receivable: it must not
     * show up in the register or in the day's collection worklist.
     */
    @Test
    void draftLeaseChequesAreInvisibleToTheRegister() {
        cheque(1, "000001", TODAY.minusDays(1), ChequeStatus.REGISTERED, ChequeMode.PDC);
        Lease lease = leaseRepo.findById(leaseId).orElseThrow();
        lease.setStatus(LeaseStatus.DRAFT);
        leaseRepo.save(lease);

        assertThat(due(null)).isEmpty();
        assertThat(search(null, null, null, null, null, null)).isEmpty();

        // Re-read: Lease is @Version'd, so the instance saved above is already stale.
        Lease pending = leaseRepo.findById(leaseId).orElseThrow();
        pending.setStatus(LeaseStatus.PENDING_SIGNATURE);
        leaseRepo.save(pending);

        assertThat(due(null)).isEmpty();
        assertThat(search(null, null, null, null, null, null)).isEmpty();
    }

    /**
     * {@link ChequeRepository#findDueForLease} is a hand copy of
     * {@link ChequeRepository#findDue} with the property scope swapped for a lease
     * scope, and nothing in the type system stops the two drifting. They must not:
     * the settlement preview deducts the lease's due rows from the renter's
     * deposit, and the register screen shows them the same rows under the word
     * "due". A row one query calls owed and the other does not is a figure the
     * accountant cannot reconcile against any screen they can open.
     *
     * <p>So this asserts set equality over a register built to differ in every
     * dimension the predicate cares about: maturity (past vs future), status
     * (REGISTERED, DEPOSITED, CLEARED, DRAFT) and the one case where the date does
     * <em>not</em> decide — a BOUNCED cheque dated next month is due now, because
     * it has already failed and the debt does not wait for a calendar date.</p>
     */
    @Test
    void findDueForLeaseReturnsExactlyFindDuesRowsForThatLease() {
        Cheque maturedRegistered = cheque(1, "000001", TODAY.minusDays(1), ChequeStatus.REGISTERED, ChequeMode.PDC);
        cheque(2, "000002", TODAY.plusMonths(1), ChequeStatus.REGISTERED, ChequeMode.PDC);
        Cheque maturedDeposited = cheque(3, "000003", TODAY.minusDays(3), ChequeStatus.DEPOSITED, ChequeMode.PDC);
        Cheque bouncedInTheFuture = cheque(4, "000004", TODAY.plusMonths(2), ChequeStatus.BOUNCED, ChequeMode.PDC);
        cheque(5, "000005", TODAY.minusMonths(1), ChequeStatus.CLEARED, ChequeMode.PDC);
        cheque(6, null, TODAY.minusDays(2), ChequeStatus.DRAFT, ChequeMode.PDC);
        // A gateway session in flight over a matured instalment: nothing has posted,
        // so the money is as owed as it was before the renter opened checkout.
        Cheque inCheckout = cheque(7, "000007", TODAY.minusDays(4), ChequeStatus.ONLINE_PENDING, ChequeMode.PDC);

        List<UUID> expected = due(null).stream()
                .filter(c -> c.getLease().getId().equals(leaseId))
                .map(Cheque::getId)
                .toList();

        assertThat(expected).as("the register's own answer, so a drifting fixture cannot make this vacuous")
                .containsExactlyInAnyOrder(maturedRegistered.getId(), maturedDeposited.getId(),
                        bouncedInTheFuture.getId(), inCheckout.getId());
        assertThat(dueForLease(leaseId)).extracting(Cheque::getId)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    /**
     * {@link ChequeDueRules#due} and the three SQL copies of it are one rule, and
     * this is where that is enforced rather than hoped for.
     *
     * <p>{@code findDue} backs the register screen, the aging report and the summary
     * tiles; {@code findDueForLease} backs the settlement preview's arrears;
     * {@code findAllDue} is what the overdue reminder job walks. Each is the rule
     * written out in JPQL a second, third and fourth time, and the type system says
     * nothing about them agreeing — which is exactly how {@code ONLINE_PENDING} came
     * to be missing from all four at once: every screen agreed, and every screen was
     * wrong by one instalment.</p>
     *
     * <p>So: one row per status, on each side of its date, and set equality against
     * what the Java rule says about the very same rows. A status added to the enum
     * and to one side only fails here.</p>
     */
    @Test
    void theDuePredicateAndTheDueQueriesAgreeOnEveryStatus() {
        List<Cheque> seeded = new ArrayList<>();
        int seq = 0;
        for (ChequeStatus status : ChequeStatus.values()) {
            for (int offset : new int[]{-2, 0, 2}) {
                seq++;
                seeded.add(cheque(seq, String.format("%06d", seq), TODAY.plusDays(offset), status, ChequeMode.PDC));
            }
        }

        Set<UUID> byTheRule = seeded.stream()
                .filter(c -> ChequeDueRules.due(c, TODAY))
                .map(Cheque::getId)
                .collect(Collectors.toSet());
        assertThat(byTheRule).as("a vacuous set would make every equality below true").isNotEmpty();

        assertThat(mine(inTx(() -> cheques.findDue(null, TODAY, true, List.of(), BIG_PAGE).getContent())))
                .as("findDue — the register, the aging report and the tiles")
                .isEqualTo(byTheRule);
        assertThat(mine(dueForLease(leaseId)))
                .as("findDueForLease — the settlement preview's arrears")
                .isEqualTo(byTheRule);
        assertThat(mine(inTx(() -> cheques.findAllDue(TODAY))))
                .as("findAllDue — the overdue reminder job")
                .isEqualTo(byTheRule);
    }

    /** The ids of this test's own lease's rows, as a set. */
    private Set<UUID> mine(List<Cheque> rows) {
        return rows.stream()
                .filter(c -> c.getLease() != null && leaseId.equals(c.getLease().getId()))
                .map(Cheque::getId)
                .collect(Collectors.toSet());
    }

    /**
     * And it inherits the register's lease-status exclusion too: a lease nobody has
     * signed is a proposal, so nothing on it is owed. A settlement cannot reach this
     * state, but the two queries agreeing matters more than the state being
     * reachable — that is the whole point of pinning them together.
     */
    @Test
    void findDueForLeaseIsEmptyForADraftOrUnsignedLease() {
        cheque(1, "000001", TODAY.minusDays(1), ChequeStatus.REGISTERED, ChequeMode.PDC);
        assertThat(dueForLease(leaseId)).hasSize(1);

        Lease draft = leaseRepo.findById(leaseId).orElseThrow();
        draft.setStatus(LeaseStatus.DRAFT);
        leaseRepo.save(draft);
        assertThat(dueForLease(leaseId)).isEmpty();
        assertThat(due(null)).isEmpty();

        // Re-read: Lease is @Version'd, so the instance saved above is already stale.
        Lease pending = leaseRepo.findById(leaseId).orElseThrow();
        pending.setStatus(LeaseStatus.PENDING_SIGNATURE);
        leaseRepo.save(pending);
        assertThat(dueForLease(leaseId)).isEmpty();
        assertThat(due(null)).isEmpty();
    }

    @Test
    void search_matchesUnitNumberRenterNameAndChequeNumber() {
        seedRegister();

        // The caller passes an already-lowercased, already-wildcarded term; all three
        // of the lease's search axes hit the same three cheques.
        assertThat(search(propertyId, null, null, null, null, "%304%")).hasSize(3);
        assertThat(search(propertyId, null, null, null, null, "%prabhjot%")).hasSize(3);
        assertThat(search(propertyId, null, null, null, null, "%000002%"))
                .extracting(Cheque::getChequeNumber).containsExactly("000002");
        assertThat(search(propertyId, null, null, null, null, "%nobody%")).isEmpty();
    }

    /**
     * {@code unit_id} is nullable, so dereferencing {@code c.unit.unitNumber} inside
     * the where clause makes Hibernate emit an INNER join and quietly delete every
     * unit-less cheque from the register — and from the count the pager shows —
     * even when the caller passed no search term at all. The LEFT JOIN is what keeps
     * them visible.
     */
    @Test
    void search_keepsChequesThatHaveNoUnit() {
        seedRegister();
        Cheque unitLess = cheque(4, "000004", TODAY.minusDays(2), ChequeStatus.REGISTERED, ChequeMode.CASH);
        unitLess.setUnit(null);
        cheques.save(unitLess);

        Page<Cheque> all = searchPage(null, null, null, null, null, null);
        assertThat(all.getContent()).extracting(Cheque::getChequeNumber)
                .containsExactlyInAnyOrder("000001", "000002", "000003", "000004");
        assertThat(all.getTotalElements())
                .as("the unit-less cheque must be counted, not just listed")
                .isEqualTo(4);

        // It is still reachable by the other two search axes, and a unit-number term
        // simply does not match it.
        assertThat(search(null, null, null, null, null, "%000004%"))
                .extracting(Cheque::getChequeNumber).containsExactly("000004");
        assertThat(search(null, null, null, null, null, "%prabhjot%")).hasSize(4);
        assertThat(search(null, null, null, null, null, "%304%")).hasSize(3);
    }

    /**
     * Both worklists take a property filter that no caller exercises yet. A landlord
     * opening one building's deposit run must not be handed another building's
     * cheques.
     */
    @Test
    void dueAndDepositWorklistsScopeToTheRequestedProperty() {
        cheque(1, "000001", TODAY.minusDays(1), ChequeStatus.REGISTERED, ChequeMode.PDC);
        UUID otherPropertyId = seedSecondPropertyWithDueCheque();

        assertThat(due(propertyId)).extracting(Cheque::getChequeNumber).containsExactly("000001");
        assertThat(toDeposit(propertyId)).extracting(Cheque::getChequeNumber).containsExactly("000001");

        assertThat(due(otherPropertyId)).extracting(Cheque::getChequeNumber).containsExactly("000002");
        assertThat(toDeposit(otherPropertyId)).extracting(Cheque::getChequeNumber).containsExactly("000002");

        // Unscoped still sees the whole tenant — the filter narrows, it does not hide.
        assertThat(due(null)).hasSize(2);
        assertThat(toDeposit(null)).hasSize(2);
    }

    /** A second property in the same tenant, with its own unit, lease and due cheque. */
    private UUID seedSecondPropertyWithDueCheque() {
        Property p = new Property();
        p.setNameEn("Marina Heights");
        p.setEmirate(Emirate.DUBAI);
        Property saved = propertyRepo.save(p);

        Unit u = new Unit();
        u.setProperty(saved);
        u.setUnitNumber("1201");
        Unit savedUnit = unitRepo.save(u);

        Lease lease = new Lease();
        lease.setUnit(savedUnit);
        lease.setRenter(renterRepo.findById(renterId).orElseThrow());
        lease.setStartDate(LocalDate.of(2026, 9, 1));
        lease.setEndDate(LocalDate.of(2027, 8, 31));
        lease.setRentAmount(new BigDecimal("90000"));
        lease.setDepositAmount(new BigDecimal("5000"));
        lease.setStatus(LeaseStatus.ACTIVE);
        Lease savedLease = leaseRepo.save(lease);

        Cheque c = new Cheque();
        c.setLease(savedLease);
        c.setProperty(saved);
        c.setUnit(savedUnit);
        c.setRenter(renterRepo.findById(renterId).orElseThrow());
        c.setSeqNo(1);
        c.setPostingDate(LocalDate.of(2026, 9, 1));
        c.setChequeNumber("000002");
        c.setChequeDate(TODAY.minusDays(1));
        c.setAmount(new BigDecimal("22500"));
        c.setStatus(ChequeStatus.REGISTERED);
        c.setMode(ChequeMode.PDC);
        cheques.save(c);
        return saved.getId();
    }

    @Test
    void search_appliesStatusModeAndDateFilters() {
        seedRegister();

        assertThat(search(null, ChequeStatus.CLEARED, null, null, null, null))
                .extracting(Cheque::getChequeNumber).containsExactly("000003");
        assertThat(search(null, null, ChequeMode.CASH, null, null, null)).isEmpty();
        assertThat(search(null, null, null, TODAY, null, null))
                .extracting(Cheque::getChequeNumber).containsExactly("000002");
        assertThat(search(null, null, null, null, TODAY, null))
                .extracting(Cheque::getChequeNumber).containsExactlyInAnyOrder("000001", "000003");
        assertThat(search(UUID.randomUUID(), null, null, null, null, null)).isEmpty();
        // No filters at all is the unfiltered register, not an empty one.
        assertThat(search(null, null, null, null, null, null)).hasSize(3);
    }

    @Test
    void registerHelpersCountAndSumOverTheLease() {
        seedRegister();
        Cheque bounced = cheque(4, "000004", TODAY.minusDays(3), ChequeStatus.BOUNCED, ChequeMode.PDC);
        bounced.setBouncedAt(TODAY.minusDays(2));
        cheques.save(bounced);
        Cheque cleared = inTx(() -> cheques.findByLease_IdOrderBySeqNoAsc(leaseId)).get(2);
        cleared.setClearedAt(TODAY.minusMonths(1));
        cheques.save(cleared);

        assertThat(inTx(() -> cheques.findByLease_IdOrderBySeqNoAsc(leaseId)))
                .extracting(Cheque::getSeqNo).containsExactly(1, 2, 3, 4);
        assertThat(inTx(() -> cheques.countByLease_IdAndStatusIn(leaseId, List.of(ChequeStatus.REGISTERED))))
                .isEqualTo(2);
        assertThat(inTx(() -> cheques.countByLease_IdAndBouncedAtIsNotNull(leaseId))).isEqualTo(1);
        assertThat(inTx(() -> cheques.findByRenter_IdAndStatusInOrderByChequeDateAsc(
                renterId, List.of(ChequeStatus.REGISTERED, ChequeStatus.BOUNCED))))
                .extracting(Cheque::getChequeNumber).containsExactly("000004", "000001", "000002");
        assertThat(inTx(() -> cheques.sumClearedBetween(TODAY.minusMonths(2), TODAY, null, true, List.of())))
                .isEqualByComparingTo("13700");
        // The window's upper bound is exclusive, and an empty window coalesces to zero
        // rather than returning null into an arithmetic caller.
        assertThat(inTx(() -> cheques.sumClearedBetween(TODAY, TODAY.plusDays(1), null, true, List.of())))
                .isEqualByComparingTo("0");
        assertThat(inTx(() -> cheques.sumClearedBetween(TODAY.minusMonths(2), TODAY.minusMonths(1), null, true, List.of())))
                .isEqualByComparingTo("0");
    }

    /** The retention purge only looks at rows that still hold a blob. */
    @Test
    void findImagePurgeBatch_picksOnlyRowsStillHoldingABlob() {
        Cheque withImage = cheque(1, "000001", LocalDate.of(2020, 1, 1), ChequeStatus.CLEARED, ChequeMode.PDC);
        withImage.setImageBlobPath("cheques/000001.jpg");
        cheques.save(withImage);
        cheque(2, "000002", LocalDate.of(2020, 1, 1), ChequeStatus.CLEARED, ChequeMode.PDC);

        assertThat(inTx(() -> cheques.findImagePurgeBatch(LocalDate.of(2021, 1, 1), PAGE)))
                .extracting(ChequeImagePurgeRow::chequeImageBlobPath)
                .containsExactly("cheques/000001.jpg");
        assertThat(inTx(() -> cheques.findImagePurgeBatch(LocalDate.of(2019, 1, 1), PAGE))).isEmpty();
    }

    /**
     * Two callers deciding the same cheque's fate (one clearing it, one marking it
     * bounced) must not both pass their precondition guard: the row lock is what
     * serializes them. NOWAIT means the loser fails immediately rather than queuing
     * behind a holder that may be stuck.
     */
    @Test
    void findByIdForUpdate_secondCallerFailsImmediatelyRatherThanWaiting() throws Exception {
        UUID chequeId = cheque(1, "000001", TODAY.minusDays(1), ChequeStatus.REGISTERED, ChequeMode.PDC).getId();

        CountDownLatch lockHeld = new CountDownLatch(1);
        long holdMillis = 3000;
        ExecutorService pool = Executors.newFixedThreadPool(1);
        Future<?> holder = pool.submit(holdLockOn(chequeId, lockHeld, holdMillis));

        try {
            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            assertThatThrownBy(() -> inTx(() -> cheques.findByIdForUpdate(chequeId)))
                    .isInstanceOf(PessimisticLockingFailureException.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMs)
                    .as("NOWAIT should fail immediately, not block until the holder releases")
                    .isLessThan(holdMillis);
        } finally {
            holder.get(15, TimeUnit.SECONDS);
            pool.shutdown();
        }
    }

    /**
     * One contended row fails the whole batch. A bulk deposit run that got a
     * half-locked set would mutate the rows it did win while another caller was
     * mutating the one it lost.
     */
    @Test
    void findAllByIdForUpdate_lockingIsAllOrNothingAcrossTheBatch() throws Exception {
        UUID first = cheque(1, "000001", TODAY.minusDays(1), ChequeStatus.REGISTERED, ChequeMode.PDC).getId();
        UUID second = cheque(2, "000002", TODAY, ChequeStatus.REGISTERED, ChequeMode.PDC).getId();

        CountDownLatch lockHeld = new CountDownLatch(1);
        long holdMillis = 3000;
        ExecutorService pool = Executors.newFixedThreadPool(1);
        Future<?> holder = pool.submit(holdLockOn(second, lockHeld, holdMillis));

        try {
            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> inTx(() -> cheques.findAllByIdForUpdate(List.of(first, second))))
                    .isInstanceOf(PessimisticLockingFailureException.class);
        } finally {
            holder.get(15, TimeUnit.SECONDS);
            pool.shutdown();
        }
    }

    /** Takes the row lock in its own transaction and holds it for {@code holdMillis}. */
    private Runnable holdLockOn(UUID chequeId, CountDownLatch lockHeld, long holdMillis) {
        return () -> {
            TenantContextHolder.setTenantId(tenantId);
            try {
                transactionTemplate.execute(status -> {
                    cheques.findByIdForUpdate(chequeId).orElseThrow();
                    lockHeld.countDown();
                    try {
                        Thread.sleep(holdMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } finally {
                TenantContextHolder.clear();
            }
        };
    }

    /**
     * The register queries are JPQL, so the Hibernate tenant filter scopes them — but
     * only if {@link Cheque} really extends the filtered superclass. Another
     * landlord's cheques must be invisible, not merely unlikely to appear.
     */
    @Test
    void registerQueriesAreScopedToTheCallersTenant() {
        seedRegister();

        LandlordOrg other = new LandlordOrg();
        other.setName("CHQ-other-" + UUID.randomUUID());
        TenantContextHolder.setTenantId(orgRepo.save(other).getId());

        assertThat(due(null)).isEmpty();
        assertThat(search(null, null, null, null, null, null)).isEmpty();
        assertThat(inTx(() -> cheques.findByLease_IdOrderBySeqNoAsc(leaseId))).isEmpty();
        assertThat(inTx(() -> cheques.sumClearedBetween(TODAY.minusYears(1), TODAY.plusYears(1), null, true, List.of())))
                .isEqualByComparingTo("0");
    }
}
