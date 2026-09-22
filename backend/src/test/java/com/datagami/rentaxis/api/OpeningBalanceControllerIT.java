package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.cutover.OpeningBalanceService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.PropertyAccountMapping;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PropertyAccountMappingRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.TenantDefaultAccountMappingRepository;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The opening-balance and reconciliation API over HTTP: the role gate, the
 * tenant-less SUPER_ADMIN guard, the multipart upload, and the JSON the web tasks
 * are typed from.
 *
 * <p>{@code @PreAuthorize} does nothing in a service-level test — the roles only
 * bite once a request has been through {@code ApiSecurityFilter} — so this is a
 * full-context HTTP test with the legacy {@code X-User-*} headers the Next.js proxy
 * sends, the same shape as {@code VoucherControllerIT} and
 * {@code ImportBatchControllerIT}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OpeningBalanceControllerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort int port;

    @Autowired ObjectMapper json;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired AccountResolver resolver;
    @Autowired OpeningBalanceService ob;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired PropertyAccountMappingRepository propertyMappings;
    @Autowired TenantDefaultAccountMappingRepository defaultMappings;
    @Autowired TransactionTemplate tx;

    static final LocalDate BOOKS_START = LocalDate.of(2026, 10, 1);
    static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);

    UUID tenantId, otherTenantId;
    Account cashInHand, vatPayable, rentReceivable, obDifference;
    User accountant, tenantAdmin, superAdmin, propertyManager, tenantUser, renter;

    @BeforeEach
    void setUp() {
        tenantId = tenant("OBCtl");
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        propertyAccounts.seedDefaultTemplateAndDefaults();

        Property p = new Property();
        p.setNameEn("Tulip Oasis 7");
        p.setEmirate(Emirate.DUBAI);
        UUID propertyId = propertyRepo.save(p).getId();

        cashInHand = accounts.createLeaf("Cash In Hand", accounts.getAccountByCode("A-02"), null);
        vatPayable = accounts.createLeaf("VAT Payable", accounts.getAccountByCode("B-01"), null);
        rentReceivable = accounts.createLeaf("Rent Receivable - Tulip 7", accounts.getAccountByCode("A-02-01"), propertyId);
        obDifference = resolver.resolve(AccountRole.OPENING_BALANCE_DIFFERENCE, null);   // seeded as F-02

        PropertyAccountMapping m = new PropertyAccountMapping();
        m.setPropertyId(propertyId);
        m.setRole(AccountRole.RENT_RECEIVABLE);
        m.setAccount(rentReceivable);
        propertyMappings.save(m);

        fiscal.setBooksStartDate(BOOKS_START);
        fiscal.lockThrough(AS_OF);

        accountant = user(UserRole.ACCOUNTANT, tenantId);
        tenantAdmin = user(UserRole.TENANT_ADMIN, tenantId);
        superAdmin = user(UserRole.SUPER_ADMIN, tenantId);
        propertyManager = user(UserRole.PROPERTY_MANAGER, tenantId);
        tenantUser = user(UserRole.TENANT_USER, tenantId);
        renter = user(UserRole.RENTER, tenantId);

        otherTenantId = tenant("OBCtl-B");
        TenantContextHolder.setTenantId(otherTenantId);
        accounts.seedDefaultAccounts();
        propertyAccounts.seedDefaultTemplateAndDefaults();
        fiscal.setBooksStartDate(BOOKS_START);
        TenantContextHolder.setTenantId(tenantId);
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private UUID tenant(String prefix) {
        LandlordOrg org = new LandlordOrg();
        org.setName(prefix + "-" + UUID.randomUUID());
        return orgRepo.save(org).getId();
    }

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

    /** What the proxy sends for a SUPER_ADMIN who has not picked an organisation. */
    private ResponseEntity<String> callWithoutTenant(HttpMethod method, String path, User caller, Object body) {
        RestClient.RequestBodySpec spec = client().method(method)
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name());
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    private ResponseEntity<String> uploadCsv(User caller, String csv) {
        MultipartBodyBuilder b = new MultipartBodyBuilder();
        b.part("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "trial-balance.csv"; }
        }).contentType(MediaType.TEXT_PLAIN);
        return multipart("/api/v1/finance/opening-balances/snapshot", caller, b.build());
    }

    private ResponseEntity<String> multipart(String path, User caller, MultiValueMap<String, HttpEntity<?>> parts) {
        return client().method(HttpMethod.POST).uri(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString())
                .contentType(MediaType.MULTIPART_FORM_DATA).body(parts)
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    private JsonNode json(ResponseEntity<String> res) {
        try {
            return json.readTree(res.getBody());
        } catch (Exception e) {
            throw new AssertionError("Response was not JSON: " + res.getBody(), e);
        }
    }

    private String csv() {
        return """
                Account Code,Account Name,Debit,Credit
                %s,Cash In Hand,50000.00,0.00
                %s,VAT Payable,0.00,12000.00
                """.formatted(cashInHand.getCode(), vatPayable.getCode());
    }

    // ------------------------------------------------------------------
    // the grid and the upload
    // ------------------------------------------------------------------

    @Test
    void theGridCarriesTheCutOverDateTheTotalsAndTheDerivedFlag() {
        ResponseEntity<String> res = call(HttpMethod.GET, "/api/v1/finance/opening-balances", accountant, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode g = json(res);
        assertThat(g.get("asOf").asText()).isEqualTo("2026-09-30");
        assertThat(g.get("posted").asBoolean()).isFalse();
        assertThat(g.get("journalId").isNull()).isTrue();
        assertThat(g.get("totalDebit").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(g.get("difference").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(g.get("problems")).isEmpty();

        JsonNode derivedRow = null;
        for (JsonNode row : g.get("rows")) {
            if (rentReceivable.getId().toString().equals(row.get("accountId").asText())) derivedRow = row;
        }
        assertThat(derivedRow).isNotNull();
        assertThat(derivedRow.get("derived").asBoolean()).isTrue();
        assertThat(derivedRow.get("derivedRole").asText()).isEqualTo("RENT_RECEIVABLE");
        assertThat(derivedRow.get("enteredDebit").decimalValue()).isEqualByComparingTo("0.00");
        // Ruling R25: three pairs on the wire, not two. The web reads post* for what
        // the journal will write and seeds its edit inputs from entered*.
        assertThat(derivedRow.get("postDebit").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(derivedRow.get("postCredit").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(derivedRow.get("derivedDebit")).isNotNull();
    }

    /**
     * Ruling R26: the grid's problems are objects with a severity, and the one that
     * will refuse the post is marked apart from the ones that will not.
     *
     * <p>Both halves in one test on purpose — the point of the field is the
     * <em>contrast</em>, and asserting ERROR somewhere and WARNING somewhere else
     * would not catch a mapping that stamped everything the same.</p>
     */
    @Test
    void theGridsProblemsSayWhichOfThemWillStopThePost() {
        // An advisory, on a chart that is otherwise in order: PACT's own suspense
        // figure, which we report and do not carry over.
        uploadCsv(accountant, csv()
                + obDifference.getCode() + ",Opening Balance Difference,0.00,5000.00\n");

        JsonNode advisory = onlyProblem(json(call(HttpMethod.GET, "/api/v1/finance/opening-balances",
                accountant, null)));
        assertThat(advisory.get("message").asText())
                .contains(obDifference.getCode()).contains("recomputed");
        assertThat(advisory.get("severity").asText()).isEqualTo("WARNING");

        // And the fault that refuses the post, on the same grid.
        unmapOpeningBalanceDifference();

        JsonNode fault = onlyProblem(json(call(HttpMethod.GET, "/api/v1/finance/opening-balances",
                accountant, null)));
        assertThat(fault.get("message").asText()).contains("OPENING_BALANCE_DIFFERENCE");
        assertThat(fault.get("severity").asText()).isEqualTo("ERROR");

        // Said once, meant once: the post refuses on exactly that condition.
        ResponseEntity<String> refused = call(HttpMethod.POST, "/api/v1/finance/opening-balances/post",
                accountant, null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("OPENING_BALANCE_DIFFERENCE");
    }

    private JsonNode onlyProblem(JsonNode grid) {
        assertThat(grid.get("problems")).hasSize(1);
        return grid.get("problems").get(0);
    }

    /** Stands in for a tenant whose default-account seed ran before F-02 existed. */
    private void unmapOpeningBalanceDifference() {
        tx.executeWithoutResult(s -> defaultMappings.findAllByOrderByRoleAsc().stream()
                .filter(m -> m.getRole() == AccountRole.OPENING_BALANCE_DIFFERENCE)
                .forEach(defaultMappings::delete));
    }

    @Test
    void uploadingTheTrialBalanceReportsWhatItStoredAndWhatItDidNotRecognise() {
        ResponseEntity<String> res = uploadCsv(accountant, csv() + "999999,Not ours,0.00,777.00\n");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode body = json(res);
        assertThat(body.get("stored").asInt()).isEqualTo(3);
        assertThat(body.get("unmatchedCodes").get(0).asText()).isEqualTo("999999");
        assertThat(body.get("problems")).isEmpty();

        JsonNode grid = json(call(HttpMethod.GET, "/api/v1/finance/opening-balances", accountant, null));
        assertThat(grid.get("totalDebit").decimalValue()).isEqualByComparingTo("50000.00");
        assertThat(grid.get("difference").decimalValue()).isEqualByComparingTo("38000.00");
    }

    @Test
    void anEmptyUploadIsARefusalNotAnEmptySnapshot() {
        MultipartBodyBuilder b = new MultipartBodyBuilder();
        b.part("file", new ByteArrayResource(new byte[0]) {
            @Override public String getFilename() { return "empty.csv"; }
        }).contentType(MediaType.TEXT_PLAIN);
        ResponseEntity<String> res = multipart("/api/v1/finance/opening-balances/snapshot", accountant, b.build());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aFigureCanBeTypedIntoAManualRowButNotADerivedOne() {
        assertThat(call(HttpMethod.PUT, "/api/v1/finance/opening-balances/" + cashInHand.getId(),
                accountant, Map.of("debit", "1200.50")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> refused = call(HttpMethod.PUT,
                "/api/v1/finance/opening-balances/" + rentReceivable.getId(), accountant, Map.of("debit", "1200.50"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains(rentReceivable.getCode()).contains("RENT_RECEIVABLE");
    }

    @Test
    void aRowForAnAccountThatDoesNotExistIs404() {
        ResponseEntity<String> res = call(HttpMethod.PUT,
                "/api/v1/finance/opening-balances/" + UUID.randomUUID(), accountant, Map.of("debit", "1.00"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // posting over HTTP
    // ------------------------------------------------------------------

    @Test
    void postingReturnsTheJournalSummaryAndPostingTwiceIsRefused() {
        uploadCsv(accountant, csv());

        ResponseEntity<String> posted = call(HttpMethod.POST, "/api/v1/finance/opening-balances/post", accountant, null);
        assertThat(posted.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode j = json(posted);
        assertThat(j.get("id").asText()).isNotBlank();
        assertThat(j.get("entryNumber").asText()).startsWith("OB");
        assertThat(j.get("entryDate").asText()).isEqualTo("2026-09-30");

        ResponseEntity<String> again = call(HttpMethod.POST, "/api/v1/finance/opening-balances/post", accountant, null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(again.getBody()).contains("already");

        JsonNode grid = json(call(HttpMethod.GET, "/api/v1/finance/opening-balances", accountant, null));
        assertThat(grid.get("posted").asBoolean()).isTrue();
        assertThat(grid.get("journalNumber").asText()).isEqualTo(j.get("entryNumber").asText());
    }

    @Test
    void aCorrectedTrialBalanceCanBeRePostedAndTheBooksCanBeClosedAgain() {
        uploadCsv(accountant, csv());
        call(HttpMethod.POST, "/api/v1/finance/opening-balances/post", accountant, null);

        JsonNode reposted = json(call(HttpMethod.POST, "/api/v1/finance/opening-balances/repost",
                accountant, Map.of("reason", "corrected trial balance")));
        assertThat(reposted.get("entryNumber").asText()).startsWith("OB");

        ResponseEntity<String> reversed = call(HttpMethod.POST, "/api/v1/finance/opening-balances/reverse",
                tenantAdmin, Map.of("reason", "wrong file"));
        assertThat(reversed.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode grid = json(call(HttpMethod.GET, "/api/v1/finance/opening-balances", accountant, null));
        assertThat(grid.get("posted").asBoolean()).isFalse();
    }

    @Test
    void reversingWithNothingPostedIs400() {
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/finance/opening-balances/reverse",
                accountant, Map.of("reason", "nothing there"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------
    // reconciliation
    // ------------------------------------------------------------------

    @Test
    void theReconciliationReportCarriesBothBalancesAndTheDifference() {
        uploadCsv(accountant, csv());

        ResponseEntity<String> res = call(HttpMethod.GET, "/api/v1/finance/reconciliation", accountant, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode rows = json(res);
        JsonNode cash = null;
        for (JsonNode row : rows) {
            if (cashInHand.getId().toString().equals(row.get("accountId").asText())) cash = row;
        }
        assertThat(cash).isNotNull();
        assertThat(cash.get("code").asText()).isEqualTo(cashInHand.getCode());
        assertThat(cash.get("derived").asBoolean()).isFalse();
        assertThat(cash.get("derivedBalance").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(cash.get("pactBalance").decimalValue()).isEqualByComparingTo("50000.00");
        assertThat(cash.get("difference").decimalValue()).isEqualByComparingTo("-50000.00");
    }

    // ------------------------------------------------------------------
    // fix round 1 — the JSON the web is typed from
    // ------------------------------------------------------------------

    /** `computed` on the difference account's row, so the page needs no second lookup. */
    @Test
    void theGridMarksTheDifferenceAccountComputed() {
        JsonNode g = json(call(HttpMethod.GET, "/api/v1/finance/opening-balances", accountant, null));
        assertThat(g.get("changedSincePosted").asBoolean()).isFalse();

        JsonNode computed = null, cash = null;
        for (JsonNode row : g.get("rows")) {
            if (row.get("computed").asBoolean()) computed = row;
            if (cashInHand.getId().toString().equals(row.get("accountId").asText())) cash = row;
        }
        assertThat(computed).isNotNull();
        assertThat(computed.get("code").asText()).isEqualTo("F-02");
        assertThat(computed.get("derived").asBoolean()).isFalse();
        assertThat(cash).isNotNull();
        assertThat(cash.get("computed").asBoolean()).isFalse();
    }

    @Test
    void theUploadResponseCarriesTheFilesTotalsAndWhetherItBalances() {
        JsonNode body = json(uploadCsv(accountant, csv()));
        assertThat(body.get("totalDebit").decimalValue()).isEqualByComparingTo("50000.00");
        assertThat(body.get("totalCredit").decimalValue()).isEqualByComparingTo("12000.00");
        assertThat(body.get("balanced").asBoolean()).isFalse();
    }

    @Test
    void theGridFlagsUnpostedChangesAfterTheSnapshotIsEdited() {
        uploadCsv(accountant, csv());
        call(HttpMethod.POST, "/api/v1/finance/opening-balances/post", accountant, null);
        assertThat(json(call(HttpMethod.GET, "/api/v1/finance/opening-balances", accountant, null))
                .get("changedSincePosted").asBoolean()).isFalse();

        call(HttpMethod.PUT, "/api/v1/finance/opening-balances/" + cashInHand.getId(),
                accountant, Map.of("debit", "60000.00"));
        assertThat(json(call(HttpMethod.GET, "/api/v1/finance/opening-balances", accountant, null))
                .get("changedSincePosted").asBoolean()).isTrue();
    }

    @Test
    void aFigureAgainstTheComputedDifferenceAccountIsRefused() {
        Account difference = resolver.resolve(AccountRole.OPENING_BALANCE_DIFFERENCE, null);
        ResponseEntity<String> res = call(HttpMethod.PUT,
                "/api/v1/finance/opening-balances/" + difference.getId(), accountant, Map.of("credit", "5000"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("F-02").contains("recomputed");
    }

    /**
     * The reverse body no longer carries a date, and a client that still sends one is
     * ignored rather than obeyed — which is the whole of C1 seen from the wire: a
     * later-dated mirror would leave the opening balances standing at the cut-over
     * date while the grid said the books were closed.
     */
    @Test
    void aDateSentToTheReverseEndpointIsIgnoredAndTheMirrorFollowsTheEntry() {
        uploadCsv(accountant, csv());
        JsonNode posted = json(call(HttpMethod.POST, "/api/v1/finance/opening-balances/post", accountant, null));

        JsonNode mirror = json(call(HttpMethod.POST, "/api/v1/finance/opening-balances/reverse",
                accountant, Map.of("date", "2026-11-15", "reason", "wrong file")));
        assertThat(mirror.get("entryDate").asText())
                .isEqualTo(posted.get("entryDate").asText())
                .isEqualTo("2026-09-30");

        JsonNode grid = json(call(HttpMethod.GET, "/api/v1/finance/opening-balances", accountant, null));
        assertThat(grid.get("posted").asBoolean()).isFalse();
    }

    /** The calendar refuses to move out from under a posted opening balance. */
    @Test
    void theBooksStartDateCannotBeChangedOverHttpWhileTheBooksAreOpen() {
        uploadCsv(accountant, csv());
        call(HttpMethod.POST, "/api/v1/finance/opening-balances/post", accountant, null);

        ResponseEntity<String> res = call(HttpMethod.PUT, "/api/v1/finance/fiscal-settings",
                tenantAdmin, Map.of("booksStartDate", "2026-11-01"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("Reverse or replace the opening balances");
    }

    // ------------------------------------------------------------------
    // the gates
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"PROPERTY_MANAGER", "TENANT_USER", "RENTER"})
    void everyOtherRoleIsForbidden(String role) {
        User caller = switch (role) {
            case "PROPERTY_MANAGER" -> propertyManager;
            case "TENANT_USER" -> tenantUser;
            default -> renter;
        };
        assertThat(call(HttpMethod.GET, "/api/v1/finance/opening-balances", caller, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/v1/finance/reconciliation", caller, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/api/v1/finance/opening-balances/post", caller, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(uploadCsv(caller, csv()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * A platform admin who has not picked an organisation reaches a service with no
     * ambient tenant — and the Hibernate tenant filter is left off, so a list endpoint
     * would read across organisations. Every handler says so first.
     */
    @Test
    void aTenantLessSuperAdminGetsACleanBadRequest() {
        for (String path : new String[]{"/api/v1/finance/opening-balances", "/api/v1/finance/reconciliation"}) {
            ResponseEntity<String> res = callWithoutTenant(HttpMethod.GET, path, superAdmin, null);
            assertThat(res.getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody()).as(path).contains("Select an organisation first");
        }
        assertThat(callWithoutTenant(HttpMethod.POST, "/api/v1/finance/opening-balances/post", superAdmin, null)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(callWithoutTenant(HttpMethod.POST, "/api/v1/finance/opening-balances/repost", superAdmin, null)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(callWithoutTenant(HttpMethod.POST, "/api/v1/finance/opening-balances/reverse", superAdmin, null)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(callWithoutTenant(HttpMethod.PUT, "/api/v1/finance/opening-balances/" + cashInHand.getId(),
                superAdmin, Map.of("debit", "1.00")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * Tenant B's accountant sees B's books: not A's snapshot, not A's opening journal,
     * not A's reconciliation, and A's account id is simply not found.
     */
    @Test
    void tenantBSeesNothingOfTenantAsOpeningBalances() {
        uploadCsv(accountant, csv());
        call(HttpMethod.POST, "/api/v1/finance/opening-balances/post", accountant, null);

        User bAccountant = user(UserRole.ACCOUNTANT, otherTenantId);

        JsonNode grid = json(call(HttpMethod.GET, "/api/v1/finance/opening-balances", bAccountant, null));
        assertThat(grid.get("posted").asBoolean()).isFalse();
        assertThat(grid.get("totalDebit").decimalValue()).isEqualByComparingTo("0.00");
        for (JsonNode row : grid.get("rows")) {
            assertThat(row.get("accountId").asText()).isNotEqualTo(cashInHand.getId().toString());
        }

        assertThat(json(call(HttpMethod.GET, "/api/v1/finance/reconciliation", bAccountant, null))).isEmpty();

        assertThat(call(HttpMethod.PUT, "/api/v1/finance/opening-balances/" + cashInHand.getId(),
                bAccountant, Map.of("debit", "1.00")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(call(HttpMethod.POST, "/api/v1/finance/opening-balances/reverse",
                bAccountant, Map.of("reason", "not mine")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // A's books are untouched by any of that.
        JsonNode aGrid = json(call(HttpMethod.GET, "/api/v1/finance/opening-balances", accountant, null));
        assertThat(aGrid.get("posted").asBoolean()).isTrue();
        assertThat(aGrid.get("totalDebit").decimalValue()).isEqualByComparingTo("50000.00");
    }
}
