package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The register over HTTP: who may work it, and what each verb does to the row.
 *
 * <p>Method security is what these endpoints are guarded by, and
 * {@code @PreAuthorize} does nothing in a service-level test — the roles only bite
 * once a request has been through {@code ApiSecurityFilter}. So this is a
 * full-context HTTP test with the legacy {@code X-User-*} headers the Next.js
 * proxy sends, the same shape as {@link LeaseControllerChequeEndpointsIT}.</p>
 *
 * <p>The interesting split is deposit/clear against cancel. A property manager
 * runs collections for their buildings, so they bank and clear; cancelling
 * reverses the registering journal, which is a finance correction, so it stops at
 * SUPER_ADMIN / TENANT_ADMIN / ACCOUNTANT.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ChequeControllerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort int port;

    @Autowired LeaseService leaseService;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService generation;
    @Autowired ChequeService chequeService;
    @Autowired ChequeRepository chequeRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired AccountResolver resolver;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;
    @Autowired JournalEntryRepository entries;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 1, 5);
    private static final LocalDate START = LocalDate.of(2026, 2, 1);
    private static final LocalDate END = LocalDate.of(2027, 1, 31);

    private LeaseTestFixtures fixtures;
    private UUID leaseId;
    private List<Cheque> register;
    private User accountant;
    private User propertyManager;
    private User renter;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);

        leaseId = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000")), 4, "100040").lease().getId();
        register = reread();

        accountant = user(UserRole.ACCOUNTANT);
        propertyManager = user(UserRole.PROPERTY_MANAGER);
        renter = user(UserRole.RENTER);

        // Without the assignment a PROPERTY_MANAGER is scoped to no properties at
        // all and the policy answers 404 — a different failure from the one these
        // tests are about.
        UserPropertyAssignment assignment = new UserPropertyAssignment();
        assignment.setUserId(propertyManager.getId());
        assignment.setPropertyId(fixtures.property().getId());
        assignmentRepo.save(assignment);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private List<Cheque> reread() {
        return tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId));
    }

    private Cheque reread(UUID chequeId) {
        return tx.execute(s -> chequeRepo.findById(chequeId).orElseThrow());
    }

    private LocalDate entryDate(UUID entryId) {
        return tx.execute(s -> entries.findById(entryId).orElseThrow().getEntryDate());
    }

    private long registerSize() {
        return reread().size();
    }

    private long journalEntryCount() {
        return jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ?",
                Long.class, fixtures.tenantId());
    }

    private User user(UserRole role) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(fixtures.tenantId());
        return userRepo.save(u);
    }

    private RestClient.ResponseSpec request(User caller, HttpMethod method, String path, Object body) {
        RestClient.RequestBodySpec spec = RestClient.builder()
                .baseUrl("http://localhost:" + port).build()
                .method(method).uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString());
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        // Never throw on a 4xx: the status is the assertion.
        return spec.retrieve().onStatus(status -> true, (req, res) -> { });
    }

    private HttpStatusCode status(User caller, HttpMethod method, String path, Object body) {
        return request(caller, method, path, body).toBodilessEntity().getStatusCode();
    }

    private ResponseEntity<Map> map(User caller, HttpMethod method, String path, Object body) {
        return request(caller, method, path, body).toEntity(Map.class);
    }

    // ------------------------------------------------------------------
    // the happy path
    // ------------------------------------------------------------------

    /** Bank the paper, then record that it cleared — the whole collection loop. */
    @Test
    void depositThenClearMovesTheRowAndWritesOneClearingJournal() {
        UUID chequeId = register.getFirst().getId();

        ResponseEntity<Map> deposited = map(accountant, HttpMethod.PUT,
                "/api/v1/cheques/" + chequeId + "/deposit", Map.of("date", "2026-02-02"));
        assertThat(deposited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deposited.getBody().get("status")).isEqualTo("DEPOSITED");
        // Banking posts nothing: the landlord holds the same claim, in a different place.
        assertThat(reread(chequeId).getCrtJournalId()).isNull();

        ResponseEntity<Map> cleared = map(accountant, HttpMethod.PUT,
                "/api/v1/cheques/" + chequeId + "/clear", Map.of("date", "2026-02-05"));
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody().get("status")).isEqualTo("CLEARED");
        assertThat(reread(chequeId).getCrtJournalId()).isNotNull();
    }

    /** The tiles, read after the register has been worked. */
    @Test
    void summaryReportsWhatTheRegisterHoldsAfterTheDayIsWorked() {
        UUID first = register.getFirst().getId();
        status(accountant, HttpMethod.PUT, "/api/v1/cheques/" + first + "/deposit",
                Map.of("date", "2026-02-02"));

        ResponseEntity<Map> summary = map(accountant, HttpMethod.GET,
                "/api/v1/cheques/summary?asOf=2026-02-10", null);

        assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) summary.getBody().get("registeredCount")).intValue()).isEqualTo(3);
        assertThat(((Number) summary.getBody().get("depositedCount")).intValue()).isEqualTo(1);
    }

    /** The day's deposit run is the matured, unbanked paper and nothing else. */
    @Test
    void toDepositListsOnlyMaturedRegisteredPdcRows() {
        ResponseEntity<Map> page = map(accountant, HttpMethod.GET,
                "/api/v1/cheques/to-deposit?asOf=2026-02-05", null);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> content = (List<?>) page.getBody().get("content");
        assertThat(content).hasSize(1);
        assertThat(((Map<?, ?>) content.getFirst()).get("chequeNumber")).isEqualTo("100040");
    }

    // ------------------------------------------------------------------
    // who may do what
    // ------------------------------------------------------------------

    /** A manager assigned to the building runs its collections. */
    @Test
    void assignedPropertyManagerMayDepositAndClear() {
        UUID chequeId = register.getFirst().getId();

        assertThat(status(propertyManager, HttpMethod.PUT,
                "/api/v1/cheques/" + chequeId + "/deposit", Map.of("date", "2026-02-02")))
                .isEqualTo(HttpStatus.OK);
        assertThat(status(propertyManager, HttpMethod.PUT,
                "/api/v1/cheques/" + chequeId + "/clear", Map.of("date", "2026-02-05")))
                .isEqualTo(HttpStatus.OK);
        assertThat(status(propertyManager, HttpMethod.GET, "/api/v1/cheques/summary", null))
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * Cancelling reverses the registering journal. That is a correction to the
     * books, not a collection step, so a property manager is refused — and the row
     * is still REGISTERED afterwards.
     */
    @Test
    void propertyManagerMayNotCancel() {
        UUID chequeId = register.get(1).getId();

        assertThat(status(propertyManager, HttpMethod.PUT,
                "/api/v1/cheques/" + chequeId + "/cancel", Map.of("notes", "typo")))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.REGISTERED);

        assertThat(status(accountant, HttpMethod.PUT,
                "/api/v1/cheques/" + chequeId + "/cancel", Map.of("notes", "entered in error")))
                .isEqualTo(HttpStatus.OK);
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.CANCELLED);
    }

    /** A renter reaches their receipt and nothing else on the register. */
    @Test
    void renterIsRefusedEveryRegisterEndpoint() {
        UUID chequeId = register.getFirst().getId();

        assertThat(status(renter, HttpMethod.GET, "/api/v1/cheques", null))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(renter, HttpMethod.GET, "/api/v1/cheques/summary", null))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(renter, HttpMethod.PUT, "/api/v1/cheques/" + chequeId + "/deposit",
                Map.of("date", "2026-02-02")))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(renter, HttpMethod.PUT, "/api/v1/cheques/" + chequeId + "/clear",
                Map.of("date", "2026-02-02")))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.REGISTERED);
    }

    // ------------------------------------------------------------------
    // details, cash receipt, bounce
    // ------------------------------------------------------------------

    /** Correcting the number on a cheque still in the drawer writes no journal. */
    @Test
    void detailsRewritesNumberAndBankOnARegisteredRow() {
        UUID chequeId = register.get(2).getId();

        ResponseEntity<Map> updated = map(accountant, HttpMethod.PUT,
                "/api/v1/cheques/" + chequeId + "/details",
                Map.of("chequeNumber", "555001", "payeeBank", "Mashreq", "chequeDate", "2026-08-09"));

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        Cheque after = reread(chequeId);
        assertThat(after.getChequeNumber()).isEqualTo("555001");
        assertThat(after.getPayeeBank()).isEqualTo("Mashreq");
        assertThat(after.getChequeDate()).isEqualTo(LocalDate.of(2026, 8, 9));
        assertThat(after.getCrtJournalId()).isNull();
    }

    /**
     * A banked cheque's number is on a deposit slip the bank is processing.
     * Renumbering it here would leave the register describing a different
     * instrument from the one in the clearing system.
     */
    @Test
    void detailsRefusesADepositedRowAndChangesNothing() {
        UUID chequeId = register.get(2).getId();
        chequeService.deposit(chequeId, ChequeActionRequest.on(LocalDate.of(2026, 8, 2)));
        String numberBefore = reread(chequeId).getChequeNumber();

        assertThat(status(accountant, HttpMethod.PUT, "/api/v1/cheques/" + chequeId + "/details",
                Map.of("chequeNumber", "999999", "payeeBank", "Mashreq")))
                .isEqualTo(HttpStatus.BAD_REQUEST);

        Cheque after = reread(chequeId);
        assertThat(after.getChequeNumber()).isEqualTo(numberBefore);
        assertThat(after.getPayeeBank()).isNotEqualTo("Mashreq");
    }

    /**
     * Cash over the counter: the row is added to the posted lease and received in
     * one call, so the register never shows a receipt that exists but has not
     * arrived.
     */
    @Test
    void cashReceiptCreatesAndReceivesInOneCall() {
        ResponseEntity<Map> created = map(accountant, HttpMethod.POST,
                "/api/v1/cheques/lease/" + leaseId + "/cash-receipt",
                Map.of("amount", 1500, "chequeDate", "2026-09-10", "mode", "CASH",
                        "narration", "Counter receipt"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody().get("status")).isEqualTo("CLEARED");
        UUID id = UUID.fromString((String) created.getBody().get("id"));
        Cheque row = reread(id);
        // PDR then CRT: registered, then settled.
        assertThat(row.getPdrJournalId()).isNotNull();
        assertThat(row.getCrtJournalId()).isNotNull();
        assertThat(row.getClearedAt()).isEqualTo(LocalDate.of(2026, 9, 10));
    }

    /**
     * Cash taken on Friday, written up on Monday: both journals file on the day the
     * money moved.
     *
     * <p>The row's posting date defaults to its own cheque date rather than to
     * today, so a back-dated receipt cannot produce a {@code PDR} dated <em>after</em>
     * the {@code CRT} that settles it — an instrument that cleared before it was
     * registered, which no reconciliation can explain.</p>
     */
    @Test
    void aBackDatedCashReceiptFilesBothJournalsOnTheRowsOwnDate() {
        ResponseEntity<Map> created = map(accountant, HttpMethod.POST,
                "/api/v1/cheques/lease/" + leaseId + "/cash-receipt",
                Map.of("amount", 2500, "chequeDate", "2026-07-03", "mode", "CASH",
                        "narration", "Counter receipt, written up late"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID id = UUID.fromString((String) created.getBody().get("id"));
        Cheque row = reread(id);
        assertThat(row.getPostingDate()).isEqualTo(LocalDate.of(2026, 7, 3));
        assertThat(row.getClearedAt()).isEqualTo(LocalDate.of(2026, 7, 3));
        assertThat(entryDate(row.getPdrJournalId())).isEqualTo(LocalDate.of(2026, 7, 3));
        assertThat(entryDate(row.getCrtJournalId())).isEqualTo(LocalDate.of(2026, 7, 3));
        assertThat(entryDate(row.getPdrJournalId()))
                .isBeforeOrEqualTo(entryDate(row.getCrtJournalId()));
    }

    /**
     * The two halves are one act. When the receiving half refuses, the row it would
     * have received must not be left behind — a registered instrument for money the
     * counter never took, with a PDR raising a receivable against it.
     */
    @Test
    void aCashReceiptThatCannotBeReceivedLeavesNoRowAndNoJournal() {
        long rowsBefore = registerSize();
        long entriesBefore = journalEntryCount();

        // A debit account that is not a bank or cash leaf: accepted while the row is
        // built, refused by the clearing half.
        UUID receivable = tx.execute(s ->
                resolver.resolve(AccountRole.RENT_RECEIVABLE, fixtures.property().getId()).getId());

        assertThat(status(accountant, HttpMethod.POST,
                "/api/v1/cheques/lease/" + leaseId + "/cash-receipt",
                Map.of("amount", 1500, "chequeDate", "2026-09-10", "mode", "CASH",
                        "debitAccountId", receivable.toString())))
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(registerSize()).isEqualTo(rowsBefore);
        assertThat(journalEntryCount()).isEqualTo(entriesBefore);
    }

    /**
     * A contract whose term has run out takes no counter receipt.
     *
     * <p>Review I2: this endpoint is the one user-facing door into
     * {@code addRowToPostedLease}, and until now it admitted an EXPIRED lease — so a
     * finance user could raise a CASH row, and a {@code PDR} with it, against a
     * tenancy that had ended. Money owed on an expired lease is collected through
     * its settlement (spec §9.2), which has its own internal door; approving a
     * penalty on one still works and goes through that same door.</p>
     */
    @Test
    void cashReceiptIsRefusedOnAnExpiredLease() {
        long rowsBefore = registerSize();
        long entriesBefore = journalEntryCount();
        // The real path, not a status poke: the term ends 31 Jan 2027, so the
        // nightly sweep run on 1 Feb expires it.
        tx.executeWithoutResult(s -> leaseService.markExpired(leaseId, END.plusDays(1)));
        assertThat(leaseService.getLeaseById(leaseId).getStatus()).isEqualTo(LeaseStatus.EXPIRED);

        ResponseEntity<Map> refused = map(accountant, HttpMethod.POST,
                "/api/v1/cheques/lease/" + leaseId + "/cash-receipt",
                Map.of("amount", 1500, "chequeDate", "2027-02-02", "mode", "CASH",
                        "narration", "Last month, over the counter"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) refused.getBody().get("message")).contains("This lease is EXPIRED");
        assertThat(registerSize()).as("no row").isEqualTo(rowsBefore);
        assertThat(journalEntryCount()).as("and no PDR").isEqualTo(entriesBefore);
    }

    /** A PDC is paper to be banked, not something that arrives over the counter. */
    @Test
    void cashReceiptRefusesAPdcRow() {
        assertThat(status(accountant, HttpMethod.POST,
                "/api/v1/cheques/lease/" + leaseId + "/cash-receipt",
                Map.of("amount", 1500, "chequeDate", "2026-09-10", "mode", "PDC")))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** "Insufficient funds" and "signature mismatch" lead somewhere different. */
    @Test
    void bounceWithoutAReasonIsRefused() {
        UUID chequeId = register.getFirst().getId();
        status(accountant, HttpMethod.PUT, "/api/v1/cheques/" + chequeId + "/deposit",
                Map.of("date", "2026-02-02"));

        assertThat(status(accountant, HttpMethod.PUT, "/api/v1/cheques/" + chequeId + "/bounce",
                Map.of("date", "2026-02-06")))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.DEPOSITED);

        assertThat(status(accountant, HttpMethod.PUT, "/api/v1/cheques/" + chequeId + "/bounce",
                Map.of("date", "2026-02-06", "failureReason", "BOUNCE")))
                .isEqualTo(HttpStatus.OK);
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.BOUNCED);
    }

    /** One row, fetched by id, with the flags the screen renders. */
    @Test
    void getReturnsTheRowWithItsDerivedLatenessFlags() {
        ResponseEntity<Map> row = map(accountant, HttpMethod.GET,
                "/api/v1/cheques/" + register.getFirst().getId(), null);

        assertThat(row.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(row.getBody().get("chequeNumber")).isEqualTo("100040");
        assertThat(row.getBody()).containsKeys("due", "overdue", "daysOverdue");
    }

    /** Per-lease stats arrive in one round trip rather than one per row of a table. */
    @Test
    void statsByLeasesAnswersForTheWholeBatch() {
        ResponseEntity<List> stats = request(accountant, HttpMethod.POST,
                "/api/v1/cheques/stats-by-leases", List.of(leaseId)).toEntity(List.class);

        assertThat(stats.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stats.getBody()).hasSize(1);
        Map<?, ?> first = (Map<?, ?>) stats.getBody().getFirst();
        assertThat(((Number) first.get("total")).intValue()).isEqualTo(4);
        assertThat(((Number) first.get("cleared")).intValue()).isZero();
    }

    /** A malformed month is a sentence, not a 500. */
    @Test
    void postDatedRefusesAMonthItCannotParse() {
        assertThat(status(accountant, HttpMethod.GET, "/api/v1/cheques/post-dated?month=Feb-2026", null))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(accountant, HttpMethod.GET, "/api/v1/cheques/post-dated?month=2026-02", null))
                .isEqualTo(HttpStatus.OK);
    }

    /** The receipt endpoint folded in from the temporary controller keeps its path. */
    @Test
    void receiptPathStillAnswersForAClearedRow() {
        UUID chequeId = register.getFirst().getId();
        chequeService.deposit(chequeId, ChequeActionRequest.on(LocalDate.of(2026, 2, 2)));
        chequeService.clear(chequeId, ChequeActionRequest.on(LocalDate.of(2026, 2, 5)));

        ResponseEntity<byte[]> pdf = request(accountant, HttpMethod.GET,
                "/api/v1/cheques/" + chequeId + "/receipt", null).toEntity(byte[].class);

        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdf.getBody()).isNotEmpty();
    }

    /** Sanity: the fixture really did register four numbered instruments. */
    @Test
    void theFixtureIsTheRegisterUnderTest() {
        assertThat(register).hasSize(4);
        assertThat(register).extracting(Cheque::getStatus)
                .containsOnly(ChequeStatus.REGISTERED);
    }
}
