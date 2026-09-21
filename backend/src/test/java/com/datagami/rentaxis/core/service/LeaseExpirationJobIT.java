package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.lease.LeaseRenewalService;
import com.datagami.rentaxis.core.service.lease.LeaseTerminationService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseEvent;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The nightly expiry sweep: a tenancy whose end date has passed becomes EXPIRED
 * and gives its unit back (spec §9, lease status notes).
 *
 * <p><b>Expiry is a calendar fact and posts nothing.</b> It does not touch the
 * cheque register — an instrument dated before the end of the term is money the
 * renter genuinely owes, and a lease reaching its last day does not settle it —
 * and it does not touch the recognition schedule, whose rows complete on their
 * own through the month-end close. What happens to uncleared paper is decided by
 * a human in the termination and settlement flow.</p>
 *
 * <p><b>Per tenant, in a loop, with the context set each time</b> — the same shape
 * as {@code RevenueRecognitionJob} and for the same reason: the Hibernate tenant
 * filter is switched on by {@code TenantAspect} from {@code TenantContextHolder},
 * and this job has no HTTP request behind it to have populated one. A pass made
 * without setting it would run every organisation's leases through whichever
 * tenant happened to be current. {@link #aTenantsSweepDoesNotTouchAnotherTenant}
 * is the proof.</p>
 *
 * <p><b>The clock is fixed at 2027-10-01</b> through a {@code @Primary}
 * {@link Clock} bean — a week after the fixture's term ends — because "today" is
 * the job's only input.</p>
 */
@SpringBootTest
@Testcontainers
@Import(LeaseExpirationJobIT.FixedClockConfig.class)
class LeaseExpirationJobIT {

    /** A week after the fixture lease's 23 Sep 2027 end date. */
    static final LocalDate TODAY = LocalDate.of(2027, 10, 1);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).plusHours(3).toInstant(), ZoneOffset.UTC);
        }
    }

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired LeaseExpirationJob job;
    @Autowired LeaseService leaseService;
    @Autowired LeaseTerminationService termination;
    @Autowired LeaseRenewalService renewal;
    @Autowired RecognitionService recognition;
    @Autowired ChequeGenerationService cheques;
    @Autowired LeasePostingService posting;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LeaseRepository leaseRepo;
    @Autowired LeaseEventRepository leaseEvents;
    @Autowired ChequeRepository chequeRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired Clock clock;

    /** The client's fixture (spec §8.2), whose term is over by {@link #TODAY}. */
    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 9, 24);
    private static final LocalDate END = LocalDate.of(2027, 9, 23);

    private LeaseTestFixtures alpha;
    private LeaseTestFixtures beta;
    private UUID alphaLease;
    private UUID betaLease;

    @BeforeEach
    void setUp() {
        alpha = newTenant();
        alphaLease = postGalahLease(alpha);
        beta = newTenant();
        betaLease = postGalahLease(beta);
        assertThat(alpha.tenantId()).isNotEqualTo(beta.tenantId());
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private LeaseTestFixtures newTenant() {
        return new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, cheques, posting);
    }

    /** 51,000 of rent over the client's 365-day term plus a 2,000 admin fee, on the books. */
    private UUID postGalahLease(LeaseTestFixtures f) {
        return f.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, "100040")
                .lease().getId();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Read something as a given tenant, with no authenticated caller — as the job runs. */
    private <T> T as(UUID tenantId, Supplier<T> read) {
        TenantContextHolder.setTenantId(tenantId);
        try {
            return read.get();
        } finally {
            TenantContextHolder.clear();
        }
    }

    private Lease lease(UUID leaseId) {
        return tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow());
    }

    private LeaseStatus statusOf(UUID leaseId) {
        return lease(leaseId).getStatus();
    }

    private Unit unitOf(UUID leaseId) {
        return tx.execute(s -> unitRepo.findById(lease(leaseId).getUnit().getId()).orElseThrow());
    }

    private List<Cheque> register(UUID leaseId) {
        return tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId));
    }

    private List<LeaseEvent> eventsOf(UUID leaseId) {
        return tx.execute(s -> leaseEvents.findByLeaseIdOrderByCreatedAtDesc(leaseId));
    }

    private long journalCount(UUID tenantId) {
        return jdbc.queryForObject(
                "select count(*) from journal_entries where tenant_id = ?", Long.class, tenantId);
    }

    /** Events whose tenant does not match the lease they describe — the cross-tenant leak. */
    private long misfiledEvents() {
        return jdbc.queryForObject("select count(*) from lease_events e "
                + "join leases l on l.id = e.lease_id where e.tenant_id <> l.tenant_id", Long.class);
    }

    // ------------------------------------------------------------------

    /**
     * The sweep, for both landlords: the contract goes EXPIRED, the unit comes back
     * on the market, and the lease's trail says who did it — with nothing posted and
     * no register row touched.
     */
    @Test
    void aTermThatHasRunOutExpiresAndGivesTheUnitBack() {
        long alphaJournals = journalCount(alpha.tenantId());
        long betaJournals = journalCount(beta.tenantId());

        LeaseExpirationJob.ExpiryRun run = job.runFor(TODAY);

        assertThat(run.expired()).as("one lease each").isEqualTo(2);
        assertThat(run.skipped()).isZero();
        assertThat(run.failed()).isZero();
        for (UUID leaseId : List.of(alphaLease, betaLease)) {
            assertThat(statusOf(leaseId)).isEqualTo(LeaseStatus.EXPIRED);
            Unit unit = unitOf(leaseId);
            assertThat(unit.getStatus()).isEqualTo(UnitStatus.VACANT);
            assertThat(unit.getCurrentTenantName()).isNull();
            assertThat(unit.getActualRent()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(eventsOf(leaseId))
                    .filteredOn(e -> e.getNewState() == LeaseStatus.EXPIRED)
                    .singleElement()
                    .satisfies(e -> assertThat(e.getPreviousState()).isEqualTo(LeaseStatus.ACTIVE));
        }
        assertThat(journalCount(alpha.tenantId())).as("expiry posts nothing").isEqualTo(alphaJournals);
        assertThat(journalCount(beta.tenantId())).isEqualTo(betaJournals);
        assertThat(misfiledEvents()).as("no event filed under another landlord").isZero();
        assertThat(TenantContextHolder.getTenantId()).as("the context is not left behind").isNull();
    }

    /**
     * The register is left exactly as it was — the P0 this job used to get wrong in
     * the one direction that costs the landlord money.
     *
     * <p>A cheque dated inside the term that nobody banked is an instrument against
     * a debt the renter owes, and the term ending does not settle it. Cancelling it
     * on the way past would reverse its {@code PDR} and quietly write the money off.
     * The recognition schedule is left alone for the mirror-image reason: its rows
     * are earned rent and the month-end close posts them on their own.</p>
     */
    @Test
    void anExpiredLeaseKeepsItsChequesAndItsSchedule() {
        List<Cheque> before = register(alphaLease);
        assertThat(before).as("four rent instalments and the admin fee, all registered").hasSize(5)
                .allSatisfy(c -> assertThat(c.getStatus()).isEqualTo(ChequeStatus.REGISTERED));
        List<RecognitionEntryDTO> scheduleBefore = as(alpha.tenantId(), () -> recognition.scheduleFor(alphaLease));

        job.runFor(TODAY);

        assertThat(statusOf(alphaLease)).isEqualTo(LeaseStatus.EXPIRED);
        assertThat(register(alphaLease)).hasSize(5)
                .allSatisfy(c -> {
                    assertThat(c.getStatus()).as("still collectable").isEqualTo(ChequeStatus.REGISTERED);
                    assertThat(c.getPdrJournalId()).as("its registration is untouched").isNotNull();
                });
        List<RecognitionEntryDTO> scheduleAfter = as(alpha.tenantId(), () -> recognition.scheduleFor(alphaLease));
        assertThat(scheduleAfter).hasSameSizeAs(scheduleBefore)
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(RecognitionStatus.PLANNED));
    }

    /**
     * One landlord's sweep is one landlord's: the other's overdue lease is not its
     * business, even though the query behind it has no tenant column of its own.
     *
     * <p>This is the P0. Without the context the JPQL is unfiltered and returns
     * every organisation's leases, so a sweep would expire a landlord whose turn it
     * was not — writing a status change, a lease event and a unit release into books
     * nobody asked it to touch.</p>
     */
    @Test
    void aTenantsSweepDoesNotTouchAnotherTenant() {
        int expired = job.runTenant(alpha.tenantId(), TODAY);

        assertThat(expired).isEqualTo(1);
        assertThat(statusOf(alphaLease)).isEqualTo(LeaseStatus.EXPIRED);

        assertThat(statusOf(betaLease)).as("the other landlord's lease is untouched")
                .isEqualTo(LeaseStatus.ACTIVE);
        assertThat(unitOf(betaLease).getStatus()).isEqualTo(UnitStatus.OCCUPIED);
        assertThat(eventsOf(betaLease))
                .as("and it has no expiry event")
                .noneSatisfy(e -> assertThat(e.getNewState()).isEqualTo(LeaseStatus.EXPIRED));
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    /**
     * A lease whose successor is already on the books is RENEWED, not expired
     * (spec §6.6).
     *
     * <p>Both statuses mean "this contract is over", and only one of them says what
     * happened to the unit and the deposit. Flipping a RENEWED predecessor to
     * EXPIRED would lose the distinction the renewal chain is built on — and would
     * release a unit the successor is holding.</p>
     */
    @Test
    void aRenewedLeaseIsNotExpired() {
        UUID successor = as(alpha.tenantId(), () -> {
            LeaseTestFixtures.authenticateAsTenantAdmin();
            UUID id = renewal.renew(alphaLease, new RenewLeaseRequest(
                    END.minusDays(14), END.plusDays(1), END.plusYears(1), null, false)).getId();
            alpha.generateGrid(id, 4, END.plusDays(1));
            posting.post(id);
            return id;
        });
        LeaseTestFixtures.clearAuth();
        assertThat(successor).isNotNull();
        assertThat(statusOf(alphaLease)).as("posting the successor retires the predecessor")
                .isEqualTo(LeaseStatus.RENEWED);

        LeaseExpirationJob.ExpiryRun run = job.runFor(TODAY);

        assertThat(statusOf(alphaLease)).isEqualTo(LeaseStatus.RENEWED);
        assertThat(run.expired()).as("only the other landlord's").isEqualTo(1);
        assertThat(run.skipped()).as("a RENEWED lease is never even a candidate").isZero();
        assertThat(run.failed()).isZero();
    }

    /**
     * A terminated lease is finished with, and a half-terminated one is nobody's to
     * finish.
     *
     * <p>Two rules, two shapes. The first is the ordinary one: a contract ended
     * early is TERMINATED and the sweep's status filter passes it by. The second is
     * the belt to that brace — a row that is still ACTIVE but already carries a
     * {@code terminated_on} is a termination somebody is in the middle of, and
     * expiring it would stamp a second ending on a contract that already has one and
     * release a unit the termination has not finished with.</p>
     */
    @Test
    void neitherATerminatedLeaseNorAHalfTerminatedOneIsExpired() {
        as(alpha.tenantId(), () -> {
            LeaseTestFixtures.authenticateAsTenantAdmin();
            return termination.terminate(alphaLease,
                    new TerminateLeaseRequest(LocalDate.of(2027, 6, 30), null, null, "Early move-out"), null);
        });
        LeaseTestFixtures.clearAuth();
        assertThat(statusOf(alphaLease)).isEqualTo(LeaseStatus.TERMINATED);

        // The drift case: an ACTIVE row carrying a termination date.
        jdbc.update("update leases set terminated_on = ? where id = ?",
                java.sql.Date.valueOf(LocalDate.of(2027, 6, 30)), betaLease);

        LeaseExpirationJob.ExpiryRun run = job.runFor(TODAY);

        assertThat(statusOf(alphaLease)).isEqualTo(LeaseStatus.TERMINATED);
        assertThat(statusOf(betaLease)).as("a termination in flight is not an expiry")
                .isEqualTo(LeaseStatus.ACTIVE);
        assertThat(run.expired()).isZero();
        assertThat(run.skipped()).as("neither row is a candidate at all — the query excludes both")
                .isZero();
        assertThat(run.failed()).as("and neither is refused by the flip, which never sees them")
                .isZero();
    }

    /**
     * The scheduled entry point takes its date from the {@link Clock} bean and holds
     * the ShedLock the brief specifies — the API runs behind Caddy on more than one
     * instance, and a midnight sweep must happen once, not once per replica.
     */
    @Test
    void theScheduledEntryPointUsesTheClockAndHoldsItsShedLock() {
        assertThat(LocalDate.now(clock)).isEqualTo(TODAY);

        job.evaluateExpiredLeases();

        assertThat(statusOf(alphaLease)).isEqualTo(LeaseStatus.EXPIRED);
        assertThat(statusOf(betaLease)).isEqualTo(LeaseStatus.EXPIRED);

        Map<String, Object> lock = jdbc.queryForMap(
                "select locked_at, lock_until from shedlock where name = 'lease-expiration'");
        Timestamp lockedAt = (Timestamp) lock.get("locked_at");
        Timestamp lockUntil = (Timestamp) lock.get("lock_until");
        assertThat(lockUntil).as("lockAtLeastFor keeps the next replica out")
                .isAfterOrEqualTo(Timestamp.from(lockedAt.toInstant().plusSeconds(60)));
    }

    /** A second sweep on the same night finds nothing left to do. */
    @Test
    void aSecondSweepIsANoOp() {
        job.runFor(TODAY);

        LeaseExpirationJob.ExpiryRun again = job.runFor(TODAY);

        assertThat(again.expired()).isZero();
        assertThat(again.skipped()).isZero();
        assertThat(again.failed()).isZero();
        assertThat(eventsOf(alphaLease))
                .filteredOn(e -> e.getNewState() == LeaseStatus.EXPIRED)
                .as("one expiry event, not two").hasSize(1);
    }
}
