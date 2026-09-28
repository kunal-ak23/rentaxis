package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.chequeRow;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Break-it round 2 (contracts2) F2/F3, over HTTP: contract money carries the lease
 * version the user saw. A post, a draft edit, a cheque-grid save or generate and a
 * rent-free change that name a version the lease has moved past are refused with
 * 409 "This contract changed since you opened it — review it again" (code
 * {@code lease.changed}); one that names none is accepted, as before (mobile).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaseVersionGuardIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService chequeGenerationService;
    @Autowired LeasePostingService leasePostingService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired JdbcTemplate jdbc;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);
    private static final String CHANGED = "This contract changed since you opened it — review it again";

    private LeaseTestFixtures fixtures;
    private User admin;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGenerationService, leasePostingService);
        User u = new User();
        u.setEmail("admin-" + UUID.randomUUID() + "@t.io");
        u.setName("ADMIN");
        u.setRole(UserRole.TENANT_ADMIN);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(fixtures.tenantId());
        admin = userRepo.save(u);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ---- helpers -------------------------------------------------------------

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> call(HttpMethod method, String path, Object body, String ifMatch) {
        RestClient.RequestBodySpec spec = RestClient.builder()
                .baseUrl("http://localhost:" + port).build()
                .method(method).uri(path)
                .header("X-User-Id", admin.getId().toString())
                .header("X-User-Role", admin.getRole().name())
                .header("X-Tenant-Id", admin.getTenantId().toString())
                .header("X-User-Tenant-Id", admin.getTenantId().toString());
        if (ifMatch != null) spec = spec.header("If-Match", ifMatch);
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(Map.class);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<List> callList(HttpMethod method, String path, Object body, String ifMatch) {
        RestClient.RequestBodySpec spec = RestClient.builder()
                .baseUrl("http://localhost:" + port).build()
                .method(method).uri(path)
                .header("X-User-Id", admin.getId().toString())
                .header("X-User-Role", admin.getRole().name())
                .header("X-Tenant-Id", admin.getTenantId().toString())
                .header("X-User-Tenant-Id", admin.getTenantId().toString());
        if (ifMatch != null) spec = spec.header("If-Match", ifMatch);
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(List.class);
    }

    private long versionOf(UUID leaseId) {
        Object v = call(HttpMethod.GET, "/api/v1/leases/" + leaseId, null, null).getBody().get("version");
        assertThat(v).as("LeaseDTO.version").isNotNull();
        return ((Number) v).longValue();
    }

    private long dbVersion(UUID leaseId) {
        return jdbc.queryForObject("select version from leases where id = ?", Long.class, leaseId);
    }

    private BigDecimal dbRent(UUID leaseId) {
        return jdbc.queryForObject("select rent_amount from leases where id = ?", BigDecimal.class, leaseId);
    }

    private String dbStatus(UUID leaseId) {
        return jdbc.queryForObject("select status from leases where id = ?", String.class, leaseId);
    }

    private long tcoCount(UUID leaseId) {
        return jdbc.queryForObject("select count(*) from journal_entries where source_id = ? and doc_type = 'TCO'",
                Long.class, leaseId);
    }

    private UUID draftWithGrid(String rent) {
        UUID id = fixtures.draftLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", rent), line("SECURITY_DEPOSIT", "5000")));
        fixtures.generateGrid(id, 4, START);
        return id;
    }

    /** The draft editor's body: the whole form, as LeaseMetadataEditor sends it. */
    private CreateLeaseDTO form(String rent, LocalDate end, Long version) {
        CreateLeaseDTO dto = fixtures.draftDto(START, end, List.of(line("RENT", rent), line("SECURITY_DEPOSIT", "5000")));
        dto.setContractDate(CONTRACT_DATE);
        dto.setFirstDueDate(START);
        dto.setVersion(version);
        return dto;
    }

    @SuppressWarnings("rawtypes")
    private static void assertChanged(ResponseEntity<Map> res) {
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().get("message")).isEqualTo(CHANGED);
        assertThat(res.getBody().get("code")).isEqualTo("lease.changed");
    }

    // ---- F2: post ------------------------------------------------------------

    /** C14: A's dialog saw 65,000; B moved rent to 90,000; A's confirm is refused and nothing posts. */
    @Test
    void aPostNamingAStaleVersionIsRefusedAndWritesNothing() {
        UUID id = draftWithGrid("60000");
        long seenByA = versionOf(id);

        assertThat(call(HttpMethod.PUT, "/api/v1/leases/" + id, form("90000", END, seenByA), null)
                .getStatusCode().value()).isEqualTo(200);

        var res = call(HttpMethod.POST, "/api/v1/leases/" + id + "/post", Map.of("version", seenByA), null);
        assertChanged(res);
        assertThat(dbStatus(id)).isEqualTo("DRAFT");
        assertThat(tcoCount(id)).isZero();
    }

    @Test
    void aPostNamingAStaleVersionInIfMatchIsRefusedToo() {
        UUID id = draftWithGrid("60000");
        long seen = versionOf(id);
        call(HttpMethod.PUT, "/api/v1/leases/" + id, form("90000", END, null), null);

        assertChanged(call(HttpMethod.POST, "/api/v1/leases/" + id + "/post", null, "\"" + seen + "\""));
        assertThat(dbStatus(id)).isEqualTo("DRAFT");
    }

    @Test
    void aPostNamingTheCurrentVersionPosts() {
        UUID id = draftWithGrid("60000");
        var res = call(HttpMethod.POST, "/api/v1/leases/" + id + "/post", Map.of("version", versionOf(id)), null);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(dbStatus(id)).isEqualTo(LeaseStatus.ACTIVE.name());
    }

    /** Back-compatible: an older client (mobile) that names no version is accepted. */
    @Test
    void aPostNamingNoVersionPostsAsBefore() {
        UUID id = draftWithGrid("60000");
        assertThat(call(HttpMethod.POST, "/api/v1/leases/" + id + "/post", null, null)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(dbStatus(id)).isEqualTo(LeaseStatus.ACTIVE.name());
    }

    /**
     * The wizard's path over HTTP: create, cut the grid (which moves the version —
     * review A M3 — and says where to in X-Lease-Version), number it, post with the
     * version the wizard adopted. The create's own version is stale by then.
     */
    @Test
    void theWizardAdoptsTheVersionEachGridWriteReturnsAndPosts() {
        var created = call(HttpMethod.POST, "/api/v1/leases",
                form("60000", END, null), null);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        UUID id = UUID.fromString((String) created.getBody().get("id"));
        long v = ((Number) created.getBody().get("version")).longValue();
        assertThat(v).isEqualTo(dbVersion(id));

        var gen = callList(HttpMethod.POST, "/api/v1/leases/" + id + "/cheques/generate",
                Map.of("installments", 4), "\"" + v + "\"");
        assertThat(gen.getStatusCode().value()).isEqualTo(200);
        long afterGrid = headerVersion(gen);
        assertThat(afterGrid).isGreaterThan(v).isEqualTo(dbVersion(id));

        assertThat(callList(HttpMethod.POST, "/api/v1/leases/" + id + "/cheques/numbers",
                Map.of("startingNumber", "910001"), null).getStatusCode().value()).isEqualTo(200);

        assertChanged(call(HttpMethod.POST, "/api/v1/leases/" + id + "/post", Map.of("version", v), null));
        assertThat(call(HttpMethod.POST, "/api/v1/leases/" + id + "/post", Map.of("version", afterGrid), null)
                .getStatusCode().value()).isEqualTo(200);
    }

    // ---- F3: draft edits -----------------------------------------------------

    /** C13: both tabs opened at the same version; A saves rent 72,000, B's stale save is refused and A's rent kept. */
    @Test
    void theSecondOfTwoEditsFromTheSameVersionIsRefusedAndTheFirstKept() {
        UUID id = draftWithGrid("60000");
        long v = versionOf(id);

        var a = call(HttpMethod.PUT, "/api/v1/leases/" + id, form("72000", END, v), null);
        assertThat(a.getStatusCode().value()).isEqualTo(200);

        var b = call(HttpMethod.PUT, "/api/v1/leases/" + id, form("60000", LocalDate.of(2027, 9, 30), v), null);
        assertChanged(b);
        assertThat(dbRent(id)).isEqualByComparingTo("72000");
        assertThat(jdbc.queryForObject("select end_date from leases where id = ?", LocalDate.class, id)).isEqualTo(END);
    }

    /** The version an edit answers with is the row's, so the same tab can save again. */
    @Test
    void theVersionAnEditReturnsIsCurrentSoTheSameTabCanSaveAgain() {
        UUID id = draftWithGrid("60000");
        var first = call(HttpMethod.PUT, "/api/v1/leases/" + id, form("72000", END, versionOf(id)), null);
        long returned = ((Number) first.getBody().get("version")).longValue();
        assertThat(returned).isEqualTo(dbVersion(id));

        var second = call(HttpMethod.PUT, "/api/v1/leases/" + id, form("73000", END, returned), null);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(dbRent(id)).isEqualByComparingTo("73000");
    }

    @Test
    void anEditNamingNoVersionIsAcceptedAsBefore() {
        UUID id = draftWithGrid("60000");
        assertThat(call(HttpMethod.PUT, "/api/v1/leases/" + id, form("72000", END, null), null)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(dbRent(id)).isEqualByComparingTo("72000");
    }

    /** The cheque grid and the rent-free card: a stale If-Match is refused, the current one accepted. */
    @Test
    void chequeGridAndRentFreeWritesCheckIfMatch() {
        UUID id = draftWithGrid("60000");
        long stale = versionOf(id);
        call(HttpMethod.PUT, "/api/v1/leases/" + id, form("72000", END, null), null);
        long current = versionOf(id);
        assertThat(current).isNotEqualTo(stale);

        List<Object> rows = List.of(chequeRow("72000", START), chequeRow("5000", START));
        assertChanged(callAsMap(HttpMethod.PUT, "/api/v1/leases/" + id + "/cheques", rows, String.valueOf(stale)));
        assertChanged(callAsMap(HttpMethod.POST, "/api/v1/leases/" + id + "/cheques/generate",
                Map.of("installments", 4), String.valueOf(stale)));
        assertChanged(call(HttpMethod.PUT, "/api/v1/leases/" + id + "/rent-free-periods", List.of(),
                String.valueOf(stale)));
        assertThat(jdbc.queryForObject("select count(*) from cheques where lease_id = ?", Long.class, id)).isZero();

        var gen = callList(HttpMethod.POST, "/api/v1/leases/" + id + "/cheques/generate",
                Map.of("installments", 4), "\"" + current + "\"");
        assertThat(gen.getStatusCode().value()).isEqualTo(200);
        // The same tab carries on with the version the write answered with.
        var save = callList(HttpMethod.PUT, "/api/v1/leases/" + id + "/cheques", rows, String.valueOf(headerVersion(gen)));
        assertThat(save.getStatusCode().value()).isEqualTo(200);
        assertThat(call(HttpMethod.PUT, "/api/v1/leases/" + id + "/rent-free-periods", List.of(),
                String.valueOf(headerVersion(save))).getStatusCode().value()).isEqualTo(200);
    }

    // ---- review A C1: writes that touch only the lease's child rows ----------

    /** A fee-only edit leaves the leases row's own columns alone; it must still move the version. */
    @Test
    void aFeeOnlyEditMovesTheVersionSoAStalePostIsRefused() {
        UUID id = draftWithGrid("60000");
        long seenByA = versionOf(id);

        var edit = call(HttpMethod.PUT, "/api/v1/leases/" + id, formWithFee("18000", seenByA), null);
        assertThat(edit.getStatusCode().value()).isEqualTo(200);
        assertThat(dbRent(id)).isEqualByComparingTo("60000");
        assertThat(((Number) edit.getBody().get("version")).longValue()).isEqualTo(dbVersion(id)).isNotEqualTo(seenByA);

        // Posted straight away: the version check runs before anything else, so only the fee edit is in play.
        assertChanged(call(HttpMethod.POST, "/api/v1/leases/" + id + "/post", Map.of("version", seenByA), null));
        assertThat(dbStatus(id)).isEqualTo("DRAFT");
        assertThat(tcoCount(id)).isZero();
    }

    /** Two tabs at the same version, each editing only a fee: the second is refused and the first fee kept. */
    @Test
    void aFeeOnlyEditFromAStaleVersionIsRefused() {
        UUID id = draftWithGrid("60000");
        long v = versionOf(id);

        assertThat(call(HttpMethod.PUT, "/api/v1/leases/" + id, formWithFee("18000", v), null)
                .getStatusCode().value()).isEqualTo(200);
        assertChanged(call(HttpMethod.PUT, "/api/v1/leases/" + id, formWithFee("2000", v), null));
        assertThat(lineTotal(id)).isEqualByComparingTo("83000");
    }

    /** Rent-free periods live in their own table; a change there must move the version even when the leases row stays put. */
    @Test
    void aRentFreeChangeMovesTheVersion() {
        UUID id = draftWithGrid("60000");
        long v = versionOf(id);

        var res = call(HttpMethod.PUT, "/api/v1/leases/" + id + "/rent-free-periods",
                // A zero concession: the periods table changes, the RENT line and the leases row do not.
                List.of(Map.of("fromDate", START.toString(), "toDate", START.plusDays(29).toString(),
                        "concessionOverride", 0)),
                String.valueOf(v));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        long returned = ((Number) res.getBody().get("version")).longValue();
        assertThat(returned).isEqualTo(dbVersion(id)).isNotEqualTo(v);

        assertChanged(call(HttpMethod.PUT, "/api/v1/leases/" + id + "/rent-free-periods", List.of(), String.valueOf(v)));
    }

    /** review A M3: a cheque-only save (dates, numbers) moves the version; a tab that saw the old grid cannot post. */
    @Test
    void aChequeOnlySaveMovesTheVersionAndSaysWhereTo() {
        UUID id = draftWithGrid("60000");
        long v = versionOf(id);

        var save = callList(HttpMethod.PUT, "/api/v1/leases/" + id + "/cheques",
                List.of(chequeRow("60000", START), chequeRow("5000", START)), String.valueOf(v));
        assertThat(save.getStatusCode().value()).isEqualTo(200);
        long after = headerVersion(save);
        assertThat(after).isEqualTo(dbVersion(id)).isNotEqualTo(v);

        assertChanged(callAsMap(HttpMethod.PUT, "/api/v1/leases/" + id + "/cheques",
                List.of(chequeRow("60000", START.plusDays(1)), chequeRow("5000", START)), String.valueOf(v)));
        assertChanged(call(HttpMethod.POST, "/api/v1/leases/" + id + "/post", Map.of("version", v), null));
        assertThat(dbStatus(id)).isEqualTo("DRAFT");
    }

    @SuppressWarnings("rawtypes")
    private static long headerVersion(ResponseEntity<? extends Object> res) {
        String h = res.getHeaders().getFirst("X-Lease-Version");
        assertThat(h).as("X-Lease-Version").isNotNull();
        return Long.parseLong(h);
    }

    private CreateLeaseDTO formWithFee(String fee, Long version) {
        CreateLeaseDTO dto = fixtures.draftDto(START, END,
                List.of(line("RENT", "60000"), line("SECURITY_DEPOSIT", "5000"), line("ADMIN_FEE", fee)));
        dto.setContractDate(CONTRACT_DATE);
        dto.setFirstDueDate(START);
        dto.setVersion(version);
        return dto;
    }

    private BigDecimal lineTotal(UUID leaseId) {
        return jdbc.queryForObject("select coalesce(sum(gross_amount), 0) from lease_lines where lease_id = ?",
                BigDecimal.class, leaseId);
    }

    /** A list endpoint's 409 is still a JSON object; read it as one. */
    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> callAsMap(HttpMethod method, String path, Object body, String ifMatch) {
        return call(method, path, body, ifMatch);
    }
}
