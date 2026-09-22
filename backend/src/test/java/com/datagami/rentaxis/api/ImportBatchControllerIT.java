package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.cutover.ContractImportPostJobService;
import com.datagami.rentaxis.core.service.cutover.ImportBatchService;
import com.datagami.rentaxis.core.service.cutover.LeaseReverter;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.ImportBatch;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * The cut-over batches API over HTTP: the role gate, the tenant-less SUPER_ADMIN
 * guard, and the one act the screen offers.
 *
 * <p>{@code @PreAuthorize} does nothing in a service-level test — the roles only
 * bite once a request has been through {@code ApiSecurityFilter} — so this is a
 * full-context HTTP test with the legacy {@code X-User-*} headers the Next.js
 * proxy sends, the same shape as {@code VoucherControllerIT}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ImportBatchControllerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort int port;

    @Autowired ObjectMapper json;
    @Autowired AccountService accounts;
    @Autowired PostingService posting;
    @Autowired ImportBatchService batches;
    @Autowired ContractImportPostJobService postJobs;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired JournalEntryRepository entries;
    @Autowired TransactionTemplate tx;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;

    /** Plan 4 Task 11 implements the seam on LeaseService; the HTTP contract does not wait for it. */
    @MockitoBean LeaseReverter leaseReverter;

    UUID tenantId;
    Account receivable, advanceRent;
    User accountant, tenantAdmin, superAdmin, propertyManager, tenantUser, renter;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("IBCtl-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        receivable = accounts.createLeaf("Rent Receivable - Tulip 7", accounts.getAccountByCode("A-02-01"), null);
        advanceRent = accounts.createLeaf("Advance Rent - Tulip 7", accounts.getAccountByCode("B-01-01"), null);
        fiscal.setBooksStartDate(LocalDate.of(2026, 10, 1));
        fiscal.lockThrough(LocalDate.of(2026, 9, 30));

        accountant = user(UserRole.ACCOUNTANT);
        tenantAdmin = user(UserRole.TENANT_ADMIN);
        superAdmin = user(UserRole.SUPER_ADMIN);
        propertyManager = user(UserRole.PROPERTY_MANAGER);
        tenantUser = user(UserRole.TENANT_USER);
        renter = user(UserRole.RENTER);
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private User user(UserRole role) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        return userRepo.save(u);
    }

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private ResponseEntity<String> call(HttpMethod method, String path, User caller, Object body) {
        RestClient.RequestBodySpec spec = client().method(method)
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString());
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    /** What the proxy sends for a SUPER_ADMIN who has not picked an organisation in the switcher. */
    private ResponseEntity<String> callWithoutTenant(HttpMethod method, String path, User caller, Object body) {
        RestClient.RequestBodySpec spec = client().method(method)
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name());
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    private JsonNode json(ResponseEntity<String> res) {
        try {
            return json.readTree(res.getBody());
        } catch (Exception e) {
            throw new AssertionError("Response was not JSON: " + res.getBody(), e);
        }
    }

    /** A batch with one lease and one posted journal, ready to be reversed. */
    private ImportBatch postedBatch(UUID leaseId) {
        ImportBatch b = batches.create(null, "September cut-over");
        batches.linkLease(b.getId(), leaseId);
        posting.post(new PostingRequest(
                JournalDocType.TCO, LocalDate.of(2026, 9, 11), "Imported contract",
                PostingRequest.Dimensions.none(), JournalSourceType.IMPORT, UUID.randomUUID(), b.getId(),
                List.of(PostingRequest.dr(receivable.getId(), new BigDecimal("61000.00")),
                        PostingRequest.cr(advanceRent.getId(), new BigDecimal("61000.00")))));
        return batches.markPosted(b.getId(), 1);
    }

    /**
     * The body the endpoint takes now: a reason and nothing else. The mirror is
     * always dated on the entry it reverses (review C1, ruling R16).
     */
    private static Map<String, Object> reverseBody(String reason) {
        return Map.of("reason", reason);
    }

    // ------------------------------------------------------------------
    // the endpoints
    // ------------------------------------------------------------------

    @Test
    void anAccountantListsGetsAndReversesABatch() {
        UUID leaseId = UUID.randomUUID();
        ImportBatch b = postedBatch(leaseId);

        JsonNode list = json(call(HttpMethod.GET, "/api/v1/finance/import-batches", accountant, null));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("id").asText()).isEqualTo(b.getId().toString());
        assertThat(list.get(0).get("status").asText()).isEqualTo("POSTED");
        assertThat(list.get(0).get("label").asText()).isEqualTo("September cut-over");
        assertThat(list.get(0).get("kind").asText()).isEqualTo("CONTRACT_IMPORT");
        assertThat(list.get(0).get("leasesImported").asInt()).isEqualTo(1);
        assertThat(list.get(0).get("journalsPosted").asInt()).isEqualTo(1);

        JsonNode one = json(call(HttpMethod.GET, "/api/v1/finance/import-batches/" + b.getId(), accountant, null));
        assertThat(one.get("id").asText()).isEqualTo(b.getId().toString());

        ResponseEntity<String> reversed = call(HttpMethod.POST,
                "/api/v1/finance/import-batches/" + b.getId() + "/reverse", accountant,
                reverseBody("Re-import with corrected rents"));
        assertThat(reversed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(reversed).get("status").asText()).isEqualTo("REVERSED");
        verify(leaseReverter).revertToDraft(leaseId);
    }

    /** A batch id from another organisation is a 404, not a 403 — see ImportBatchService.get. */
    @Test
    void anUnknownBatchIs404() {
        ResponseEntity<String> res = call(HttpMethod.GET,
                "/api/v1/finance/import-batches/" + UUID.randomUUID(), accountant, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** A DRAFT batch has nothing to reverse: a 400 that says so, not a 500. */
    @Test
    void reversingADraftBatchIs400() {
        ImportBatch b = batches.create(null, "not posted yet");
        ResponseEntity<String> res = call(HttpMethod.POST,
                "/api/v1/finance/import-batches/" + b.getId() + "/reverse", accountant,
                reverseBody("x"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("DRAFT");
    }

    /**
     * The reverse endpoint no longer takes a date (review C1, ruling R16), and a
     * client that has not caught up must not break on it.
     *
     * <p>Spring Boot leaves Jackson's {@code FAIL_ON_UNKNOWN_PROPERTIES} off, so the
     * stale field is ignored rather than rejected — which is what lets the web drop
     * the reverse dialog's date picker in its own release instead of this one. The
     * date sent here is in the OPEN period on purpose: if it were honoured the
     * mirror would land there, and the assertion below would find it.</p>
     */
    @Test
    void aReverseBodyStillCarryingTheOldDateFieldIsAcceptedAndTheDateIsIgnored() {
        ImportBatch b = postedBatch(UUID.randomUUID());

        ResponseEntity<String> res = call(HttpMethod.POST,
                "/api/v1/finance/import-batches/" + b.getId() + "/reverse", accountant,
                Map.of("date", "2026-11-15", "reason", "x"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(res).get("status").asText()).isEqualTo("REVERSED");
        List<JournalEntry> written = tx.execute(s -> entries.findByImportBatchIdOrderByCreatedAtAsc(b.getId()));
        assertThat(written).hasSize(2)
                .allSatisfy(e -> assertThat(e.getEntryDate()).isEqualTo(LocalDate.of(2026, 9, 11)));
    }

    /** An empty body is a reverse with no reason given, not a 400. */
    @Test
    void aReversalWithNoBodyIsAccepted() {
        ImportBatch b = postedBatch(UUID.randomUUID());
        ResponseEntity<String> res = call(HttpMethod.POST,
                "/api/v1/finance/import-batches/" + b.getId() + "/reverse", accountant, Map.of());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(batches.get(b.getId()).getStatus().name()).isEqualTo("REVERSED");
    }

    @ParameterizedTest
    @ValueSource(strings = { "ACCOUNTANT", "TENANT_ADMIN", "SUPER_ADMIN" })
    void financeRolesMayReadTheBatches(String role) {
        User caller = switch (role) {
            case "ACCOUNTANT" -> accountant;
            case "TENANT_ADMIN" -> tenantAdmin;
            default -> superAdmin;
        };
        assertThat(call(HttpMethod.GET, "/api/v1/finance/import-batches", caller, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest
    @ValueSource(strings = { "PROPERTY_MANAGER", "TENANT_USER", "RENTER" })
    void everyOtherRoleIsForbidden(String role) {
        User caller = switch (role) {
            case "PROPERTY_MANAGER" -> propertyManager;
            case "TENANT_USER" -> tenantUser;
            default -> renter;
        };
        assertThat(call(HttpMethod.GET, "/api/v1/finance/import-batches", caller, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/api/v1/finance/import-batches/" + UUID.randomUUID() + "/reverse",
                caller, reverseBody("x")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * A SUPER_ADMIN with no organisation selected reaches the handler authorised but
     * with an empty {@code TenantContextHolder} — which leaves the Hibernate tenant
     * filter off, so the list would answer with every organisation's batches. A
     * clean 400 instead, in the same words the other finance screens use.
     */
    @Test
    void aTenantLessSuperAdminGetsACleanBadRequest() {
        postedBatch(UUID.randomUUID());

        for (String path : List.of("/api/v1/finance/import-batches",
                "/api/v1/finance/import-batches/" + UUID.randomUUID())) {
            ResponseEntity<String> res = callWithoutTenant(HttpMethod.GET, path, superAdmin, null);
            assertThat(res.getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody()).as(path).contains("Select an organisation first");
        }

        ResponseEntity<String> reverse = callWithoutTenant(HttpMethod.POST,
                "/api/v1/finance/import-batches/" + UUID.randomUUID() + "/reverse", superAdmin,
                reverseBody("x"));
        assertThat(reverse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reverse.getBody()).contains("Select an organisation first");
    }

    // ------------------------------------------------------------------
    // bulk post, its job, and discard
    // ------------------------------------------------------------------

    /**
     * The shape the web wires against: Post answers at once with a job id, and the
     * job is polled until it reaches a terminal status and carries its result.
     *
     * <p>The batch here has no contracts, which is deliberate — what this test is
     * about is the HTTP contract and the executor plumbing (the tenant context and
     * the caller's authentication both have to be re-installed on that thread, and
     * neither is inherited). What a real portfolio does is
     * {@code ContractImportPostIT}'s subject.</p>
     */
    @Test
    void postingABatchStartsAJobAndTheJobCarriesItsResult() {
        ImportBatch b = batches.create(null, "September cut-over");

        ResponseEntity<String> started = call(HttpMethod.POST,
                "/api/v1/finance/import-batches/" + b.getId() + "/post", accountant, null);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(started);
        assertThat(body.get("batchId").asText()).isEqualTo(b.getId().toString());
        String jobId = body.get("jobId").asText();

        JsonNode job = awaitTerminalPostJob(b.getId(), jobId);
        assertThat(job.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(job.get("jobId").asText()).isEqualTo(jobId);
        assertThat(job.get("total").asInt()).isZero();
        assertThat(job.get("processed").asInt()).isZero();
        JsonNode result = job.get("result");
        assertThat(result.get("batchId").asText()).isEqualTo(b.getId().toString());
        assertThat(result.get("repostOf").isNull()).isTrue();
        assertThat(result.get("journalsPosted").asInt()).isZero();
        assertThat(result.get("leasesPosted").asInt()).isZero();
        assertThat(result.get("leases").isArray()).isTrue();
        assertThat(result.get("failures").isArray()).isTrue();
        // Nothing posted, so the batch is left DRAFT: "Reverse" on it would be a
        // button with nothing to undo.
        assertThat(batches.get(b.getId()).getStatus().name()).isEqualTo("DRAFT");
    }

    /** A batch-level refusal lands on the job as an error, not as a 500 on the poll. */
    @Test
    void aBatchThatCannotBePostedFailsItsJobWithASentence() {
        fiscal.setBooksStartDate(null);
        ImportBatch b = batches.create(null, "no books start date");

        String jobId = json(call(HttpMethod.POST,
                "/api/v1/finance/import-batches/" + b.getId() + "/post", accountant, null))
                .get("jobId").asText();

        JsonNode job = awaitTerminalPostJob(b.getId(), jobId);
        assertThat(job.get("status").asText()).isEqualTo("FAILED");
        assertThat(job.get("result").isNull()).isTrue();
        assertThat(job.get("errors").get(0).get("message").asText()).contains("books start date");
    }

    @Test
    void anAccountantDiscardsADraftBatch() {
        ImportBatch b = batches.create(null, "wrong workbook");

        ResponseEntity<String> res = call(HttpMethod.POST,
                "/api/v1/finance/import-batches/" + b.getId() + "/discard", accountant, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(res);
        assertThat(body.get("status").asText()).isEqualTo("DISCARDED");
        assertThat(body.get("leasesDeleted").asInt()).isZero();
        assertThat(body.get("kept").isArray()).isTrue();
        assertThat(batches.get(b.getId()).getStatus().name()).isEqualTo("DISCARDED");
    }

    @Test
    void discardingAPostedBatchIs400() {
        ImportBatch b = postedBatch(UUID.randomUUID());
        ResponseEntity<String> res = call(HttpMethod.POST,
                "/api/v1/finance/import-batches/" + b.getId() + "/discard", accountant, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("reverse it first");
    }

    /** The drill-through the batches screen needs: the journals this batch wrote. */
    @Test
    void theJournalSearchFiltersByImportBatch() {
        ImportBatch mine = postedBatch(UUID.randomUUID());
        ImportBatch other = postedBatch(UUID.randomUUID());

        JsonNode page = json(call(HttpMethod.GET,
                "/api/v1/finance/journals?importBatchId=" + mine.getId(), accountant, null));
        assertThat(page.get("totalElements").asInt()).isEqualTo(1);
        assertThat(page.get("content").get(0).get("docType").asText()).isEqualTo("TCO");

        // Without the filter, both batches' entries come back — so the filter really
        // is what narrowed it.
        assertThat(json(call(HttpMethod.GET, "/api/v1/finance/journals", accountant, null))
                .get("totalElements").asInt()).isEqualTo(2);
        assertThat(json(call(HttpMethod.GET,
                "/api/v1/finance/journals?importBatchId=" + other.getId(), accountant, null))
                .get("totalElements").asInt()).isEqualTo(1);
    }

    /** Post and discard are finance acts like the rest of this controller. */
    @ParameterizedTest
    @ValueSource(strings = { "PROPERTY_MANAGER", "TENANT_USER", "RENTER" })
    void everyOtherRoleIsForbiddenFromPostingAndDiscarding(String role) {
        User caller = switch (role) {
            case "PROPERTY_MANAGER" -> propertyManager;
            case "TENANT_USER" -> tenantUser;
            default -> renter;
        };
        UUID id = UUID.randomUUID();
        assertThat(call(HttpMethod.POST, "/api/v1/finance/import-batches/" + id + "/post", caller, null)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/api/v1/finance/import-batches/" + id + "/discard", caller, null)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/v1/finance/import-batches/" + id + "/post/" + UUID.randomUUID(),
                caller, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aTenantLessSuperAdminCannotPostOrDiscardEither() {
        UUID id = UUID.randomUUID();
        for (String path : List.of("/api/v1/finance/import-batches/" + id + "/post",
                "/api/v1/finance/import-batches/" + id + "/discard")) {
            ResponseEntity<String> res = callWithoutTenant(HttpMethod.POST, path, superAdmin, null);
            assertThat(res.getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody()).as(path).contains("Select an organisation first");
        }
        ResponseEntity<String> poll = callWithoutTenant(HttpMethod.GET,
                "/api/v1/finance/import-batches/" + id + "/post/" + UUID.randomUUID(), superAdmin, null);
        assertThat(poll.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(poll.getBody()).contains("Select an organisation first");
    }

    /** Another organisation's batch id is not there, on the new verbs too. */
    @Test
    void anotherOrganisationsBatchIs404OnPostAndDiscard() {
        LandlordOrg other = new LandlordOrg();
        other.setName("Other-" + UUID.randomUUID());
        UUID otherTenant = orgRepo.save(other).getId();
        TenantContextHolder.setTenantId(otherTenant);
        ImportBatch theirs = batches.create(null, "theirs");
        TenantContextHolder.setTenantId(tenantId);

        assertThat(call(HttpMethod.POST, "/api/v1/finance/import-batches/" + theirs.getId() + "/post",
                accountant, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST, "/api/v1/finance/import-batches/" + theirs.getId() + "/discard",
                accountant, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * And another organisation's <em>job</em> id, which is the third door onto the
     * same run (review M6). The poll carries the batch in its path, so the guard has
     * two halves: the job must be this tenant's, and it must be this batch's.
     */
    @Test
    void anotherOrganisationsPostJobIs404() {
        LandlordOrg other = new LandlordOrg();
        other.setName("Other-" + UUID.randomUUID());
        UUID otherTenant = orgRepo.save(other).getId();
        TenantContextHolder.setTenantId(otherTenant);
        ImportBatch theirs = batches.create(null, "theirs");
        UUID theirJob = postJobs.start(theirs.getId(), null).getId();
        TenantContextHolder.setTenantId(tenantId);

        ImportBatch mine = batches.create(null, "mine");
        // Their job id, on their batch: not this organisation's to read.
        assertThat(call(HttpMethod.GET,
                "/api/v1/finance/import-batches/" + theirs.getId() + "/post/" + theirJob, accountant, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Their job id, dressed up as one of mine: the batch-of-job check refuses it.
        assertThat(call(HttpMethod.GET,
                "/api/v1/finance/import-batches/" + mine.getId() + "/post/" + theirJob, accountant, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * The job row is polled the way the web polls it. The run happens on the import
     * executor, so it really is asynchronous and the first poll can still say
     * POSTING.
     */
    private JsonNode awaitTerminalPostJob(UUID batchId, String jobId) {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode job = json(call(HttpMethod.GET,
                    "/api/v1/finance/import-batches/" + batchId + "/post/" + jobId, accountant, null));
            String status = job.get("status").asText();
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) return job;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for bulk-post job " + jobId, e);
            }
        }
        throw new AssertionError("Bulk-post job " + jobId + " never reached a terminal status");
    }

    @Test
    void anAnonymousCallerIsRejected() {
        ResponseEntity<String> res = client().get().uri("/api/v1/finance/import-batches")
                .retrieve().onStatus(s -> true, (req, res2) -> { }).toEntity(String.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isFalse();
    }
}
