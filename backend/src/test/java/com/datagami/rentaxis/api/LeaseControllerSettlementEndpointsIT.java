package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.lease.LeaseTerminationService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may read a settlement statement and who may write one, asserted over HTTP —
 * and the JSON the settlement screen is built against.
 *
 * <p>The two role lists differ on purpose, and it is the same asymmetry
 * preview/terminate draws. <b>The statement</b> writes nothing: it tells the
 * building manager what a move-out in their building costs, which is a question
 * they are entitled to ask. <b>Saving and finalising</b> decide what comes out of a
 * renter's deposit and post a journal; that is the accountant's call.</p>
 *
 * <p><b>403 and 404 mean two different things here and both are asserted.</b> A
 * role the endpoint does not admit is refused by {@code @PreAuthorize} before the
 * request reaches any service — 403. A caller whose role <em>is</em> admitted but
 * who is not entitled to <em>this</em> lease is refused by
 * {@code LeaseAccessPolicy}, which answers <b>404</b>: a 403 on a lease id confirms
 * the lease exists.</p>
 *
 * <p>{@code @PreAuthorize} does nothing in a service-level test — the roles only
 * bite once a request has been through {@code ApiSecurityFilter} — so this is a
 * full-context HTTP test with the legacy {@code X-User-*} headers the Next.js proxy
 * sends, the same shape as {@link LeaseControllerTerminateEndpointsIT}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaseControllerSettlementEndpointsIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LeaseService leaseService;
    @Autowired LeaseTerminationService termination;
    @Autowired ChequeGenerationService chequeGenerationService;
    @Autowired LeasePostingService leasePostingService;
    @Autowired AccountResolver resolver;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;
    @Autowired TransactionTemplate tx;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);
    private static final LocalDate T = LocalDate.of(2027, 2, 15);
    private static final LocalDate SETTLED_ON = LocalDate.of(2027, 2, 20);

    private LeaseTestFixtures fixtures;
    private UUID leaseId;
    /** A second building the property manager was never assigned to. */
    private UUID foreignLeaseId;
    private UUID bankAccountId;
    private User superAdmin;
    private User tenantAdmin;
    private User accountant;
    private User propertyManager;
    private User renter;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGenerationService, leasePostingService);

        // The grid is typed out rather than generated because the cheque dates
        // decide the termination's default split, and therefore whether this
        // settlement refunds or collects. A generator's own instalment dates would
        // make that an accident of the generator.
        leaseId = fixtures.draftLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("SECURITY_DEPOSIT", "3000")));
        chequeGenerationService.saveRows(leaseId, List.of(
                row("200040", LocalDate.of(2026, 9, 20), "3000"),
                row("200041", LocalDate.of(2026, 10, 2), "12750"),
                row("200042", LocalDate.of(2027, 1, 2), "12750"),
                row("200043", LocalDate.of(2027, 4, 2), "12750"),
                row("200044", LocalDate.of(2027, 7, 2), "12750")));
        leasePostingService.post(leaseId);

        Property other = fixtures.createProperty("PALM");
        Unit otherUnit = fixtures.createUnit(other, "201");
        Renter otherRenter = fixtures.createRenter("Other Renter");
        foreignLeaseId = fixtures.postedLease(otherUnit, otherRenter, CONTRACT_DATE, START, END,
                List.of(line("RENT", "36500")), 4, null)
                .lease().getId();

        bankAccountId = tx.execute(s -> resolver.resolve(AccountRole.BANK, fixtures.property().getId()).getId());

        superAdmin = user(UserRole.SUPER_ADMIN);
        tenantAdmin = user(UserRole.TENANT_ADMIN);
        accountant = user(UserRole.ACCOUNTANT);
        propertyManager = user(UserRole.PROPERTY_MANAGER);
        renter = user(UserRole.RENTER);

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
        return spec.retrieve().onStatus(status -> true, (request, response) -> { });
    }

    private HttpStatusCode status(User caller, HttpMethod method, String path, Object body) {
        return request(caller, method, path, body).toBodilessEntity().getStatusCode();
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> body(User caller, HttpMethod method, String path, Object body) {
        return request(caller, method, path, body).toEntity(Map.class);
    }

    private String statementPath(UUID id) {
        return "/api/v1/leases/" + id + "/settlement/preview";
    }

    private String settlementPath(UUID id) {
        return "/api/v1/leases/" + id + "/settlement";
    }

    private String draftPath(UUID id) {
        return "/api/v1/leases/" + id + "/settlement/draft";
    }

    private String finalizePath(UUID id) {
        return "/api/v1/leases/" + id + "/settlement/finalize";
    }

    private Map<String, Object> draftBody() {
        return Map.of("notes", "Keys handed back", "deductions", List.of(Map.of(
                "type", "DEDUCTION", "category", "CLEANING",
                "description", "End-of-tenancy clean", "amount", 500)));
    }

    /**
     * The shape the settlement screen posts. {@code acknowledgeOutstanding} is here
     * because this lease is terminated with its kept paper still on the register,
     * which is the ordinary case — see {@link #theFinanceRolesMaySeeAndSettle}.
     */
    private Map<String, Object> finalizeBody() {
        return Map.of("settlementDate", SETTLED_ON.toString(),
                "refundBankAccountId", bankAccountId.toString(),
                "acknowledgeOutstanding", true);
    }

    private Map<String, Object> finalizeBodyWithoutAcknowledgement() {
        return Map.of("settlementDate", SETTLED_ON.toString(),
                "refundBankAccountId", bankAccountId.toString());
    }

    private void terminate() {
        termination.terminate(leaseId, new TerminateLeaseRequest(T, null, null, "Renter relocating"), null);
    }

    private static BigDecimal number(Object raw) {
        return new BigDecimal(String.valueOf(raw));
    }

    private static ChequeRowInput row(String number, LocalDate chequeDate, String amount) {
        return new ChequeRowInput(null, null, CONTRACT_DATE, number, chequeDate, "Emirates NBD",
                null, null, new BigDecimal(amount), null, null);
    }

    // ------------------------------------------------------------------

    /**
     * The finance roles may do all four, and the bodies are the contract Task 8
     * renders.
     */
    @Test
    void theFinanceRolesMaySeeAndSettle() {
        for (User caller : List.of(superAdmin, tenantAdmin, accountant)) {
            assertThat(status(caller, HttpMethod.GET, statementPath(leaseId), null))
                    .as(caller.getRole() + " statement").isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<Map> statement = body(accountant, HttpMethod.GET, statementPath(leaseId), null);
        assertThat(statement.getBody()).containsKeys("asOf", "earnedRent", "receivedTotal",
                "receivableBalance", "depositsHeld", "penaltiesOutstanding",
                "instrumentsOutstanding", "outstandingInstruments",
                "deductions", "additions",
                "totalDeductions", "totalAdditions", "netRefund", "unrecognisedEntries");
        assertThat(number(statement.getBody().get("depositsHeld"))).isEqualByComparingTo("3000.00");
        // Nothing cleared and nothing recognised, so the receivable is the whole
        // contract and the renter, on paper, owes all of it.
        assertThat(number(statement.getBody().get("receivedTotal"))).isEqualByComparingTo("0.00");

        // A draft is saved by the accountant and comes back as the settlement row.
        ResponseEntity<Map> draft = body(accountant, HttpMethod.POST, draftPath(leaseId), draftBody());
        assertThat(draft.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(draft.getBody().get("status")).isEqualTo("DRAFT");
        assertThat(number(draft.getBody().get("totalDeductions"))).isEqualByComparingTo("500");
        assertThat(draft.getBody()).containsKeys("settlementDate", "earnedRent", "receivedTotal",
                "receivableBalance", "depositsHeld", "penaltiesOutstanding", "balanceDue",
                "refundBankAccountId", "journalId", "journalNumber", "collectionChequeId", "deductions");

        // …and finalise refuses until the contract has ended.
        ResponseEntity<Map> tooEarly = body(accountant, HttpMethod.POST, finalizePath(leaseId), finalizeBody());
        assertThat(tooEarly.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) tooEarly.getBody().get("message")).contains("Terminate the lease before settling it");

        terminate();

        // The termination kept three uncleared rows for collection, so refunding the
        // deposit now is a decision the accountant has to take on purpose. An
        // omitted flag is a plain 400 with the figure in it, not a parse error.
        ResponseEntity<Map> unacknowledged = body(accountant, HttpMethod.POST, finalizePath(leaseId),
                finalizeBodyWithoutAcknowledgement());
        assertThat(unacknowledged.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) unacknowledged.getBody().get("message"))
                .contains("is still outstanding on the cheque register")
                .contains("acknowledge it to refund the deposit anyway");

        ResponseEntity<Map> finalized = body(accountant, HttpMethod.POST, finalizePath(leaseId), finalizeBody());

        assertThat(finalized.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(finalized.getBody().get("status")).isEqualTo("FINALIZED");
        assertThat(finalized.getBody().get("settlementDate")).isEqualTo(SETTLED_ON.toString());
        assertThat((String) finalized.getBody().get("journalNumber")).startsWith("STL");
        assertThat(finalized.getBody().get("journalId")).isNotNull();
        // 3,000 held + 6,357.53 the termination left owing to the renter
        // (25,500 of paper handed back against 31,857.53 of unearned rent) − 500
        // of cleaning. A positive net, so there is nothing left to collect from the
        // settlement itself.
        assertThat(number(finalized.getBody().get("refundAmount"))).isEqualByComparingTo("8857.53");
        assertThat(number(finalized.getBody().get("balanceDue"))).isEqualByComparingTo("0.00");
        // …and the lease is nonetheless still TERMINATED, because §9.1's keep list
        // left three uncleared instruments on this register — nothing in this
        // fixture ever clears — dated 20 Sep, 2 Oct and 2 Jan, all before T. A
        // settlement that refunds does not make paper in the drawer disappear, and
        // a CLOSED lease would refuse to bank it. The contract closes when the last
        // of those clears; ChequeOnEndedLeaseIT walks that through.
        assertThat(leaseService.getLeaseById(leaseId).getStatus()).isEqualTo(LeaseStatus.TERMINATED);

        assertThat(status(accountant, HttpMethod.GET, settlementPath(leaseId), null)).isEqualTo(HttpStatus.OK);
    }

    /**
     * A property manager may read the statement for their own building and may not
     * change it.
     *
     * <p>The refusals are deliberately different codes: the writes are refused by
     * the role gate before any service sees them (403), while reading somebody
     * else's building is refused by {@code LeaseAccessPolicy} as though the lease
     * did not exist (404).</p>
     */
    @Test
    void aPropertyManagerMayReadTheirOwnBuildingAndWriteNothing() {
        ResponseEntity<Map> statement = body(propertyManager, HttpMethod.GET, statementPath(leaseId), null);
        assertThat(statement.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(number(statement.getBody().get("depositsHeld"))).isEqualByComparingTo("3000.00");

        assertThat(status(propertyManager, HttpMethod.GET, statementPath(foreignLeaseId), null))
                .as("a building they were not assigned").isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(status(propertyManager, HttpMethod.POST, draftPath(leaseId), draftBody()))
                .as("saving is the finance roles'").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(propertyManager, HttpMethod.POST, finalizePath(leaseId), finalizeBody()))
                .as("finalising is the finance roles'").isEqualTo(HttpStatus.FORBIDDEN);

        // Nothing was written by any of that.
        assertThat(status(accountant, HttpMethod.GET, settlementPath(leaseId), null))
                .isEqualTo(HttpStatus.NOT_FOUND);

        // …and once a row exists, the manager may read it — the read gate admits
        // them, which is a different question from whether they may write one.
        body(accountant, HttpMethod.POST, draftPath(leaseId), draftBody());
        ResponseEntity<Map> row = body(propertyManager, HttpMethod.GET, settlementPath(leaseId), null);
        assertThat(row.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(row.getBody().get("status")).isEqualTo("DRAFT");
        assertThat(status(propertyManager, HttpMethod.GET, settlementPath(foreignLeaseId), null))
                .as("still nothing outside their buildings").isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** A renter may not look at, save or finalise the settlement of their own contract. */
    @Test
    void aRenterIsRefusedEveryEndpoint() {
        assertThat(status(renter, HttpMethod.GET, statementPath(leaseId), null)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(renter, HttpMethod.GET, settlementPath(leaseId), null)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(renter, HttpMethod.POST, draftPath(leaseId), draftBody())).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(renter, HttpMethod.POST, finalizePath(leaseId), finalizeBody()))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * The category rules and the settlement date arrive as ordinary 400s with the
     * house error shape, which is what the screen shows the user.
     */
    @Test
    void theRefusalsReachTheClientAs400s() {
        ResponseEntity<Map> penalties = body(accountant, HttpMethod.POST, draftPath(leaseId),
                Map.of("deductions", List.of(Map.of(
                        "type", "DEDUCTION", "category", "PENALTIES", "amount", 500))));
        assertThat(penalties.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) penalties.getBody().get("message"))
                .contains("Approved penalties are collected through their own register row")
                .contains("EARLY_TERMINATION_FEE or OTHER");

        body(accountant, HttpMethod.POST, draftPath(leaseId), draftBody());
        terminate();

        ResponseEntity<Map> noDate = body(accountant, HttpMethod.POST, finalizePath(leaseId), Map.of());
        assertThat(noDate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) noDate.getBody().get("message")).contains("needs a settlement date");

        ResponseEntity<Map> beforeTermination = body(accountant, HttpMethod.POST, finalizePath(leaseId),
                Map.of("settlementDate", T.minusDays(1).toString()));
        assertThat(beforeTermination.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) beforeTermination.getBody().get("message"))
                .contains("is before the lease was terminated");

        assertThat(leaseService.getLeaseById(leaseId).getStatus()).isEqualTo(LeaseStatus.TERMINATED);
    }
}
