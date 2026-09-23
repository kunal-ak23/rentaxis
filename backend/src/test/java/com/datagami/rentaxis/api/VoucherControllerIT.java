package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The voucher REST API over HTTP: role gate, the tenant-less SUPER_ADMIN guard,
 * the create/post/amend lifecycle, attachments, list filters and error mapping.
 *
 * <p>{@code @PreAuthorize} does nothing in a service-level test — the roles only
 * bite once a request has been through {@code ApiSecurityFilter} — so, following
 * {@code RecognitionControllerIT} and {@code LeaseControllerTerminateEndpointsIT},
 * this is a full-context HTTP test with the legacy {@code X-User-*} headers the
 * Next.js proxy sends, over a real random port. A {@code MockMvc + @WithMockUser}
 * test (as sketched in the task brief) does not exercise the tenant guard
 * correctly here: {@code ApiSecurityFilter} clears {@code TenantContextHolder} in
 * a {@code finally} block after every request it processes, including ones with
 * no {@code X-User-*} headers, so a tenant set once in {@code @BeforeEach} would
 * silently disappear after the first of several {@code mvc.perform} calls in the
 * same test — exactly the kind of multi-call scenario (create, then post, then
 * list) this suite needs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// server.tomcat.max-swallow-size defaults to 2MB: after Tomcat aborts an
// oversized multipart request and writes its error response, it only reads
// ("swallows") up to this much of whatever the client is still sending before
// giving up and resetting the connection. anOversizedUploadIsRejectedWithA400
// sends an 11MB body, ~9MB more than the default swallow allowance, so without
// this override the client's write races the server's reset and fails with a
// misleading client-side "Broken pipe" instead of ever seeing the clean 400 the
// test means to observe. Unlimited swallowing has no effect on any other test:
// it only changes what happens to bytes still in flight after an error.
@TestPropertySource(properties = "server.tomcat.max-swallow-size=-1")
class VoucherControllerIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired ObjectMapper json;
    @Autowired AccountService accounts;
    @Autowired VendorService vendorService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired TenantDefaultAccountMappingRepository defaults;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired VoucherAttachmentRepository attachmentRepo;

    /** A real "%PDF-" magic number, matching what {@link #pdfPart} already builds. */
    private static final byte[] REAL_PDF_BYTES = "%PDF-1.4 fake".getBytes();
    private static final byte[] REAL_PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0};
    private static final byte[] REAL_JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0};
    /** Not any of the three accepted formats' magic number, whatever it is declared as. */
    private static final byte[] NOT_REALLY_A_DOCUMENT = "MZ this is an executable, not a PDF".getBytes();

    UUID tenantId;
    Account expense;
    Account bank;
    Vendor vendor;
    User accountant, tenantAdmin, superAdmin, propertyManager, tenantUser, renter;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("VCtl-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        expense = accounts.createLeaf("Security Services", accounts.getAccountByCode("D-01"), null);
        bank = accounts.createLeaf("Emirates NBD - Ops", accounts.getAccountByCode("A-02-02"), null);
        Account vat = accounts.createLeaf("VAT Receivable", accounts.getAccountByCode("A-02-04"), null);
        TenantDefaultAccountMapping m = new TenantDefaultAccountMapping();
        m.setRole(AccountRole.INPUT_VAT);
        m.setAccount(vat);
        defaults.save(m);
        Vendor v = new Vendor();
        v.setNameEn("Transguard");
        vendor = vendorService.createVendor(v);
        fiscal.setBooksStartDate(LocalDate.of(2026, 10, 1));
        fiscal.lockThrough(LocalDate.of(2026, 9, 30));

        accountant = user(UserRole.ACCOUNTANT, tenantId);
        tenantAdmin = user(UserRole.TENANT_ADMIN, tenantId);
        superAdmin = user(UserRole.SUPER_ADMIN, tenantId);
        propertyManager = user(UserRole.PROPERTY_MANAGER, tenantId);
        tenantUser = user(UserRole.TENANT_USER, tenantId);
        renter = user(UserRole.RENTER, tenantId);
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

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

    private ResponseEntity<String> call(HttpMethod method, String path, User caller, Object body) {
        RestClient.RequestBodySpec spec = client().method(method)
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString());
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    /** What the proxy sends for a SUPER_ADMIN who has not picked an organisation in the switcher. */
    private ResponseEntity<String> callWithoutTenant(HttpMethod method, String path, User caller, Object body) {
        RestClient.RequestBodySpec spec = client().method(method)
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name());
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    private ResponseEntity<String> multipartCall(String path, User caller, MultiValueMap<String, HttpEntity<?>> parts) {
        RestClient.RequestBodySpec spec = client().method(HttpMethod.POST).uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString());
        return spec.contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve()
                .onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    /** Same shape as {@link #callWithoutTenant}, for the one multipart endpoint. */
    private ResponseEntity<String> multipartCallWithoutTenant(String path, User caller,
                                                               MultiValueMap<String, HttpEntity<?>> parts) {
        RestClient.RequestBodySpec spec = client().method(HttpMethod.POST).uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name());
        return spec.contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve()
                .onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    /** No {@code X-User-*} headers at all — what an anonymous internet caller sends. */
    private ResponseEntity<String> unauthenticatedGet(String path) {
        return client().get().uri(path).retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    private JsonNode json(ResponseEntity<String> res) {
        try {
            return json.readTree(res.getBody());
        } catch (Exception e) {
            throw new AssertionError("Response was not JSON: " + res.getBody(), e);
        }
    }

    private String pisrBody(UUID vendorId, UUID accountId, String amount, String vatRate) throws Exception {
        return json.writeValueAsString(Map.of(
                "docType", "PISR",
                "docDate", "2026-10-09",
                "vendorId", vendorId.toString(),
                "invoiceNumber", "TG-99",
                "narration", "Security — October",
                "lines", List.of(Map.of(
                        "accountId", accountId.toString(),
                        "description", "Guards",
                        "amount", new BigDecimal(amount),
                        "vatRate", new BigDecimal(vatRate)))));
    }

    private String bpvBody(UUID paymentAccountId, UUID lineAccountId, String amount) throws Exception {
        return json.writeValueAsString(Map.of(
                "docType", "BPV",
                "docDate", "2026-10-09",
                "narration", "Cash payment",
                "paymentAccountId", paymentAccountId.toString(),
                "lines", List.of(Map.of(
                        "accountId", lineAccountId.toString(),
                        "description", "Payment",
                        "amount", new BigDecimal(amount),
                        "vatRate", BigDecimal.ZERO))));
    }

    private JsonNode createPisr(User caller) throws Exception {
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/finance/vouchers", caller,
                pisrBody(vendor.getId(), expense.getId(), "8000.00", "5"));
        assertThat(res.getStatusCode()).as("create failed: %s", res.getBody()).isEqualTo(HttpStatus.CREATED);
        return json(res);
    }

    // ------------------------------------------------------------------
    // create -> update -> post -> amend, over HTTP
    // ------------------------------------------------------------------

    @Test
    void theFullDraftLifecycleWorksOverHttpAndReturnsTheJournalNumber() throws Exception {
        JsonNode created = createPisr(accountant);
        assertThat(created.get("status").asText()).isEqualTo("DRAFT");
        assertThat(created.get("netTotal").asDouble()).isEqualTo(8000.00);
        assertThat(created.get("vatTotal").asDouble()).isEqualTo(400.00);
        assertThat(created.get("grossTotal").asDouble()).isEqualTo(8400.00);
        assertThat(created.get("lines").get(0).get("accountCode").asText()).isNotEmpty();
        String id = created.get("id").asText();

        // update while still a draft
        ResponseEntity<String> updated = call(HttpMethod.PUT, "/api/v1/finance/vouchers/" + id, accountant,
                pisrBody(vendor.getId(), expense.getId(), "9000.00", "5"));
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(updated).get("netTotal").asDouble()).isEqualTo(9000.00);

        // post it
        ResponseEntity<String> posted = call(HttpMethod.POST, "/api/v1/finance/vouchers/" + id + "/post", accountant, null);
        assertThat(posted.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode postedBody = json(posted);
        assertThat(postedBody.get("status").asText()).isEqualTo("POSTED");
        assertThat(postedBody.get("voucherNumber").asText()).startsWith("PISR-");
        assertThat(postedBody.get("journalId").asText()).isNotEmpty();

        // list finds it
        ResponseEntity<String> list = call(HttpMethod.GET,
                "/api/v1/finance/vouchers?docType=PISR&page=0&size=25", accountant, null);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode listBody = json(list);
        assertThat(listBody.get("totalElements").asInt()).isEqualTo(1);
        assertThat(listBody.get("content").get(0).get("vendorName").asText()).isEqualTo("Transguard");

        // amend it: reverse and replace with a corrected figure
        String amendBody = json.writeValueAsString(Map.of(
                "reversalDate", "2026-10-15",
                "reason", "Wrong amount",
                "replacement", json.readValue(pisrBody(vendor.getId(), expense.getId(), "7500.00", "5"), Map.class)));
        ResponseEntity<String> amended = call(HttpMethod.POST, "/api/v1/finance/vouchers/" + id + "/amend",
                accountant, amendBody);
        assertThat(amended.getStatusCode()).as("amend failed: %s", amended.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode amendedBody = json(amended);
        assertThat(amendedBody.get("status").asText()).isEqualTo("POSTED");
        assertThat(amendedBody.get("voucherNumber").asText()).startsWith("PISR-");
        assertThat(amendedBody.get("amendedFromId").asText()).isEqualTo(id);
        assertThat(amendedBody.get("netTotal").asDouble()).isEqualTo(7500.00);

        // the original is now REVERSED
        ResponseEntity<String> original = call(HttpMethod.GET, "/api/v1/finance/vouchers/" + id, accountant, null);
        assertThat(json(original).get("status").asText()).isEqualTo("REVERSED");
    }

    @Test
    void editingAPostedVoucherIsA400NotA500() throws Exception {
        JsonNode created = createPisr(accountant);
        String id = created.get("id").asText();
        call(HttpMethod.POST, "/api/v1/finance/vouchers/" + id + "/post", accountant, null);

        ResponseEntity<String> res = call(HttpMethod.PUT, "/api/v1/finance/vouchers/" + id, accountant,
                pisrBody(vendor.getId(), expense.getId(), "8000.00", "5"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(res).get("message").asText()).contains("POSTED");
    }

    @Test
    void anUnknownVoucherIs404() {
        ResponseEntity<String> res = call(HttpMethod.GET,
                "/api/v1/finance/vouchers/" + UUID.randomUUID(), accountant, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void malformedJsonBodyIsA400() {
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/finance/vouchers", accountant, "{not json");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------
    // the draft-time account rule (the "fix" commit), asserted over HTTP
    // ------------------------------------------------------------------

    @Test
    void aBpvOnANonBankPaymentAccountIsA400NamingTheAccount() throws Exception {
        Account receivable = accounts.createLeaf("Rental Receivable - HTTP", accounts.getAccountByCode("A-02-01"), null);
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/finance/vouchers", accountant,
                bpvBody(receivable.getId(), expense.getId(), "100.00"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String message = json(res).get("message").asText();
        assertThat(message).contains(receivable.getCode()).contains("must be a bank or cash account");
    }

    @Test
    void aPisrLineOnAnIncomeAccountIsA400NamingTheAccount() throws Exception {
        Account income = accounts.createLeaf("Other Income - HTTP", accounts.getAccountByCode("C-01-02"), null);
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/finance/vouchers", accountant,
                pisrBody(vendor.getId(), income.getId(), "100.00", "0"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String message = json(res).get("message").asText();
        assertThat(message).contains(income.getCode()).contains("expense or asset");
    }

    // ------------------------------------------------------------------
    // who may do what
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"PROPERTY_MANAGER", "TENANT_USER", "RENTER"})
    void aDisallowedRoleIsForbiddenOnEveryEndpoint(String roleName) throws Exception {
        UserRole role = UserRole.valueOf(roleName);
        User caller = role == UserRole.PROPERTY_MANAGER ? propertyManager : role == UserRole.TENANT_USER ? tenantUser : renter;

        assertThat(call(HttpMethod.GET, "/api/v1/finance/vouchers", caller, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/api/v1/finance/vouchers", caller,
                pisrBody(vendor.getId(), expense.getId(), "10.00", "0")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        JsonNode created = createPisr(accountant);
        String id = created.get("id").asText();
        assertThat(call(HttpMethod.GET, "/api/v1/finance/vouchers/" + id, caller, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.PUT, "/api/v1/finance/vouchers/" + id, caller,
                pisrBody(vendor.getId(), expense.getId(), "10.00", "0")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/api/v1/finance/vouchers/" + id + "/post", caller, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.DELETE, "/api/v1/finance/vouchers/" + id, caller, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/v1/finance/vouchers/" + id + "/attachments", caller, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/v1/finance/vouchers/attachments/" + UUID.randomUUID() + "/download",
                caller, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.DELETE, "/api/v1/finance/vouchers/attachments/" + UUID.randomUUID(),
                caller, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String amendBody = json.writeValueAsString(Map.of(
                "reversalDate", "2026-10-15", "reason", "x",
                "replacement", json.readValue(pisrBody(vendor.getId(), expense.getId(), "10.00", "0"), Map.class)));
        assertThat(call(HttpMethod.POST, "/api/v1/finance/vouchers/" + id + "/amend", caller, amendBody).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(multipartCall("/api/v1/finance/vouchers/" + id + "/attachments", caller,
                pdfPart("invoice.pdf", "nope")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // The refusal is really the role, not a broken fixture: the same calls succeed for an accountant.
        assertThat(call(HttpMethod.GET, "/api/v1/finance/vouchers", accountant, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNTANT", "TENANT_ADMIN", "SUPER_ADMIN"})
    void anAllowedRoleMayListAndCreate(String roleName) throws Exception {
        UserRole role = UserRole.valueOf(roleName);
        User caller = role == UserRole.ACCOUNTANT ? accountant : role == UserRole.TENANT_ADMIN ? tenantAdmin : superAdmin;

        assertThat(call(HttpMethod.GET, "/api/v1/finance/vouchers", caller, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> created = call(HttpMethod.POST, "/api/v1/finance/vouchers", caller,
                pisrBody(vendor.getId(), expense.getId(), "10.00", "0"));
        assertThat(created.getStatusCode()).as(caller.getRole() + ": %s", created.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    // ------------------------------------------------------------------
    // the tenant-less SUPER_ADMIN guard
    // ------------------------------------------------------------------

    @Test
    void aSuperAdminWithNoOrganisationSelectedGetsACleanBadRequestOnEveryEndpoint() throws Exception {
        JsonNode created = createPisr(accountant);
        String id = created.get("id").asText();
        UUID randomId = UUID.randomUUID();

        assertRefusedForNoTenant(HttpMethod.GET, "/api/v1/finance/vouchers", null);
        assertRefusedForNoTenant(HttpMethod.GET, "/api/v1/finance/vouchers/" + id, null);
        assertRefusedForNoTenant(HttpMethod.POST, "/api/v1/finance/vouchers",
                pisrBody(vendor.getId(), expense.getId(), "10.00", "0"));
        assertRefusedForNoTenant(HttpMethod.PUT, "/api/v1/finance/vouchers/" + id,
                pisrBody(vendor.getId(), expense.getId(), "10.00", "0"));
        assertRefusedForNoTenant(HttpMethod.DELETE, "/api/v1/finance/vouchers/" + id, null);
        assertRefusedForNoTenant(HttpMethod.POST, "/api/v1/finance/vouchers/" + id + "/post", null);
        assertRefusedForNoTenant(HttpMethod.GET, "/api/v1/finance/vouchers/" + id + "/attachments", null);
        assertRefusedForNoTenant(HttpMethod.GET, "/api/v1/finance/vouchers/attachments/" + randomId + "/download", null);
        assertRefusedForNoTenant(HttpMethod.DELETE, "/api/v1/finance/vouchers/attachments/" + randomId, null);

        String amendBody = json.writeValueAsString(Map.of(
                "reversalDate", "2026-10-15", "reason", "x",
                "replacement", json.readValue(pisrBody(vendor.getId(), expense.getId(), "10.00", "0"), Map.class)));
        assertRefusedForNoTenant(HttpMethod.POST, "/api/v1/finance/vouchers/" + id + "/amend", amendBody);

        ResponseEntity<String> uploadRes = multipartCallWithoutTenant(
                "/api/v1/finance/vouchers/" + id + "/attachments", superAdmin, pdfPart("invoice.pdf", "x"));
        assertThat(uploadRes.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(uploadRes).get("message").asText()).contains("Select an organisation first");

        // The refusal is the missing organisation, not the role: the same admin with one chosen succeeds.
        assertThat(call(HttpMethod.GET, "/api/v1/finance/vouchers", superAdmin, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private void assertRefusedForNoTenant(HttpMethod method, String path, Object body) {
        ResponseEntity<String> res = callWithoutTenant(method, path, superAdmin, body);
        assertThat(res.getStatusCode()).as("%s %s", method, path).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(res).get("message").asText()).contains("Select an organisation first");
    }

    // ------------------------------------------------------------------
    // list: filters and default sort
    // ------------------------------------------------------------------

    @Test
    void listFiltersByDocTypeStatusVendorAndDateRangeAndSortsByCreatedAtAscending() throws Exception {
        JsonNode first = createPisr(accountant);
        Vendor other = vendorService.createVendor(withName("Other Vendor"));
        ResponseEntity<String> secondRes = call(HttpMethod.POST, "/api/v1/finance/vouchers", accountant,
                pisrBody(other.getId(), expense.getId(), "500.00", "0"));
        assertThat(secondRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode second = json(secondRes);
        call(HttpMethod.POST, "/api/v1/finance/vouchers/" + second.get("id").asText() + "/post", accountant, null);

        // default sort: oldest created first
        JsonNode all = json(call(HttpMethod.GET, "/api/v1/finance/vouchers", accountant, null));
        assertThat(all.get("content")).hasSize(2);
        assertThat(all.get("content").get(0).get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(all.get("content").get(1).get("id").asText()).isEqualTo(second.get("id").asText());

        // status filter
        JsonNode drafts = json(call(HttpMethod.GET, "/api/v1/finance/vouchers?status=DRAFT", accountant, null));
        assertThat(drafts.get("totalElements").asInt()).isEqualTo(1);
        assertThat(drafts.get("content").get(0).get("id").asText()).isEqualTo(first.get("id").asText());

        // vendor filter
        JsonNode byVendor = json(call(HttpMethod.GET,
                "/api/v1/finance/vouchers?vendorId=" + other.getId(), accountant, null));
        assertThat(byVendor.get("totalElements").asInt()).isEqualTo(1);
        assertThat(byVendor.get("content").get(0).get("vendorName").asText()).isEqualTo("Other Vendor");

        // date range filter (both are dated 2026-10-09)
        ResponseEntity<String> inRangeRes = call(HttpMethod.GET,
                "/api/v1/finance/vouchers?from=2026-10-01&to=2026-10-31", accountant, null);
        assertThat(inRangeRes.getStatusCode()).as("body: %s", inRangeRes.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(inRangeRes).get("totalElements").asInt()).isEqualTo(2);
        ResponseEntity<String> outOfRangeRes = call(HttpMethod.GET,
                "/api/v1/finance/vouchers?from=2026-11-01&to=2026-11-30", accountant, null);
        assertThat(outOfRangeRes.getStatusCode()).as("body: %s", outOfRangeRes.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(outOfRangeRes).get("totalElements").asInt()).isEqualTo(0);

        // docType filter
        JsonNode byType = json(call(HttpMethod.GET, "/api/v1/finance/vouchers?docType=BPV", accountant, null));
        assertThat(byType.get("totalElements").asInt()).isEqualTo(0);
    }

    private Vendor withName(String name) {
        Vendor v = new Vendor();
        v.setNameEn(name);
        return v;
    }

    // ------------------------------------------------------------------
    // attachments
    // ------------------------------------------------------------------

    private MultiValueMap<String, HttpEntity<?>> pdfPart(String filename, String docName) {
        MultipartBodyBuilder b = new MultipartBodyBuilder();
        b.part("name", docName);
        b.part("file", new ByteArrayResource("%PDF-1.4 fake".getBytes()) {
            @Override public String getFilename() { return filename; }
        }).contentType(MediaType.APPLICATION_PDF);
        return b.build();
    }

    @Test
    void attachmentsCanBeUploadedListedDownloadedAndDeleted() throws Exception {
        JsonNode created = createPisr(accountant);
        String id = created.get("id").asText();

        ResponseEntity<String> up = multipartCall("/api/v1/finance/vouchers/" + id + "/attachments", accountant,
                pdfPart("invoice.pdf", "Vendor invoice"));
        assertThat(up.getStatusCode()).as("upload failed: %s", up.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode uploaded = json(up);
        assertThat(uploaded.get("name").asText()).isEqualTo("Vendor invoice");
        assertThat(uploaded.get("fileType").asText()).isEqualTo("application/pdf");
        String attachmentId = uploaded.get("id").asText();

        ResponseEntity<String> list = call(HttpMethod.GET, "/api/v1/finance/vouchers/" + id + "/attachments", accountant, null);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(list)).hasSize(1);

        ResponseEntity<String> download = call(HttpMethod.GET,
                "/api/v1/finance/vouchers/attachments/" + attachmentId + "/download", accountant, null);
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(download.getHeaders().getFirst("Content-Disposition")).contains("Vendor invoice");

        ResponseEntity<String> delete = call(HttpMethod.DELETE,
                "/api/v1/finance/vouchers/attachments/" + attachmentId, accountant, null);
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(json(call(HttpMethod.GET, "/api/v1/finance/vouchers/" + id + "/attachments", accountant, null)))
                .isEmpty();
    }

    @Test
    void anExecutableUploadIsRejected() throws Exception {
        JsonNode created = createPisr(accountant);
        String id = created.get("id").asText();

        MultipartBodyBuilder b = new MultipartBodyBuilder();
        b.part("name", "nope");
        b.part("file", new ByteArrayResource("rm -rf /".getBytes()) {
            @Override public String getFilename() { return "payload.sh"; }
        }).contentType(MediaType.valueOf("application/x-sh"));

        ResponseEntity<String> res = multipartCall("/api/v1/finance/vouchers/" + id + "/attachments", accountant, b.build());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * The declared {@code Content-Type} header is trivially spoofed: an honestly
     * labelled {@code application/x-sh} is caught by the type allowlist alone (see
     * {@link #anExecutableUploadIsRejected}), but that proves nothing about a file
     * that lies and claims to be a PDF. This uploads bytes that are not really any
     * of the three accepted formats, declared as {@code application/pdf}, and only
     * the file-signature check can catch it.
     */
    @Test
    void anExecutableLabelledAsAPdfIsRejectedBySignature() throws Exception {
        JsonNode created = createPisr(accountant);
        String id = created.get("id").asText();

        MultipartBodyBuilder b = new MultipartBodyBuilder();
        b.part("name", "nope");
        b.part("file", new ByteArrayResource(NOT_REALLY_A_DOCUMENT) {
            @Override public String getFilename() { return "invoice.pdf"; }
        }).contentType(MediaType.APPLICATION_PDF);

        ResponseEntity<String> res = multipartCall("/api/v1/finance/vouchers/" + id + "/attachments", accountant, b.build());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * The inverse case: a file that really is one of the three accepted formats
     * but is mislabelled. This is accepted — mislabelling is not spoofing, the
     * bytes are still a real, harmless image — and the response stores the
     * DETECTED type, not the declared one, per the security ruling's decision.
     */
    @Test
    void aRealPngMislabelledAsJpegIsAcceptedAndStoredAsPng() throws Exception {
        JsonNode created = createPisr(accountant);
        String id = created.get("id").asText();

        MultipartBodyBuilder b = new MultipartBodyBuilder();
        b.part("name", "Mislabelled scan");
        b.part("file", new ByteArrayResource(REAL_PNG_BYTES) {
            @Override public String getFilename() { return "scan.jpg"; }
        }).contentType(MediaType.IMAGE_JPEG);

        ResponseEntity<String> res = multipartCall("/api/v1/finance/vouchers/" + id + "/attachments", accountant, b.build());
        assertThat(res.getStatusCode()).as("upload failed: %s", res.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(res).get("fileType").asText()).isEqualTo("image/png");
    }

    /**
     * A real JPEG is also accepted under its own declared type, proving the
     * signature check is not PDF-only.
     */
    @Test
    void aRealJpegIsAccepted() throws Exception {
        JsonNode created = createPisr(accountant);
        String id = created.get("id").asText();

        MultipartBodyBuilder b = new MultipartBodyBuilder();
        b.part("name", "Photo of the invoice");
        b.part("file", new ByteArrayResource(REAL_JPEG_BYTES) {
            @Override public String getFilename() { return "photo.jpg"; }
        }).contentType(MediaType.IMAGE_JPEG);

        ResponseEntity<String> res = multipartCall("/api/v1/finance/vouchers/" + id + "/attachments", accountant, b.build());
        assertThat(res.getStatusCode()).as("upload failed: %s", res.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(res).get("fileType").asText()).isEqualTo("image/jpeg");
    }

    /**
     * The service's own 10MB ceiling is now aligned with the global
     * {@code spring.servlet.multipart.max-file-size}, so an oversized upload is
     * rejected by the servlet layer before the controller runs, as a
     * {@code MaxUploadSizeExceededException} — this proves {@code
     * GlobalExceptionHandler} turns that into the app's usual 400 shape rather
     * than letting it fall through to a raw 500.
     */
    @Test
    void anOversizedUploadIsRejectedWithA400() throws Exception {
        JsonNode created = createPisr(accountant);
        String id = created.get("id").asText();

        byte[] big = new byte[11 * 1024 * 1024];
        System.arraycopy(REAL_PDF_BYTES, 0, big, 0, REAL_PDF_BYTES.length);
        MultipartBodyBuilder b = new MultipartBodyBuilder();
        b.part("name", "Huge scan");
        b.part("file", new ByteArrayResource(big) {
            @Override public String getFilename() { return "huge.pdf"; }
        }).contentType(MediaType.APPLICATION_PDF);

        ResponseEntity<String> res = multipartCall("/api/v1/finance/vouchers/" + id + "/attachments", accountant, b.build());
        assertThat(res.getStatusCode()).as("body: %s", res.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(res).get("message").asText()).contains("larger than 10 MB");
    }

    /**
     * The Critical fix: a voucher attachment's storage key must not be fetchable
     * through the unauthenticated {@code /api/v1/assets/serve/**} endpoint, even
     * though {@code SecurityConfig} {@code permitAll()}s that whole path. The DTO
     * no longer exposes the key at all (see {@code VoucherAttachmentDTO}), so this
     * reads it directly off the entity — the same key an attacker would have to
     * obtain some other way (a log, a referrer, a screenshot) to try this attack.
     */
    @Test
    void aVoucherAttachmentsStorageKeyIsNotPubliclyServable() throws Exception {
        JsonNode created = createPisr(accountant);
        String id = created.get("id").asText();
        JsonNode uploaded = json(multipartCall("/api/v1/finance/vouchers/" + id + "/attachments", accountant,
                pdfPart("invoice.pdf", "Vendor invoice — تقرير")));
        String attachmentId = uploaded.get("id").asText();
        assertThat(uploaded.has("fileUrl")).as("the DTO must never expose the storage key").isFalse();

        VoucherAttachment entity = attachmentRepo.findById(UUID.fromString(attachmentId)).orElseThrow();
        String storageKey = entity.getFileUrl();
        assertThat(storageKey).contains("/private/");

        // 401/403 now rather than the controller's 404: issue #300 closed the route
        // itself, so an anonymous request for anything outside the public asset
        // folder is refused by SecurityConfig before AssetController sees it. The
        // controller's own private-prefix refusal is still there behind it — see
        // AssetServeAuthIT, which asserts both layers.
        ResponseEntity<String> raw = unauthenticatedGet(storageKey);
        assertThat(raw.getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);

        // The authenticated path still works, with a safe Content-Disposition:
        // no raw CR/LF or quotes, and an RFC 5987 filename* for the non-ASCII name.
        ResponseEntity<String> download = call(HttpMethod.GET,
                "/api/v1/finance/vouchers/attachments/" + attachmentId + "/download", accountant, null);
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        String disposition = download.getHeaders().getFirst("Content-Disposition");
        assertThat(disposition).doesNotContain("\r").doesNotContain("\n").contains("filename*=UTF-8''");
    }

    /**
     * The prefix guard must not swallow ordinary public assets (logos, listing
     * photos) — only the ones deliberately stored under {@code private/}.
     */
    @Test
    void aNormalPublicAssetStillServesUnauthenticated() throws Exception {
        MultipartBodyBuilder b = new MultipartBodyBuilder();
        b.part("file", new ByteArrayResource(REAL_PNG_BYTES) {
            @Override public String getFilename() { return "logo.png"; }
        }).contentType(MediaType.IMAGE_PNG);

        ResponseEntity<String> uploadRes = multipartCall("/api/v1/assets/upload", tenantAdmin, b.build());
        assertThat(uploadRes.getStatusCode()).as("asset upload failed: %s", uploadRes.getBody()).isEqualTo(HttpStatus.OK);
        String url = json(uploadRes).get("url").asText();
        assertThat(url).doesNotContain("/private/");

        ResponseEntity<String> served = unauthenticatedGet(url);
        assertThat(served.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Tenant isolation, not role: a 404, never a 403 that would confirm the attachment exists. */
    @Test
    void tenantBCannotDownloadOrDeleteTenantAsAttachment() throws Exception {
        JsonNode created = createPisr(accountant);
        String id = created.get("id").asText();
        JsonNode uploaded = json(multipartCall("/api/v1/finance/vouchers/" + id + "/attachments", accountant,
                pdfPart("invoice.pdf", "Vendor invoice")));
        String attachmentId = uploaded.get("id").asText();

        LandlordOrg orgB = new LandlordOrg();
        orgB.setName("VCtl-B-" + UUID.randomUUID());
        UUID tenantB = orgRepo.save(orgB).getId();
        User outsider = user(UserRole.ACCOUNTANT, tenantB);

        assertThat(call(HttpMethod.GET, "/api/v1/finance/vouchers/attachments/" + attachmentId + "/download",
                outsider, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.DELETE, "/api/v1/finance/vouchers/attachments/" + attachmentId,
                outsider, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // untouched for tenant A
        assertThat(call(HttpMethod.GET, "/api/v1/finance/vouchers/attachments/" + attachmentId + "/download",
                accountant, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** A REVERSED voucher's paper trail is frozen with it: no new attachment, no deleting an old one. */
    @Test
    void aReversedVouchersAttachmentsAreReadOnly() throws Exception {
        JsonNode created = createPisr(accountant);
        String id = created.get("id").asText();
        JsonNode uploaded = json(multipartCall("/api/v1/finance/vouchers/" + id + "/attachments", accountant,
                pdfPart("invoice.pdf", "Vendor invoice")));
        String attachmentId = uploaded.get("id").asText();

        call(HttpMethod.POST, "/api/v1/finance/vouchers/" + id + "/post", accountant, null);
        String amendBody = json.writeValueAsString(Map.of(
                "reversalDate", "2026-10-15", "reason", "x",
                "replacement", json.readValue(pisrBody(vendor.getId(), expense.getId(), "10.00", "0"), Map.class)));
        call(HttpMethod.POST, "/api/v1/finance/vouchers/" + id + "/amend", accountant, amendBody);

        ResponseEntity<String> newUpload = multipartCall("/api/v1/finance/vouchers/" + id + "/attachments", accountant,
                pdfPart("late.pdf", "Late scan"));
        assertThat(newUpload.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(newUpload).get("message").asText()).contains("REVERSED");

        ResponseEntity<String> deleteOld = call(HttpMethod.DELETE,
                "/api/v1/finance/vouchers/attachments/" + attachmentId, accountant, null);
        assertThat(deleteOld.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(deleteOld).get("message").asText()).contains("REVERSED");

        // still readable
        assertThat(call(HttpMethod.GET, "/api/v1/finance/vouchers/attachments/" + attachmentId + "/download",
                accountant, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
