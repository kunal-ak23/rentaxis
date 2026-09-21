package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Month-end close over HTTP (spec §8.4) and the lease page's recognition tab
 * (§11).
 *
 * <p>{@code @PreAuthorize} does nothing in a service-level test — the roles only
 * bite once a request has been through {@code ApiSecurityFilter} — so this is a
 * full-context HTTP test with the {@code X-User-*} headers the Next.js proxy
 * sends, the same shape as {@link JournalControllerIT}.</p>
 *
 * <p><b>The clock is fixed at 2026-12-01</b> so that "today", and therefore which
 * dates are in the future, is a fact of the test rather than of the day it is run
 * on. Without it the whole class would start failing on 2026-12-02.</p>
 *
 * <p><b>Two contracts in one organisation, on two different properties.</b> The
 * close is tenant-wide, so a single lease could not show that the list and the run
 * cover the whole organisation; and a property manager scoped to one building must
 * be refused the other building's schedule, which needs a second building to be
 * refused.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(RecognitionControllerIT.FixedClockConfig.class)
class RecognitionControllerIT {

    static final LocalDate TODAY = LocalDate.of(2026, 12, 1);

    @TestConfiguration
    static class FixedClockConfig {
        /** {@code @Primary}, not a same-named override — see {@code RevenueRecognitionJobIT}. */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).plusHours(9).toInstant(), ZoneOffset.UTC);
        }
    }

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort int port;

    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService cheques;
    @Autowired LeasePostingService posting;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;

    /** The client's fixture (spec §8.2). */
    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 9, 24);
    private static final LocalDate END = LocalDate.of(2027, 9, 23);
    private static final LocalDate NOV_END = LocalDate.of(2026, 11, 30);

    private LeaseTestFixtures fixtures;
    private Property marina;
    private UUID marinaLease;
    private UUID palmLease;
    private User accountant;
    private User admin;
    private User manager;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, cheques, posting);

        marina = fixtures.property();
        // 51,000 over 365 days: the client's own 978.08 / 4,331.51 / 4,191.78.
        marinaLease = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, null).lease().getId();

        // A second building. 36,500 over the same 365 days is exactly 100.00 a day,
        // so this lease's contribution to every total below is checkable by eye.
        Property palm = fixtures.createProperty("PALM");
        Unit palmUnit = fixtures.createUnit(palm, "901");
        palmLease = fixtures.postedLease(palmUnit, fixtures.createRenter("Palm Renter"),
                CONTRACT_DATE, START, END, List.of(line("RENT", "36500")), 4, null).lease().getId();

        accountant = user(UserRole.ACCOUNTANT, fixtures.tenantId());
        admin = user(UserRole.TENANT_ADMIN, fixtures.tenantId());
        manager = user(UserRole.PROPERTY_MANAGER, fixtures.tenantId());

        // Marina only. Without an assignment a PROPERTY_MANAGER is scoped to no
        // property at all, which is a different answer from "scoped to one".
        UserPropertyAssignment assignment = new UserPropertyAssignment();
        assignment.setUserId(manager.getId());
        assignment.setPropertyId(marina.getId());
        assignmentRepo.save(assignment);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private User user(UserRole role, UUID tenant) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenant);
        return userRepo.save(u);
    }

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private RestClient.RequestHeadersSpec<?> get(User caller, String uri) {
        return auth(client().get().uri(uri), caller);
    }

    private RestClient.RequestHeadersSpec<?> post(User caller, String uri) {
        return auth(client().post().uri(uri), caller);
    }

    private static RestClient.RequestHeadersSpec<?> auth(RestClient.RequestHeadersSpec<?> spec, User caller) {
        return spec.header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(User caller, String uri) {
        return get(caller, uri).retrieve().body(List.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(User caller, String query) {
        return post(caller, "/api/v1/finance/recognition/run" + query).retrieve().body(Map.class);
    }

    private List<Map<String, Object>> pending(User caller, String to) {
        return rows(caller, "/api/v1/finance/recognition/pending" + (to == null ? "" : "?to=" + to));
    }

    private List<Map<String, Object>> schedule(User caller, UUID leaseId) {
        return rows(caller, "/api/v1/leases/" + leaseId + "/recognition");
    }

    /** JSON numbers arrive as Integer or Double depending on scale; compare numerically. */
    private static BigDecimal number(Object raw) {
        return new BigDecimal(String.valueOf(raw));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> body, String key) {
        return (List<Map<String, Object>>) body.get(key);
    }

    // ------------------------------------------------------------------
    // pending
    // ------------------------------------------------------------------

    /**
     * The close list is tenant-wide and oldest period first: both contracts, three
     * ended months each, sorted so the accountant reads them in the order they
     * would post.
     */
    @Test
    void pendingListsEveryTenantRowOldestPeriodFirst() {
        List<Map<String, Object>> all = pending(accountant, NOV_END.toString());

        assertThat(all).hasSize(6);
        assertThat(all).allSatisfy(r -> {
            assertThat(r.get("status")).isEqualTo("PLANNED");
            assertThat(r.get("journalId")).isNull();
        });
        assertThat(all).extracting(r -> r.get("periodEnd")).containsExactly(
                "2026-09-30", "2026-09-30", "2026-10-31", "2026-10-31", "2026-11-30", "2026-11-30");

        // The marina contract's own three rows are the client's published figures.
        assertThat(all.stream().filter(r -> marinaLease.toString().equals(r.get("leaseId")))
                .map(r -> number(r.get("amount"))).toList())
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("978.08"), new BigDecimal("4331.51"), new BigDecimal("4191.78"));
        // And the palm contract's, at exactly 100.00 a day.
        assertThat(all.stream().filter(r -> palmLease.toString().equals(r.get("leaseId")))
                .map(r -> number(r.get("amount"))).toList())
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("700.00"), new BigDecimal("3100.00"), new BigDecimal("3000.00"));
    }

    /** No {@code to} means today, which the fixed clock pins at 2026-12-01. */
    @Test
    void pendingDefaultsToToday() {
        assertThat(pending(accountant, null)).hasSize(6);
        assertThat(pending(accountant, TODAY.toString())).hasSize(6);
        // December has not ended, so the run to today stops at November either way.
        assertThat(pending(accountant, "2026-10-31")).hasSize(4);
    }

    // ------------------------------------------------------------------
    // run
    // ------------------------------------------------------------------

    /**
     * A preview reports what would post and writes nothing — and says so:
     * {@code posted} is 0, {@code wouldPost} carries the count, and every row comes
     * back still PLANNED with no journal behind it.
     */
    @Test
    void previewSaysWhatWouldPostAndWritesNothing() {
        Map<String, Object> body = run(accountant, "?to=" + NOV_END + "&preview=true");

        assertThat(body.get("preview")).isEqualTo(true);
        assertThat(body.get("posted")).as("a preview posted nothing").isEqualTo(0);
        assertThat(body.get("wouldPost")).isEqualTo(6);
        // 9,501.37 (marina) + 6,800.00 (palm)
        assertThat(number(body.get("amount"))).isEqualByComparingTo("16301.37");
        assertThat(body.get("failed")).isEqualTo(0);
        assertThat((List<?>) body.get("errors")).isEmpty();
        assertThat(body.get("skippedLocked")).isEqualTo(0);
        assertThat((List<?>) body.get("skippedLockedEntries")).isEmpty();
        assertThat(body.get("booksLockedThrough")).isNull();

        assertThat(list(body, "entries")).hasSize(6).allSatisfy(r -> {
            assertThat(r.get("status")).isEqualTo("PLANNED");
            assertThat(r.get("journalId")).isNull();
        });

        // Nothing moved: the same six rows are still waiting.
        assertThat(pending(accountant, NOV_END.toString())).hasSize(6);
    }

    /** The real run posts one CIL per row, and a second run has nothing left to do. */
    @Test
    void runPostsEveryEndedPeriodAndIsIdempotent() {
        Map<String, Object> body = run(accountant, "?to=" + NOV_END);

        assertThat(body.get("preview")).isEqualTo(false);
        assertThat(body.get("posted")).isEqualTo(6);
        assertThat(body.get("wouldPost")).isEqualTo(6);
        assertThat(number(body.get("amount"))).isEqualByComparingTo("16301.37");
        assertThat(list(body, "entries")).hasSize(6).allSatisfy(r -> {
            assertThat(r.get("status")).isEqualTo("POSTED");
            assertThat(r.get("journalId")).isNotNull();
            assertThat((String) r.get("journalNumber")).startsWith("CIL-");
            assertThat(r.get("postedAt")).isNotNull();
        });

        assertThat(pending(accountant, NOV_END.toString())).isEmpty();

        Map<String, Object> again = run(accountant, "?to=" + NOV_END);
        assertThat(again.get("posted")).isEqualTo(0);
        assertThat(list(again, "entries")).isEmpty();
        assertThat(number(again.get("amount"))).isEqualByComparingTo("0");
    }

    /** With no {@code to}, the run closes everything up to the clock's today. */
    @Test
    void runDefaultsToToday() {
        assertThat(run(accountant, "").get("posted")).isEqualTo(6);
        assertThat(pending(accountant, TODAY.toString())).isEmpty();
    }

    /**
     * Rows in a closed period come back apart from failures.
     *
     * <p>"Skipped because the month is shut" and "the ledger refused this" leave
     * the same rows PLANNED. Only which list they arrive in tells the accountant
     * whether there is anything to chase — so {@code errors} has to be empty here.</p>
     */
    @Test
    void lockedRowsAreReportedSeparatelyFromFailures() {
        TenantContextHolder.setTenantId(fixtures.tenantId());
        try {
            fiscal.lockThrough(LocalDate.of(2026, 10, 31));
        } finally {
            TenantContextHolder.clear();
        }

        Map<String, Object> body = run(accountant, "?to=" + NOV_END);

        assertThat(body.get("posted")).as("only the two November rows").isEqualTo(2);
        assertThat(body.get("failed")).isEqualTo(0);
        assertThat((List<?>) body.get("errors")).isEmpty();
        assertThat(body.get("skippedLocked")).isEqualTo(4);
        assertThat(body.get("booksLockedThrough")).isEqualTo("2026-10-31");
        assertThat(list(body, "skippedLockedEntries")).hasSize(4).allSatisfy(r -> {
            assertThat(r.get("status")).isEqualTo("PLANNED");
            assertThat(r.get("journalId")).isNull();
            assertThat(r.get("periodEnd")).isIn("2026-09-30", "2026-10-31");
        });
        // 4,191.78 + 3,000.00
        assertThat(number(body.get("amount"))).isEqualByComparingTo("7191.78");
    }

    // ------------------------------------------------------------------
    // the future-date guard
    // ------------------------------------------------------------------

    /**
     * Income is recognised for periods that have <em>ended</em>. A close run to
     * tomorrow would post a {@code CIL} dated ahead of itself for a month nobody
     * has lived through yet, and the next real run would find nothing left to do —
     * the mistake would surface a month later as a gap rather than as a refusal.
     */
    @Test
    void aDateAfterTodayIsRefusedOnBothEndpoints() {
        assertThatThrownBy(() -> run(accountant, "?to=" + TODAY.plusDays(1)))
                .isInstanceOf(HttpClientErrorException.BadRequest.class)
                .hasMessageContaining("Cannot recognise income for periods that have not ended");

        assertThatThrownBy(() -> pending(accountant, TODAY.plusDays(1).toString()))
                .isInstanceOf(HttpClientErrorException.BadRequest.class)
                .hasMessageContaining("Cannot recognise income for periods that have not ended");

        // The boundary itself is allowed: today's periods have ended today.
        assertThat(run(accountant, "?to=" + TODAY + "&preview=true").get("wouldPost")).isEqualTo(6);

        // And nothing was written by the refused calls.
        assertThat(pending(accountant, TODAY.toString())).hasSize(6);
    }

    // ------------------------------------------------------------------
    // who may do what
    // ------------------------------------------------------------------

    /**
     * Running a close is an act on the organisation's books, not on a building.
     * A PROPERTY_MANAGER drafts leases and chases cheques; they do not close a
     * month, and they do not get to see the tenant-wide list of what is about to
     * be recognised either.
     */
    @Test
    void aPropertyManagerMayNotRunOrListTheClose() {
        assertThatThrownBy(() -> run(manager, "?to=" + NOV_END))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
        assertThatThrownBy(() -> run(manager, "?to=" + NOV_END + "&preview=true"))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
        assertThatThrownBy(() -> pending(manager, NOV_END.toString()))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);

        // The refusal really was the role gate and not a broken fixture.
        assertThat(pending(admin, NOV_END.toString())).hasSize(6);
    }

    /**
     * The schedule is part of the contract, so a manager reads it — for the
     * buildings they were actually assigned.
     *
     * <p>The role gate alone would hand a manager assigned to one tower every
     * contract in the organisation by id, which is the exact hole
     * {@code LeaseAccessPolicy} exists to close. 404 rather than 403, deliberately:
     * a 403 on a lease id confirms the lease exists.</p>
     */
    @Test
    void theScheduleIsScopedToThePropertyManagersOwnBuildings() {
        assertThat(schedule(manager, marinaLease)).hasSize(13);

        assertThatThrownBy(() -> schedule(manager, palmLease))
                .as("a building this manager was not assigned")
                .isInstanceOf(HttpClientErrorException.NotFound.class);

        // The accountant is tenant-wide and reads both.
        assertThat(schedule(accountant, marinaLease)).hasSize(13);
        assertThat(schedule(accountant, palmLease)).hasSize(13);
    }

    /** The tab's own shape: every row, oldest first, with the posted ones carrying their CIL. */
    @Test
    void theScheduleCarriesEveryRowOldestFirst() {
        run(accountant, "?to=" + NOV_END);

        List<Map<String, Object>> rows = schedule(accountant, marinaLease);
        assertThat(rows).hasSize(13);
        assertThat(rows).extracting(r -> r.get("periodStart")).startsWith("2026-09-24", "2026-10-01", "2026-11-01");
        assertThat(rows.subList(0, 3)).allSatisfy(r -> {
            assertThat(r.get("status")).isEqualTo("POSTED");
            assertThat((String) r.get("journalNumber")).startsWith("CIL-");
        });
        assertThat(rows.subList(3, 13)).allSatisfy(r -> {
            assertThat(r.get("status")).isEqualTo("PLANNED");
            assertThat(r.get("journalNumber")).isNull();
        });
        assertThat(rows.stream().map(r -> number(r.get("amount"))).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("51000.00");
    }

    /** Recognition is tenant-scoped data: another landlord must see none of it (CLAUDE.md P0). */
    @Test
    void anotherLandlordsAccountantSeesNothingOfThisOne() {
        LandlordOrg other = new LandlordOrg();
        other.setName("REC-other-" + UUID.randomUUID());
        User outsider = user(UserRole.ACCOUNTANT, orgRepo.save(other).getId());

        assertThat(pending(outsider, NOV_END.toString())).isEmpty();
        assertThat(run(outsider, "?to=" + NOV_END).get("posted")).isEqualTo(0);
        assertThatThrownBy(() -> schedule(outsider, marinaLease))
                .isInstanceOf(HttpClientErrorException.NotFound.class);

        // And this landlord's own rows are untouched by the outsider's run.
        assertThat(pending(accountant, NOV_END.toString())).hasSize(6);
    }

    /** An id that is nobody's lease is a 404, not a 500 or an empty list. */
    @Test
    void anUnknownLeaseIs404() {
        assertThatThrownBy(() -> schedule(accountant, UUID.randomUUID()))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }
}
