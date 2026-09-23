package com.datagami.rentaxis.core.service.recognition;

import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * The nightly close (spec §8.4), run against a real database for two landlords at
 * once.
 *
 * <p><b>The subject is the tenant loop, not the arithmetic.</b> What the schedule
 * contains is {@code RecognitionServiceIT}'s business; what matters here is that
 * the job sets a tenant context per organisation — it has no HTTP request to have
 * set one for it, and {@code TenantAspect} enables the Hibernate tenant filter
 * only when one is present. Get that wrong and one landlord's rent is recognised
 * in another landlord's ledger, under another landlord's entry numbers. That is a
 * P0, so it is asserted from both ends: each tenant's rows post, and each tenant's
 * {@code CIL} numbering starts at 1 independently of the other's.</p>
 *
 * <p><b>The clock is fixed at 2026-12-01</b> through a {@code @Primary} {@link Clock}
 * bean, because "today" is the job's only input and a job whose date cannot be
 * fixed can only be tested by waiting for tomorrow.</p>
 *
 * <p><b>No authentication is installed</b> before the run, deliberately: that is
 * the production condition. A path that quietly needed a principal would work in
 * every other test and fail every night at 00:30.</p>
 *
 * <p><b>Why most tests here call {@code runFor} and not {@code run}.</b>
 * {@code @SchedulerLock} is a proxy on the bean, not something only the scheduler
 * sees, and {@code lockAtLeastFor = "PT1M"} means a second call within the minute
 * is silently skipped — which is the whole point of it, and which would otherwise
 * make every test after the first one here quietly assert nothing. So exactly one
 * test drives {@link RevenueRecognitionJob#run()} and the lock it takes, and the
 * rest drive {@link RevenueRecognitionJob#runFor(LocalDate)} underneath it.</p>
 */
@SpringBootTest
@Import(RevenueRecognitionJobIT.FixedClockConfig.class)
class RevenueRecognitionJobIT extends AbstractPostgresIT {

    /** 2026-12-01, so September, October and November have ended and December has not. */
    static final LocalDate TODAY = LocalDate.of(2026, 12, 1);

    @TestConfiguration
    static class FixedClockConfig {
        /**
         * {@code @Primary} rather than the same bean name as {@code ClockConfig}:
         * overriding by name needs {@code spring.main.allow-bean-definition-overriding},
         * which would silently change how every other bean in this context is wired.
         */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).plusHours(3).toInstant(), ZoneOffset.UTC);
        }
    }

    @Autowired RevenueRecognitionJob job;
    @Autowired RecognitionService recognition;
    @Autowired Clock clock;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService cheques;
    @Autowired LeasePostingService posting;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired JdbcTemplate jdbc;

    /** The client's fixture (spec §8.2), posted identically for both landlords. */
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
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, null)
                .lease().getId();
    }

    /** Read something as a given tenant, with no authenticated caller — as the job runs. */
    private <T> T as(UUID tenantId, Supplier<T> read) {
        TenantContextHolder.setTenantId(tenantId);
        try {
            return read.get();
        } finally {
            TenantContextHolder.clear();
        }
    }

    private List<String> cilNumbersOf(UUID tenantId) {
        return jdbc.queryForList("select entry_number from journal_entries "
                + "where tenant_id = ? and doc_type = 'CIL' order by entry_date asc", String.class, tenantId);
    }

    /** Every CIL in the database that belongs to neither of this test's two landlords. */
    private long cilCountOutside(UUID a, UUID b) {
        return jdbc.queryForObject("select count(*) from journal_entries "
                + "where doc_type = 'CIL' and tenant_id not in (?, ?)", Long.class, a, b);
    }

    // ------------------------------------------------------------------

    /**
     * One pass closes September, October and November for <em>both</em> landlords,
     * independently: three CILs each, numbered from 1 in each organisation, and
     * December — which has not ended — left alone.
     */
    @Test
    void oneNightlyPassClosesEveryTenantIndependently() {
        long foreignCilsBefore = cilCountOutside(alpha.tenantId(), beta.tenantId());
        LeaseTestFixtures.clearAuth();
        TenantContextHolder.clear();

        job.runFor(TODAY);

        for (LeaseTestFixtures f : List.of(alpha, beta)) {
            UUID leaseId = f == alpha ? alphaLease : betaLease;
            List<RecognitionEntryDTO> rows = as(f.tenantId(), () -> recognition.scheduleFor(leaseId));

            assertThat(rows).as("the whole schedule is still 13 rows").hasSize(13);
            assertThat(rows.subList(0, 3)).allSatisfy(r -> {
                assertThat(r.status()).isEqualTo(RecognitionStatus.POSTED);
                assertThat(r.journalId()).isNotNull();
                assertThat(r.journalNumber()).startsWith("CIL-");
            });
            // November ended on the 30th; December has not, and the job runs to today.
            assertThat(rows.subList(3, 13)).allSatisfy(r -> {
                assertThat(r.status()).isEqualTo(RecognitionStatus.PLANNED);
                assertThat(r.journalId()).isNull();
            });
            assertThat(rows.subList(0, 3)).extracting(RecognitionEntryDTO::periodEnd).containsExactly(
                    LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 30));
            assertThat(rows.subList(0, 3).stream()
                    .map(RecognitionEntryDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .as("978.08 + 4,331.51 + 4,191.78")
                    .isEqualByComparingTo("9501.37");
        }

        // ---- the P0: neither landlord's journals landed in the other's books ----
        assertThat(cilNumbersOf(alpha.tenantId()))
                .as("each organisation numbers its own CILs from 1")
                .containsExactly("CIL-26/1", "CIL-26/2", "CIL-26/3");
        assertThat(cilNumbersOf(beta.tenantId()))
                .containsExactly("CIL-26/1", "CIL-26/2", "CIL-26/3");
        assertThat(cilCountOutside(alpha.tenantId(), beta.tenantId()))
                .as("the pass wrote nothing into a third landlord's ledger")
                .isEqualTo(foreignCilsBefore);

        // Each CIL points at a recognition entry of the very same tenant.
        assertThat(jdbc.queryForObject(
                "select count(*) from journal_entries j join recognition_entries e on e.id = j.source_id "
                        + "where j.doc_type = 'CIL' and j.tenant_id <> e.tenant_id", Long.class))
                .as("a CIL written against another tenant's recognition entry")
                .isZero();

        // ---- and the context is not left behind for whatever runs next ----
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    /**
     * The scheduled entry point takes its date from the {@link Clock} bean, not
     * from {@code LocalDate.now()} — and takes the ShedLock the brief specifies.
     *
     * <p>The date is asserted by the shape of what posted rather than by trusting
     * the fixture: 2026-12-01 is the only date that closes three months of this
     * schedule and not four, and a job reading the real clock would close none of
     * them at all.</p>
     *
     * <p>The lock is asserted from the table rather than by calling {@code run()}
     * twice and watching nothing happen, which is indistinguishable from "there
     * was nothing left to do". The API runs behind Caddy on more than one
     * instance; without this row every replica would start its own close at
     * 00:30.</p>
     */
    @Test
    void theScheduledEntryPointUsesTheClockAndHoldsItsShedLock() {
        assertThat(LocalDate.now(clock)).isEqualTo(TODAY);
        // The lock table is shared with every other class on the test database, and
        // a run elsewhere within the last minute would still hold this lock — run()
        // would then skip silently and this test would assert on a close that never
        // happened. Start from a free lock; what is asserted is that run() takes it.
        jdbc.update("delete from shedlock where name = 'revenue-recognition'");

        job.run();

        long posted = as(alpha.tenantId(), () -> recognition.scheduleFor(alphaLease)).stream()
                .filter(r -> r.status() == RecognitionStatus.POSTED).count();
        assertThat(posted).isEqualTo(3);
        assertThat(as(alpha.tenantId(), () -> recognition.pending(TODAY)))
                .as("nothing whose period has ended is still waiting")
                .isEmpty();

        Map<String, Object> lock = jdbc.queryForMap(
                "select locked_at, lock_until from shedlock where name = 'revenue-recognition'");
        Timestamp lockedAt = (Timestamp) lock.get("locked_at");
        Timestamp lockUntil = (Timestamp) lock.get("lock_until");
        assertThat(lockUntil).as("lockAtLeastFor = PT1M keeps the next replica out")
                .isAfterOrEqualTo(Timestamp.from(lockedAt.toInstant().plusSeconds(60)));
    }

    /**
     * A tenant whose books are closed is skipped, not failed — and skipping it does
     * not cost the other tenant its close.
     *
     * <p>This is also the "one organisation's bad night is its own" path in its
     * mildest form: the loop must keep going past a tenant that produced nothing.</p>
     */
    @Test
    void aLockedTenantIsSkippedAndTheOtherStillCloses() {
        as(alpha.tenantId(), () -> fiscal.lockThrough(LocalDate.of(2026, 12, 31)));

        job.runFor(TODAY);

        assertThat(as(alpha.tenantId(), () -> recognition.scheduleFor(alphaLease)))
                .as("every row of the locked landlord is untouched")
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(RecognitionStatus.PLANNED));
        assertThat(cilNumbersOf(alpha.tenantId())).isEmpty();

        assertThat(as(beta.tenantId(), () -> recognition.scheduleFor(betaLease)).subList(0, 3))
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(RecognitionStatus.POSTED));
        assertThat(cilNumbersOf(beta.tenantId())).containsExactly("CIL-26/1", "CIL-26/2", "CIL-26/3");
    }

    /** A second pass on the same night finds nothing left to do and writes nothing. */
    @Test
    void aSecondPassIsANoOp() {
        job.runFor(TODAY);
        long after = cilNumbersOf(alpha.tenantId()).size() + cilNumbersOf(beta.tenantId()).size();

        job.runFor(TODAY);

        assertThat(cilNumbersOf(alpha.tenantId()).size() + cilNumbersOf(beta.tenantId()).size())
                .isEqualTo(after);
        assertThat(as(alpha.tenantId(), () -> recognition.scheduleFor(alphaLease)).stream()
                .filter(r -> r.status() == RecognitionStatus.POSTED).count()).isEqualTo(3);
    }

    /**
     * A pass asked to stop does not start, and leaves the flag for its caller.
     *
     * <p>A sweep over every organisation is minutes of work. A shutdown that
     * interrupts the thread must not have to wait for the fortieth tenant, and —
     * the textbook half of this — the flag must survive: {@code runTenant}'s
     * {@code catch (Exception)} would otherwise swallow an {@code InterruptedException}
     * whole, clearing the one signal the loop reads to decide whether to carry on.</p>
     *
     * <p>The flag is read-and-cleared the moment {@code runFor} returns, before any
     * assertion touches the database: leaving it set would poison every later test
     * on this worker thread, and a connection wait is exactly what notices it.</p>
     */
    @Test
    void anInterruptedPassDoesNotStartAndKeepsTheFlag() {
        boolean flagSurvived;
        Thread.currentThread().interrupt();
        try {
            job.runFor(TODAY);
        } finally {
            flagSurvived = Thread.interrupted();
        }

        assertThat(flagSurvived).as("the interrupt is left for the caller to act on").isTrue();
        assertThat(as(alpha.tenantId(), () -> recognition.scheduleFor(alphaLease)))
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(RecognitionStatus.PLANNED));
        assertThat(cilNumbersOf(alpha.tenantId())).isEmpty();
        assertThat(cilNumbersOf(beta.tenantId())).isEmpty();
    }

    /**
     * The cause chain, not just the outer type — which is the whole reason the
     * helper exists.
     *
     * <p>Nothing on the posting path declares {@code InterruptedException}, so when
     * a pass really is interrupted it arrives wrapped: Hikari's connection wait and
     * Hibernate both surface it inside a {@code RuntimeException}. A check on the
     * outer type alone would be the same bug as no check at all.</p>
     */
    @Test
    void aWrappedInterruptIsStillAnInterrupt() {
        assertThat(RevenueRecognitionJob.wasInterrupted(new InterruptedException())).isTrue();
        assertThat(RevenueRecognitionJob.wasInterrupted(
                new RuntimeException("closing", new IllegalStateException("pool", new InterruptedException()))))
                .as("the shape a real interrupt arrives in").isTrue();
        assertThat(RevenueRecognitionJob.wasInterrupted(new RuntimeException("no advance-rent mapping")))
                .as("an ordinary tenant failure is not a shutdown").isFalse();

        // A cyclic cause chain must answer, not spin. (initCause refuses `this`, so
        // the cycle needs two.)
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertThat(RevenueRecognitionJob.wasInterrupted(a)).isFalse();
    }

    /**
     * {@code runFor} is the same pass on an explicit date — what a cut-over
     * catch-up uses. A date past the whole term closes the whole term.
     */
    @Test
    void runForAnExplicitDateClosesEverythingUpToIt() {
        job.runFor(LocalDate.of(2027, 12, 31));

        assertThat(as(alpha.tenantId(), () -> recognition.scheduleFor(alphaLease)))
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(RecognitionStatus.POSTED));
        assertThat(cilNumbersOf(alpha.tenantId())).hasSize(13);
        assertThat(cilNumbersOf(beta.tenantId())).hasSize(13);
        // Two fiscal years, each numbered from 1 within the tenant.
        assertThat(cilNumbersOf(alpha.tenantId())).startsWith("CIL-26/1").contains("CIL-27/1");
    }
}
