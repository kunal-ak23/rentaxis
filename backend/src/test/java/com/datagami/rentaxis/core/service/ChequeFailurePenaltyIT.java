package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PaymentScheduleDTO;
import com.datagami.rentaxis.api.dto.UpdatePaymentStatusDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseEvent;
import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration test for the cheque-failure penalty flow.
 *
 * <p>Stands up a real Postgres via Testcontainers, runs Liquibase migrations,
 * and drives {@link PaymentScheduleService#markFailed} through the full
 * orchestrator → @Transactional → JSONB → tenant-filter chain. Asserts
 * persisted state across {@link PaymentSchedule}, {@link PaymentPenalty},
 * and {@link LeaseEvent}.</p>
 *
 * <p>Requires Docker on the host.</p>
 */
@SpringBootTest
@Testcontainers
class ChequeFailurePenaltyIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired PaymentScheduleService paymentScheduleService;
    @Autowired PaymentPenaltyRepository paymentPenaltyRepository;
    @Autowired PaymentScheduleRepository paymentScheduleRepository;
    @Autowired LeaseEventRepository leaseEventRepository;
    @Autowired LandlordOrgRepository landlordOrgRepository;
    @Autowired PropertyRepository propertyRepository;
    @Autowired UnitRepository unitRepository;
    @Autowired RenterRepository renterRepository;
    @Autowired LeaseRepository leaseRepository;
    @Autowired RentCollectionSettingsRepository rentCollectionSettingsRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionTemplate transactionTemplate;

    private UUID tenantId;
    private Property property;
    private Unit unit;
    private Renter renter;
    private Lease lease;
    private PaymentSchedule depositedSchedule;

    @BeforeEach
    void setUp() {
        // Each test gets its own tenant. Multi-tenant isolation in the schema
        // means we don't need DB cleanup between tests — repository reads are
        // already scoped by TenantContextHolder.
        LandlordOrg org = new LandlordOrg();
        org.setName("IT-Tenant-" + UUID.randomUUID());
        org = landlordOrgRepository.save(org);
        this.tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);

        // Seed accounts required by recordChequeBounce: C-01-01 (income) +
        // A-02-02 (bank). Account mappings are not seeded so the service
        // falls through to the hardcoded chart-of-accounts codes.
        seedAccount("C-01-01", "Rental Income", AccountType.INCOME);
        seedAccount("A-02-02", "Bank Accounts", AccountType.ASSET);

        // Seed fixtures: property → unit → renter → lease → DEPOSITED schedule.
        property = new Property();
        property.setNameEn("IT-Property");
        property.setEmirate(Emirate.DUBAI);
        property = propertyRepository.save(property);

        unit = new Unit();
        unit.setProperty(property);
        unit.setUnitNumber("U-1");
        unit = unitRepository.save(unit);

        renter = new Renter();
        renter.setNameEn("IT-Renter");
        // Leave userId null — there is a FK constraint on renters.user_id and we
        // don't seed a User row in this slice. The markFailed / replacement
        // notifications guard against null userId, so tolerable for this IT.
        renter = renterRepository.save(renter);

        lease = new Lease();
        lease.setUnit(unit);
        lease.setRenter(renter);
        lease.setStartDate(LocalDate.now().minusMonths(1));
        lease.setEndDate(LocalDate.now().plusMonths(11));
        lease.setStatus(LeaseStatus.ACTIVE);
        lease.setRentAmount(new BigDecimal("60000"));
        lease.setMonthlyRent(new BigDecimal("5000"));
        lease = leaseRepository.save(lease);

        depositedSchedule = newSchedule(PaymentStatus.DEPOSITED, 1);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void markFailed_bounce_persistsScheduleStateAndPenaltyAndAuditEvent() {
        UUID scheduleId = depositedSchedule.getId();

        paymentScheduleService.markFailed(scheduleId, ChequeFailureReason.BOUNCE, "test bounce");

        PaymentSchedule updated = paymentScheduleRepository.findById(scheduleId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PaymentStatus.BOUNCED);
        assertThat(updated.getFailureReason()).isEqualTo(ChequeFailureReason.BOUNCE);
        assertThat(updated.getNotes()).isEqualTo("test bounce");

        List<PaymentPenalty> penalties = paymentPenaltyRepository.findByLeaseIdOrderByCreatedAtAsc(lease.getId());
        assertThat(penalties).hasSize(1);
        PaymentPenalty p = penalties.get(0);
        assertThat(p.getPenaltyType()).isEqualTo("CHEQUE_FAILURE");
        assertThat(p.getPenaltyAmount()).isEqualByComparingTo("500"); // org default for BOUNCE
        assertThat(p.getDaysOverdue()).isEqualTo(0);
        assertThat(p.getFineGraceDays()).isEqualTo(7);
        assertThat(p.getFinePerDayRate()).isEqualByComparingTo("25");
        assertThat(p.getClearedAt()).isNull();

        // Ledger posting moves to PostingService in accounting v2 plan 2/3 (see spec §7/§9);
        // what markFailed still owns is the status transition and the penalty row above.

        List<LeaseEvent> events = leaseEventRepository.findByLeaseIdOrderByCreatedAtDesc(lease.getId());
        boolean hasFailedEvent = events.stream()
                .anyMatch(e -> e.getNotes() != null && e.getNotes().contains("PAYMENT_FAILED_BOUNCE"));
        assertThat(hasFailedEvent).isTrue();
    }

    /**
     * Regression test for the "rent received even though the cheque bounced"
     * bug: {@link PaymentScheduleService#clearPayment} and {@link
     * PaymentScheduleService#markFailed} both read-check-write the same
     * {@code DEPOSITED} guard with no row lock. Two requests racing on the
     * same schedule (e.g. a double-click, or a staff member clicking "Mark
     * Failed" right as another tab's "Clear" is in flight) could both
     * observe {@code DEPOSITED}, both pass their precondition, and both post
     * financial transactions — a "Rental income" credit AND a "Cheque
     * bounced" reversal for the same cheque.
     *
     * <p>With {@code findByIdForUpdate}'s pessimistic write lock, the second
     * caller blocks until the first commits, re-reads the now-updated
     * status, and is correctly rejected by the guard. Exactly one side's
     * transaction pair must exist afterward — never both.</p>
     */
    @Test
    void clearAndMarkFailed_concurrentRace_onlyOneTransitionPersistsTransactions() throws Exception {
        // clearPayment's fallback resolves the bank leg to A-02-02, already
        // seeded in setUp() alongside C-01-01 for the bounce reversal. It used
        // to look up A-01-01, a code the real seeder never creates, so this
        // test had to hand-seed it — which is precisely what hid the production
        // failure this assertion now covers.

        UUID scheduleId = depositedSchedule.getId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> clearFuture = pool.submit(raceTask(ready, go,
                    () -> paymentScheduleService.clearPayment(scheduleId, new UpdatePaymentStatusDTO())));
            Future<Throwable> markFailedFuture = pool.submit(raceTask(ready, go,
                    () -> paymentScheduleService.markFailed(scheduleId, ChequeFailureReason.BOUNCE, "race test")));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            Throwable clearOutcome = clearFuture.get(15, TimeUnit.SECONDS);
            Throwable markFailedOutcome = markFailedFuture.get(15, TimeUnit.SECONDS);

            boolean clearWon = clearOutcome == null;
            boolean markFailedWon = markFailedOutcome == null;

            assertThat(clearWon)
                    .as("exactly one of clear/markFailed must win the race on schedule %s; "
                            + "clearOutcome=%s markFailedOutcome=%s", scheduleId, clearOutcome, markFailedOutcome)
                    .isNotEqualTo(markFailedWon);
            if (!clearWon) {
                assertThat(clearOutcome).isInstanceOf(BusinessRuleViolationException.class);
            }
            if (!markFailedWon) {
                assertThat(markFailedOutcome).isInstanceOf(BusinessRuleViolationException.class);
            }

            // v1 asserted which financial_transactions each side posted. Posting
            // moves to PostingService in accounting v2 plan 2/3 (see spec §7/§9),
            // so the race invariant is now read off the schedule's own status:
            // exactly one transition is persisted, never both.
            PaymentSchedule afterRace = paymentScheduleRepository.findById(scheduleId).orElseThrow();
            assertThat(afterRace.getStatus())
                    .as("the winning transition is the one persisted")
                    .isEqualTo(clearWon ? PaymentStatus.CLEARED : PaymentStatus.BOUNCED);
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Regression test for the dead catch block that previously caught
     * {@link jakarta.persistence.PessimisticLockException} in {@code
     * lockAndRequireStatus} — Spring Data JPA's exception translation
     * converts the Postgres NOWAIT lock-conflict SQLSTATE (55P03) into
     * {@link org.springframework.dao.PessimisticLockingFailureException}
     * before it reaches service code, so the old catch clause never actually
     * fired and the raw exception escaped as an unhandled 500.
     *
     * <p>Holds the row's pessimistic lock open in a separate
     * thread/transaction (via {@link TransactionTemplate}, released on its
     * own fixed timer independent of whatever the main thread does) and
     * asserts a concurrent {@code clearPayment} call fails immediately with
     * the friendly error rather than blocking. An earlier version of
     * {@code findByIdForUpdate} used a 5s {@code jakarta.persistence.lock.timeout}
     * (a bounded wait) instead of NOWAIT — that mechanism turned out not to
     * be honored by this Hibernate/Postgres/HikariCP stack for row-lock
     * waits (verified empirically: holding the lock for 8s/20s/30s in
     * separate runs, a concurrent caller blocked for the *entire* hold
     * duration every time rather than timing out at 5s), which is why NOWAIT
     * — a query-level clause Postgres enforces directly — replaced it.
     */
    @Test
    void clearPayment_rowAlreadyLocked_failsImmediatelyWithBusinessRuleViolation() throws Exception {
        UUID scheduleId = depositedSchedule.getId();

        CountDownLatch lockHeld = new CountDownLatch(1);
        long holdMillis = 3000;

        ExecutorService pool = Executors.newFixedThreadPool(1);
        Future<?> holderFuture = pool.submit(() -> {
            TenantContextHolder.setTenantId(tenantId);
            try {
                transactionTemplate.execute(status -> {
                    paymentScheduleRepository.findByIdForUpdate(scheduleId).orElseThrow();
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
        });

        try {
            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            assertThatThrownBy(() ->
                    paymentScheduleService.clearPayment(scheduleId, new UpdatePaymentStatusDTO()))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("currently being updated by another request");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            // NOWAIT must fail near-instantly — well before the holder's own
            // hold ends — proving this is a real conflict failure, not a
            // race that just happens to resolve in our favor.
            assertThat(elapsedMs)
                    .as("NOWAIT should fail immediately, not block until the holder releases")
                    .isLessThan(holdMillis);
        } finally {
            holderFuture.get(15, TimeUnit.SECONDS);
            pool.shutdown();
        }

        // The lock holder never mutated the row, so the schedule is untouched.
        PaymentSchedule reloaded = paymentScheduleRepository.findById(scheduleId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.DEPOSITED);
    }

    /**
     * Regression test for the sibling race in {@link PaymentScheduleService#replacePayment}
     * — same unlocked read-check-write shape as the clear/mark-failed race
     * above, but on the {@code BOUNCED} guard. Left unfixed, two concurrent
     * replace calls on the same bounced cheque could both pass the guard and
     * both persist a new PENDING replacement schedule, silently orphaning
     * one of them (never referenced by {@code replacedBy}, but still a real
     * rent obligation sitting in the DB). Exactly one replacement must
     * persist afterward.
     */
    @Test
    void replacePayment_concurrentRace_onlyOneReplacementPersists() throws Exception {
        paymentScheduleService.markFailed(depositedSchedule.getId(), ChequeFailureReason.BOUNCE, "to replace (race)");
        UUID scheduleId = depositedSchedule.getId();

        UpdatePaymentStatusDTO dtoA = new UpdatePaymentStatusDTO();
        dtoA.setChequeNumber("REPLACE-A");
        dtoA.setBankName("Bank-A");
        dtoA.setPayerName("IT-Renter");
        dtoA.setChequeDate(LocalDate.now().plusDays(7));

        UpdatePaymentStatusDTO dtoB = new UpdatePaymentStatusDTO();
        dtoB.setChequeNumber("REPLACE-B");
        dtoB.setBankName("Bank-B");
        dtoB.setPayerName("IT-Renter");
        dtoB.setChequeDate(LocalDate.now().plusDays(7));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> replaceAFuture = pool.submit(raceTask(ready, go,
                    () -> paymentScheduleService.replacePayment(scheduleId, dtoA)));
            Future<Throwable> replaceBFuture = pool.submit(raceTask(ready, go,
                    () -> paymentScheduleService.replacePayment(scheduleId, dtoB)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            Throwable outcomeA = replaceAFuture.get(15, TimeUnit.SECONDS);
            Throwable outcomeB = replaceBFuture.get(15, TimeUnit.SECONDS);

            boolean aWon = outcomeA == null;
            boolean bWon = outcomeB == null;
            assertThat(aWon)
                    .as("exactly one replace call must win the race on schedule %s; outcomeA=%s outcomeB=%s",
                            scheduleId, outcomeA, outcomeB)
                    .isNotEqualTo(bWon);
            Throwable losingOutcome = aWon ? outcomeB : outcomeA;
            assertThat(losingOutcome).isInstanceOf(BusinessRuleViolationException.class);

            List<PaymentSchedule> leaseSchedules = paymentScheduleRepository.findByLeaseId(lease.getId());
            List<PaymentSchedule> pendingReplacements = leaseSchedules.stream()
                    .filter(ps -> ps.getStatus() == PaymentStatus.PENDING)
                    .filter(ps -> "REPLACE-A".equals(ps.getChequeNumber()) || "REPLACE-B".equals(ps.getChequeNumber()))
                    .toList();
            assertThat(pendingReplacements)
                    .as("exactly one replacement schedule should be persisted, not one per racer")
                    .hasSize(1);

            PaymentSchedule oldReloaded = paymentScheduleRepository.findById(scheduleId).orElseThrow();
            assertThat(oldReloaded.getReplacedBy()).isNotNull();
            assertThat(oldReloaded.getReplacedBy().getId()).isEqualTo(pendingReplacements.get(0).getId());
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Wraps a racing transition call with the countdown-latch synchronization
     * (both tasks signal ready, then wait for the shared {@code go} release
     * so they hit the DB at the same instant) and tenant-context setup/teardown.
     * Shared by every race test so the harness plumbing lives in one place.
     */
    private Callable<Throwable> raceTask(CountDownLatch ready, CountDownLatch go, Runnable action) {
        return () -> {
            TenantContextHolder.setTenantId(tenantId);
            try {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                action.run();
                return null;
            } catch (Throwable t) {
                return t;
            } finally {
                TenantContextHolder.clear();
            }
        };
    }

    @Test
    void markFailed_signatureMismatch_appliesCorrectFineAmount() {
        UUID scheduleId = depositedSchedule.getId();

        paymentScheduleService.markFailed(scheduleId, ChequeFailureReason.SIGNATURE_MISMATCH, "sig mismatch");

        List<PaymentPenalty> penalties = paymentPenaltyRepository.findByLeaseIdOrderByCreatedAtAsc(lease.getId());
        assertThat(penalties).hasSize(1);
        assertThat(penalties.get(0).getPenaltyAmount()).isEqualByComparingTo("500");
    }

    @Test
    void markFailed_accountClosed_appliesCorrectFineAmount() {
        UUID scheduleId = depositedSchedule.getId();

        paymentScheduleService.markFailed(scheduleId, ChequeFailureReason.ACCOUNT_CLOSED, "closed");

        List<PaymentPenalty> penalties = paymentPenaltyRepository.findByLeaseIdOrderByCreatedAtAsc(lease.getId());
        assertThat(penalties).hasSize(1);
        assertThat(penalties.get(0).getPenaltyAmount()).isEqualByComparingTo("1000");
    }

    @Test
    void markFailed_propertyOverride_appliesOverriddenAmount() {
        // Property-level override: bounce fine 750 instead of org default 500.
        RentCollectionSettings rcs = new RentCollectionSettings();
        rcs.setProperty(property);
        rcs.setFineBounceAmount(new BigDecimal("750"));
        rentCollectionSettingsRepository.save(rcs);

        paymentScheduleService.markFailed(depositedSchedule.getId(), ChequeFailureReason.BOUNCE, "override test");

        List<PaymentPenalty> penalties = paymentPenaltyRepository.findByLeaseIdOrderByCreatedAtAsc(lease.getId());
        assertThat(penalties).hasSize(1);
        assertThat(penalties.get(0).getPenaltyAmount()).isEqualByComparingTo("750");
    }

    @Test
    void markFailed_pendingSchedule_throwsAndLeavesStateUntouched() {
        PaymentSchedule pending = newSchedule(PaymentStatus.PENDING, 2);

        assertThatThrownBy(() ->
                paymentScheduleService.markFailed(pending.getId(), ChequeFailureReason.BOUNCE, null))
                .isInstanceOf(BusinessRuleViolationException.class);

        PaymentSchedule reloaded = paymentScheduleRepository.findById(pending.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reloaded.getFailureReason()).isNull();

        List<PaymentPenalty> penalties = paymentPenaltyRepository.findByLeaseIdOrderByCreatedAtAsc(lease.getId());
        assertThat(penalties).isEmpty();
    }

    @Test
    void replacementCheque_afterMarkFailed_preservesPenalty() {
        UUID originalId = depositedSchedule.getId();
        paymentScheduleService.markFailed(originalId, ChequeFailureReason.BOUNCE, "to replace");

        // Snapshot the open penalty before replacing.
        PaymentPenalty before = paymentPenaltyRepository
                .findByLeaseIdOrderByCreatedAtAsc(lease.getId())
                .get(0);
        BigDecimal originalAmount = before.getPenaltyAmount();

        UpdatePaymentStatusDTO replaceDto = new UpdatePaymentStatusDTO();
        replaceDto.setChequeNumber("REPLACE-001");
        replaceDto.setBankName("Bank-IT");
        replaceDto.setPayerName("IT-Renter");
        replaceDto.setChequeDate(LocalDate.now().plusDays(7));
        PaymentScheduleDTO replacement = paymentScheduleService.replacePayment(originalId, replaceDto);

        assertThat(replacement).isNotNull();
        assertThat(replacement.getStatus()).isEqualTo(PaymentStatus.PENDING);

        PaymentSchedule original = paymentScheduleRepository.findById(originalId).orElseThrow();
        assertThat(original.getReplacedBy()).isNotNull();
        assertThat(original.getReplacedBy().getId()).isEqualTo(replacement.getId());

        // Penalty must be unchanged: same amount and still open.
        PaymentPenalty after = paymentPenaltyRepository.findById(before.getId()).orElseThrow();
        assertThat(after.getPenaltyAmount()).isEqualByComparingTo(originalAmount);
        assertThat(after.getClearedAt()).isNull();
    }

    private void seedAccount(String code, String name, AccountType type) {
        Account a = new Account();
        a.setCode(code);
        a.setName(name);
        a.setAccountType(type);
        accountRepository.save(a);
    }

    private PaymentSchedule newSchedule(PaymentStatus status, int installmentNumber) {
        PaymentSchedule s = new PaymentSchedule();
        s.setLease(lease);
        s.setUnit(unit);
        s.setProperty(property);
        s.setInstallmentNumber(installmentNumber);
        s.setDueDate(LocalDate.now());
        s.setAmount(new BigDecimal("5000"));
        s.setStatus(status);
        return paymentScheduleRepository.save(s);
    }
}
