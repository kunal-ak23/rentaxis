package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.BankAccountService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bank reconciliation over HTTP (finance-ops spec §3): SUPER_ADMIN acting in an
 * organisation imports (mapping wizard, preview, commit) and matches; a
 * property manager gets 403 on every route; another organisation gets 404; and
 * the lines CSV neutralises formulas from the bank's file.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BankReconciliationControllerIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired ObjectMapper json;
    @Autowired AccountService accounts;
    @Autowired BankAccountService bankAccounts;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired com.datagami.rentaxis.core.service.ledger.PropertyAccountService propertyAccounts;

    UUID tenantId;
    Account leaf;
    BankAccount ei;
    User superAdmin, accountant, manager;

    static final String BASE = "/api/v1/finance/bank-reconciliation";
    static final String CSV = """
            Date,Description,Debit,Credit,Balance
            01/09/2026,=HYPERLINK("http://x"),,100.00,100.00
            02/09/2026,SERVICE CHARGE,5.00,,95.00
            """;
    static final String PROFILE = """
            {"fileKind":"CSV","headerRow":1,"firstDataRow":2,"amountMode":"SPLIT","dateFormats":["dd/MM/yyyy"],
             "columns":{"txnDate":"Date","description":"Description","debit":"Debit","credit":"Credit","balance":"Balance"}}""";

    @BeforeEach
    void setUp() {
        tenantId = tenant("BankRec-");
        leaf = accounts.createLeaf("Emirates Islamic - Marina Tower", accounts.getAccountByCode("A-02-02"), null);
        BankAccount b = new BankAccount();
        b.setBankName("Emirates Islamic");
        b.setAccountNumber("0123");
        b.setCoaAccount(leaf);
        ei = bankAccounts.createBankAccount(b);
        superAdmin = user(UserRole.SUPER_ADMIN, tenantId);
        accountant = user(UserRole.ACCOUNTANT, tenantId);
        manager = user(UserRole.PROPERTY_MANAGER, tenantId);
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    private UUID tenant(String prefix) {
        LandlordOrg org = new LandlordOrg();
        org.setName(prefix + UUID.randomUUID());
        UUID id = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(id);
        accounts.seedDefaultAccounts();
        propertyAccounts.seedDefaultTemplateAndDefaults();
        return id;
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

    private RestClient.RequestBodySpec spec(HttpMethod method, String path, User caller) {
        return RestClient.builder().build().method(method).uri(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", caller.getId().toString()).header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString());
    }

    private ResponseEntity<String> call(HttpMethod method, String path, User caller, Object body) {
        RestClient.RequestBodySpec s = spec(method, path, caller);
        if (body != null) s = s.contentType(MediaType.APPLICATION_JSON).body(body);
        return s.retrieve().onStatus(x -> true, (req, res) -> { }).toEntity(String.class);
    }

    private ResponseEntity<String> upload(User caller, String csv, String profile, boolean dryRun) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "september.csv"; }
        });
        if (profile != null) parts.add("profile", profile);
        parts.add("dryRun", Boolean.toString(dryRun));
        return spec(HttpMethod.POST, BASE + "/bank-accounts/" + ei.getId() + "/imports", caller)
                .contentType(MediaType.MULTIPART_FORM_DATA).body(parts)
                .retrieve().onStatus(x -> true, (req, res) -> { }).toEntity(String.class);
    }

    private JsonNode json(ResponseEntity<String> r) throws Exception {
        return json.readTree(r.getBody());
    }

    @Test
    void aSuperAdminMapsPreviewsImportsAndMatchesAndTheCsvNeutralisesFormulas() throws Exception {
        JsonNode list = json(call(HttpMethod.GET, BASE + "/bank-accounts", superAdmin, null));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("leaves").get(0).get("id").asText()).isEqualTo(leaf.getId().toString());
        assertThat(list.get(0).get("needsLeaf").asBoolean()).isFalse();

        ResponseEntity<String> required = upload(superAdmin, CSV, null, true);
        assertThat(required.getStatusCode()).as(required.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(required).get("status").asText()).isEqualTo("PROFILE_REQUIRED");
        // The wizard's live preview: the unsaved mapping, nothing written.
        JsonNode preview = json(upload(superAdmin, CSV, PROFILE, true));
        assertThat(preview.get("status").asText()).isEqualTo("PREVIEW");
        assertThat(preview.get("rows").get(1).get("amount").decimalValue()).isEqualByComparingTo("-5.00");
        assertThat(call(HttpMethod.PUT, BASE + "/bank-accounts/" + ei.getId() + "/profile", superAdmin, PROFILE)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode imported = json(upload(superAdmin, CSV, null, false));
        assertThat(imported.get("status").asText()).isEqualTo("IMPORTED");
        assertThat(imported.get("linesNew").asInt()).isEqualTo(2);

        JsonNode ws = json(call(HttpMethod.GET, BASE + "/bank-accounts/" + ei.getId() + "/workspace?state=UNMATCHED", accountant, null));
        assertThat(ws.get("statementLines")).hasSize(2);
        String charge = ws.get("statementLines").get(1).get("id").asText();
        ResponseEntity<String> posted = call(HttpMethod.POST, BASE + "/lines/actions/post", superAdmin,
                Map.of("statementLineIds", List.of(charge), "kind", "CHARGE", "vatIncluded", true));
        assertThat(posted.getStatusCode()).as(posted.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(posted).get("entryNumbers").get(0).asText()).startsWith("BNK");

        ResponseEntity<byte[]> csv = spec(HttpMethod.GET, BASE + "/bank-accounts/" + ei.getId() + "/lines.csv", accountant)
                .retrieve().onStatus(x -> true, (req, res) -> { }).toEntity(byte[].class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        String text = new String(csv.getBody(), StandardCharsets.UTF_8);
        assertThat(text).contains("\"'=HYPERLINK(\"\"http://x\"\")\"").doesNotContain(",=HYPERLINK");
        assertThat(text).contains("SERVICE CHARGE").contains("CONFIRMED");
    }

    @Test
    void aPropertyManagerHasNoneOfItAndAnotherOrganisationFindsNothing() throws Exception {
        for (String path : List.of(BASE + "/bank-accounts", BASE + "/bank-accounts/" + ei.getId() + "/workspace",
                BASE + "/bank-accounts/" + ei.getId() + "/imports", BASE + "/bank-accounts/" + ei.getId() + "/ledgers")) {
            assertThat(call(HttpMethod.GET, path, manager, null).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(call(HttpMethod.POST, BASE + "/matches", manager, Map.of("statementLineIds", List.of()))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload(manager, CSV, PROFILE, true).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        UUID other = tenant("BankRec-B-");
        User otherAccountant = user(UserRole.ACCOUNTANT, other);
        assertThat(call(HttpMethod.GET, BASE + "/bank-accounts/" + ei.getId() + "/workspace", otherAccountant, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PUT, BASE + "/bank-accounts/" + ei.getId() + "/ledgers", otherAccountant,
                Map.of("accountIds", List.of())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(call(HttpMethod.GET, BASE + "/bank-accounts", otherAccountant, null))).isEmpty();
    }

    /** Finance-ops spec §4 over HTTP: SUPER_ADMIN reconciles, finalizes and downloads; only an admin reopens. */
    @Test
    void aSuperAdminReconcilesFinalizesAndDownloadsAndOnlyAnAdminReopens() throws Exception {
        call(HttpMethod.PUT, BASE + "/bank-accounts/" + ei.getId() + "/profile", superAdmin, PROFILE);
        assertThat(json(upload(superAdmin, CSV, null, false)).get("linesNew").asInt()).isEqualTo(2);

        ResponseEntity<String> created = call(HttpMethod.POST, BASE + "/bank-accounts/" + ei.getId() + "/reconciliations", superAdmin,
                Map.of("periodFrom", "2026-09-01", "periodTo", "2026-09-02"));
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode draft = json(created);
        String id = draft.get("id").asText();
        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("unrecordedItems")).hasSize(2);
        assertThat(draft.get("canFinalize").asBoolean()).isFalse();

        // The statement CSV neutralises the bank's formula.
        ResponseEntity<byte[]> csv = spec(HttpMethod.GET, BASE + "/reconciliations/" + id + ".csv", accountant)
                .retrieve().onStatus(x -> true, (req, res) -> { }).toEntity(byte[].class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        String text = new String(csv.getBody(), StandardCharsets.UTF_8);
        assertThat(text).contains("\"'=HYPERLINK(\"\"http://x\"\")\"").doesNotContain(",=HYPERLINK");
        assertThat(text).contains("Unrecorded statement item");
        // F14-48: amounts with two decimals; ?lang=ar gives Arabic labels.
        assertThat(text).contains("Booked after the period,0.00");
        String arText = new String(spec(HttpMethod.GET, BASE + "/reconciliations/" + id + ".csv?lang=ar", accountant)
                .retrieve().toEntity(byte[].class).getBody(), StandardCharsets.UTF_8);
        assertThat(arText).contains("بند كشف غير مسجل").contains("كشف التسوية البنكية");

        JsonNode ws = json(call(HttpMethod.GET, BASE + "/bank-accounts/" + ei.getId() + "/workspace", superAdmin, null));
        for (JsonNode l : ws.get("statementLines")) {
            String kind = l.get("amount").decimalValue().signum() > 0 ? "INTEREST" : "CHARGE";
            assertThat(call(HttpMethod.POST, BASE + "/lines/actions/post", superAdmin,
                    Map.of("statementLineIds", List.of(l.get("id").asText()), "kind", kind)).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        JsonNode ready = json(call(HttpMethod.GET, BASE + "/reconciliations/" + id, accountant, null));
        assertThat(ready.get("difference").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(ready.get("canFinalize").asBoolean()).as(ready.get("checks").toString()).isTrue();
        assertThat(call(HttpMethod.GET, BASE + "/reconciliations/" + id, manager, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> finalized = call(HttpMethod.POST, BASE + "/reconciliations/" + id + "/finalize", superAdmin, null);
        assertThat(finalized.getStatusCode()).as(finalized.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(finalized).get("status").asText()).isEqualTo("FINALIZED");
        assertThat(json(call(HttpMethod.GET, BASE + "/bank-accounts", superAdmin, null)).get(0).get("reconciledThrough").asText())
                .isEqualTo("2026-09-02");

        ResponseEntity<byte[]> pdf = spec(HttpMethod.GET, BASE + "/reconciliations/" + id + ".pdf?lang=ar", accountant)
                .retrieve().onStatus(x -> true, (req, res) -> { }).toEntity(byte[].class);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdf.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(new String(pdf.getBody(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        // Reopen: an accountant or a property manager cannot; a tenant admin can, with a reason.
        Map<String, String> why = Map.of("reason", "Charge booked to the wrong leaf");
        assertThat(call(HttpMethod.POST, BASE + "/reconciliations/" + id + "/reopen", accountant, why).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, BASE + "/reconciliations/" + id + "/reopen", manager, why).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        User admin = user(UserRole.TENANT_ADMIN, tenantId);
        assertThat(call(HttpMethod.POST, BASE + "/reconciliations/" + id + "/reopen", admin, Map.of("reason", " "))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> reopened = call(HttpMethod.POST, BASE + "/reconciliations/" + id + "/reopen", admin, why);
        assertThat(reopened.getStatusCode()).as(reopened.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(reopened).get("status").asText()).isEqualTo("REOPENED");
        assertThat(json(reopened).get("reopenedByName").asText()).isEqualTo("TENANT_ADMIN");

        UUID other = tenant("BankRec-C-");
        User otherAdmin = user(UserRole.TENANT_ADMIN, other);
        assertThat(call(HttpMethod.GET, BASE + "/reconciliations/" + id, otherAdmin, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST, BASE + "/reconciliations/" + id + "/finalize", otherAdmin, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
