package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.GatePass;
import com.datagami.rentaxis.domain.entity.GatePassScan;
import com.datagami.rentaxis.domain.entity.GuardPropertyAssignment;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import com.datagami.rentaxis.domain.entity.enums.GatePassType;
import com.datagami.rentaxis.domain.entity.enums.ScanDirection;
import com.datagami.rentaxis.domain.entity.enums.ScanResult;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.GatePassRepository;
import com.datagami.rentaxis.domain.repository.GatePassScanRepository;
import com.datagami.rentaxis.domain.repository.GuardPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link GatePassScanService} against a real Postgres —
 * the two properties the mocked unit tests structurally cannot see, because
 * both live in the database rather than in the service's control flow:
 *
 * <ol>
 *   <li>the pessimistic row lock actually serializes concurrent scans (delete
 *       {@code @Lock} or {@code @Transactional} and every mocked test still
 *       passes);</li>
 *   <li>numeric-code resolution survives code recycling, which produces
 *       multiple rows for one code — the case that makes a single-result
 *       finder throw.</li>
 * </ol>
 *
 * <p>Requires Docker on the host.
 */
@SpringBootTest(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.datagami.rentaxis.core.service.GatePassScanConcurrencyIT$SqlCapture")
@Testcontainers
class GatePassScanConcurrencyIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Records every statement Hibernate sends, so a test can assert on the real SQL. */
    public static class SqlCapture implements StatementInspector {
        static final Queue<String> STATEMENTS = new ConcurrentLinkedQueue<>();

        @Override
        public String inspect(String sql) {
            STATEMENTS.add(sql);
            return sql;
        }
    }

    @Autowired GatePassScanService gatePassScanService;
    @Autowired GatePassRepository gatePassRepository;
    @Autowired GatePassScanRepository gatePassScanRepository;
    @Autowired GuardPropertyAssignmentRepository guardPropertyAssignmentRepository;
    @Autowired LandlordOrgRepository landlordOrgRepository;
    @Autowired PropertyRepository propertyRepository;
    @Autowired UnitRepository unitRepository;
    @Autowired UserRepository userRepository;

    private UUID tenantId;
    private UUID guardUserId;
    private UUID creatorUserId;
    private Property property;
    private Unit unit;

    @BeforeEach
    void setUp() {
        // Each test gets its own tenant; multi-tenant isolation means no cleanup
        // between tests is needed.
        LandlordOrg org = new LandlordOrg();
        org.setName("IT-Tenant-" + UUID.randomUUID());
        org = landlordOrgRepository.save(org);
        this.tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);

        property = new Property();
        property.setNameEn("IT-Property");
        property.setEmirate(Emirate.DUBAI);
        property = propertyRepository.save(property);

        unit = new Unit();
        unit.setProperty(property);
        unit.setUnitNumber("U-1");
        unit = unitRepository.save(unit);

        // gate_passes.created_by_user_id and gate_pass_scans.scanned_by_user_id are
        // both real FKs to users, so these rows have to exist.
        creatorUserId = seedUser("creator", UserRole.RENTER).getId();
        guardUserId = seedUser("guard", UserRole.PROPERTY_MANAGER).getId();

        GuardPropertyAssignment assignment = new GuardPropertyAssignment();
        assignment.setTenantId(tenantId);
        assignment.setUserId(guardUserId);
        assignment.setPropertyId(property.getId());
        guardPropertyAssignmentRepository.save(assignment);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    /**
     * The test Task 4 actually rests on: two guards scanning the same SINGLE_USE
     * pass at the same instant must not both admit the guest.
     *
     * <p>Without the row lock taken by the resolving query, both transactions read
     * {@code ACTIVE} under READ_COMMITTED, both pass the status guard, and both
     * write an ALLOWED ENTRY — a lost update that lets one pass admit two people.
     * With it, the second scanner either fails fast on NOWAIT ("scan in progress")
     * or re-reads the committed {@code USED} status and is rejected ("already
     * used"). Either is correct; what must hold is exactly one ALLOWED.
     */
    @Test
    void concurrentEntryScansOnSingleUsePass_exactlyOneIsAllowed() throws Exception {
        GatePass pass = seedPass(GatePassType.SINGLE_USE, GatePassStatus.ACTIVE, "10000001", Instant.now());
        String token = pass.getQrToken();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<GatePassScanService.ScanOutcome> first = pool.submit(scanTask(ready, go, token));
            Future<GatePassScanService.ScanOutcome> second = pool.submit(scanTask(ready, go, token));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            GatePassScanService.ScanOutcome a = first.get(15, TimeUnit.SECONDS);
            GatePassScanService.ScanOutcome b = second.get(15, TimeUnit.SECONDS);

            assertThat(List.of(a.result(), b.result()))
                    .as("exactly one scan of a SINGLE_USE pass may be ALLOWED; a=%s/%s b=%s/%s",
                            a.result(), a.reason(), b.result(), b.reason())
                    .containsExactlyInAnyOrder(ScanResult.ALLOWED, ScanResult.REJECTED);

            String loserReason = a.result() == ScanResult.ALLOWED ? b.reason() : a.reason();
            assertThat(loserReason).isIn("already used", "scan in progress, please retry");

            // The DB is the real verdict: one guest admitted, one row to prove it.
            List<GatePassScan> scans = gatePassScanRepository.findByGatePassIdOrderByScannedAtAsc(pass.getId());
            long allowedEntries = scans.stream()
                    .filter(s -> s.getDirection() == ScanDirection.ENTRY && s.getResult() == ScanResult.ALLOWED)
                    .count();
            assertThat(allowedEntries).as("exactly one ALLOWED ENTRY row must be persisted").isEqualTo(1);
            assertThat(gatePassRepository.findById(pass.getId()).orElseThrow().getStatus())
                    .isEqualTo(GatePassStatus.USED);
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Regression test for the 500 at the gate: {@code uq_gate_pass_numeric_active}
     * only covers PENDING_APPROVAL/ACTIVE, so a USED pass's numeric code is handed
     * back out to a new pass. A status-filtered {@code Optional} finder then matched
     * both rows and threw {@code IncorrectResultSizeDataAccessException}.
     *
     * <p>Newest-first + LIMIT 1 resolves the live pass, because the partial unique
     * index guarantees a live pass is always the newest row holding its code.
     */
    @Test
    void recycledNumericCode_resolvesTheLivePassNotTheUsedOne() {
        String sharedCode = "20000002";
        // Old, consumed pass — still in the table, still holding the code.
        GatePass used = seedPass(GatePassType.SINGLE_USE, GatePassStatus.USED, sharedCode,
                Instant.now().minus(2, ChronoUnit.DAYS));
        // Newer live pass reissued the same code, which the partial index permits.
        GatePass live = seedPass(GatePassType.SINGLE_USE, GatePassStatus.ACTIVE, sharedCode,
                Instant.now().minus(1, ChronoUnit.HOURS));

        GatePassScanService.ScanOutcome outcome =
                gatePassScanService.scan(tenantId, guardUserId, null, sharedCode, ScanDirection.ENTRY);

        assertThat(outcome.result()).isEqualTo(ScanResult.ALLOWED);
        assertThat(outcome.pass().getId()).isEqualTo(live.getId());
        assertThat(gatePassRepository.findById(live.getId()).orElseThrow().getStatus())
                .isEqualTo(GatePassStatus.USED);
        // The old row must be untouched — it was already USED and is not this scan's pass.
        assertThat(gatePassRepository.findById(used.getId()).orElseThrow().getStatus())
                .isEqualTo(GatePassStatus.USED);
    }

    /**
     * The flip side: once every row for a code is terminal, the newest one still
     * resolves, so the guard gets a true reason instead of "not found". This is what
     * lets an EXIT be logged for a guest whose pass expired while they were inside.
     */
    @Test
    void expiredPassResolvesByNumericCodeAndReportsWhy() {
        String code = "30000003";
        seedPass(GatePassType.RECURRING, GatePassStatus.EXPIRED, code, Instant.now().minus(1, ChronoUnit.DAYS));

        GatePassScanService.ScanOutcome outcome =
                gatePassScanService.scan(tenantId, guardUserId, null, code, ScanDirection.ENTRY);

        assertThat(outcome.result()).isEqualTo(ScanResult.REJECTED);
        assertThat(outcome.reason()).isEqualTo("expired");
        assertThat(outcome.pass()).isNotNull();
    }

    /**
     * The race test above is probabilistic — it can pass by luck if the lock is gone
     * and the threads simply miss each other. These two assert the mechanism itself on
     * the SQL Hibernate actually sends, so deleting {@code @Lock} or the NOWAIT
     * {@code @QueryHints} fails deterministically rather than intermittently.
     */
    @Test
    void qrResolutionSendsLockingSelectWithNowait() {
        GatePass pass = seedPass(GatePassType.SINGLE_USE, GatePassStatus.ACTIVE, "40000004", Instant.now());
        SqlCapture.STATEMENTS.clear();

        gatePassScanService.scan(tenantId, guardUserId, pass.getQrToken(), null, ScanDirection.ENTRY);

        String select = selectAgainstGatePasses();
        assertRowLockedWithNowait(select);
        // qr_token is unique, so no ordering/limit is needed to make this single-row.
    }

    @Test
    void numericResolutionSendsOrderedLimitedLockingSelectWithNowait() {
        seedPass(GatePassType.SINGLE_USE, GatePassStatus.ACTIVE, "50000005", Instant.now());
        SqlCapture.STATEMENTS.clear();

        gatePassScanService.scan(tenantId, guardUserId, null, "50000005", ScanDirection.ENTRY);

        String select = selectAgainstGatePasses();
        // Postgres applies the row lock after the limit, so ORDER BY + LIMIT 1 + lock
        // locks exactly the newest row — and in one statement, i.e. Hibernate is not
        // falling back to follow-on locking (which would lock a different row set).
        assertThat(select).contains("order by");
        assertThat(select).contains("created_at desc");
        assertThat(select).containsAnyOf("limit", "fetch first");
        assertRowLockedWithNowait(select);
    }

    /**
     * Hibernate's PostgreSQL dialect renders {@code PESSIMISTIC_WRITE} as
     * {@code FOR NO KEY UPDATE}, not {@code FOR UPDATE}. That is the right lock here and
     * not a weakening: per Postgres's row-lock conflict matrix, two
     * {@code FOR NO KEY UPDATE} requests on the same row still block each other, so
     * concurrent scans of one pass serialize exactly as intended. What it additionally
     * permits is {@code FOR KEY SHARE} — the lock an FK check takes — so referencing
     * rows (e.g. a gate_pass_scans insert) don't queue behind a scan in progress.
     */
    private void assertRowLockedWithNowait(String select) {
        assertThat(select).containsAnyOf("for update", "for no key update");
        assertThat(select).contains("nowait");
    }

    /** The locking read of gate_passes — not the scan/notification inserts around it. */
    private String selectAgainstGatePasses() {
        List<String> selects = SqlCapture.STATEMENTS.stream()
                .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                .filter(s -> s.startsWith("select") && s.contains("gate_passes"))
                .toList();
        assertThat(selects).as("expected a select against gate_passes").isNotEmpty();
        return selects.getFirst();
    }

    private Callable<GatePassScanService.ScanOutcome> scanTask(CountDownLatch ready, CountDownLatch go, String token) {
        return () -> {
            TenantContextHolder.setTenantId(tenantId);
            try {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return gatePassScanService.scan(tenantId, guardUserId, token, null, ScanDirection.ENTRY);
            } finally {
                TenantContextHolder.clear();
            }
        };
    }

    private User seedUser(String prefix, UserRole role) {
        User user = new User();
        user.setTenantId(tenantId);
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@it.test");
        user.setPasswordHash("x");
        user.setName("IT " + prefix);
        user.setRole(role);
        return userRepository.save(user);
    }

    private GatePass seedPass(GatePassType type, GatePassStatus status, String numericCode, Instant createdAt) {
        GatePass pass = new GatePass();
        pass.setTenantId(tenantId);
        pass.setPropertyId(property.getId());
        pass.setUnitId(unit.getId());
        pass.setCreatedByUserId(creatorUserId);
        pass.setGuestName("IT Guest");
        pass.setGuestPhone("+971500000000");
        pass.setPassType(type);
        pass.setValidFrom(Instant.now().minus(1, ChronoUnit.HOURS));
        pass.setValidTo(Instant.now().plus(1, ChronoUnit.HOURS));
        pass.setStatus(status);
        pass.setQrToken(UUID.randomUUID().toString().replace("-", "") + "0123456789abcdef");
        pass.setNumericCode(numericCode);
        pass.setCreatedAt(createdAt);
        return gatePassRepository.save(pass);
    }
}
