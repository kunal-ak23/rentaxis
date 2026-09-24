package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.voucher.VoucherService;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payment runs and issued cheques over HTTP (finance-ops spec §2, PR 3b): the
 * role gate (SUPER_ADMIN acting in an organisation, TENANT_ADMIN, ACCOUNTANT; no
 * access at all for PROPERTY_MANAGER), the tenant-less SUPER_ADMIN guard, a run
 * created, previewed, posted and downloaded, and a PDC presented — and another
 * tenant's cheque not found. Same plumbing as {@code PayablesControllerIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentRunControllerIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired ObjectMapper json;
    @Autowired AccountService accounts;
    @Autowired VendorService vendorService;
    @Autowired VoucherService vouchers;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired TenantDefaultAccountMappingRepository defaults;
    @Autowired UserPropertyAssignmentRepository assignments;

    UUID tenantId;
    Property p1;
    Account rmP1, bank;
    Vendor vendor;
    Voucher invoice;
    User accountant, tenantAdmin, superAdmin, manager;

    @BeforeEach
    void setUp() {
        tenantId = tenant("RunCtl-");
        p1 = new Property();
        p1.setNameEn("Marina Tower");
        p1.setEmirate(Emirate.DUBAI);
        p1 = propertyRepo.save(p1);
        rmP1 = accounts.createLeaf("Repairs & Maintenance - Marina Tower", accounts.getAccountByCode("D-01"), p1.getId());
        bank = accounts.createLeaf("Emirates Islamic - Marina Tower", accounts.getAccountByCode("A-02-02"), null);
        Vendor v = new Vendor();
        v.setNameEn("Gulf AC Services LLC");
        v.setTrn("100123456700003");
        v.setIban("AE070331234567890123456");
        vendor = vendorService.createVendor(v);
        fiscal.setBooksStartDate(LocalDate.of(2026, 8, 1));
        invoice = vouchers.post(vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.PISR,
                LocalDate.of(2026, 8, 1), vendor.getId(), "INV-7781", "AC", null, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(rmP1.getId(), "AC", new BigDecimal("1450.00"),
                        BigDecimal.ZERO, p1.getId(), null)))).getId());
        accountant = user(UserRole.ACCOUNTANT, tenantId);
        tenantAdmin = user(UserRole.TENANT_ADMIN, tenantId);
        superAdmin = user(UserRole.SUPER_ADMIN, tenantId);
        manager = user(UserRole.PROPERTY_MANAGER, tenantId);
        UserPropertyAssignment a = new UserPropertyAssignment();
        a.setUserId(manager.getId());
        a.setPropertyId(p1.getId());
        assignments.save(a);
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    private UUID tenant(String prefix) {
        LandlordOrg org = new LandlordOrg();
        org.setName(prefix + UUID.randomUUID());
        UUID id = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(id);
        accounts.seedDefaultAccounts();
        TenantDefaultAccountMapping m = new TenantDefaultAccountMapping();
        m.setRole(AccountRole.PDC_PAYABLE);
        m.setAccount(accounts.getAccountByCode("B-02-001"));
        defaults.save(m);
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

    private ResponseEntity<String> call(HttpMethod method, String path, User caller, Object body) {
        return send(method, path, caller, body, true);
    }

    private ResponseEntity<String> send(HttpMethod method, String path, User caller, Object body, boolean withTenant) {
        RestClient.RequestBodySpec spec = RestClient.builder().build().method(method)
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name());
        if (withTenant) {
            spec = spec.header("X-Tenant-Id", caller.getTenantId().toString())
                    .header("X-User-Tenant-Id", caller.getTenantId().toString());
        }
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

    private String runBody(String method, String chequeDate, String firstCheque) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("paymentDate", "2026-09-10");
        body.put("paymentAccountId", bank.getId().toString());
        body.put("method", method);
        if (chequeDate != null) body.put("chequeDate", chequeDate);
        if (firstCheque != null) body.put("firstChequeNumber", firstCheque);
        body.put("items", List.of(Map.of("invoiceId", invoice.getId().toString(), "amount", new BigDecimal("1450.00"),
                "applyAdvance", true)));
        return json.writeValueAsString(body);
    }

    @Test
    void anAccountantCreatesPreviewsPostsAndDownloadsARun() throws Exception {
        ResponseEntity<String> created = call(HttpMethod.POST, "/api/v1/finance/payment-runs", accountant,
                runBody("TRANSFER", null, null));
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        String id = json(created).get("id").asText();

        JsonNode preview = json(call(HttpMethod.GET, "/api/v1/finance/payment-runs/" + id + "/preview", accountant, null));
        assertThat(preview.get("postable").asBoolean()).isTrue();
        assertThat(preview.get("netPayment").decimalValue()).isEqualByComparingTo("1450.00");

        ResponseEntity<String> posted = call(HttpMethod.POST, "/api/v1/finance/payment-runs/" + id + "/post", accountant, null);
        assertThat(posted.getStatusCode()).as(posted.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(posted).get("status").asText()).isEqualTo("POSTED");
        // A second submission answers the same, and posts nothing more.
        ResponseEntity<String> again = call(HttpMethod.POST, "/api/v1/finance/payment-runs/" + id + "/post", accountant, null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(again).get("items").get(0).get("bpvId").asText())
                .isEqualTo(json(posted).get("items").get(0).get("bpvId").asText());

        ResponseEntity<byte[]> csv = RestClient.builder().build().get()
                .uri(URI.create("http://localhost:" + port + "/api/v1/finance/payment-runs/" + id + "/bank-file.csv"))
                .header("X-User-Id", accountant.getId().toString()).header("X-User-Role", "ACCOUNTANT")
                .header("X-Tenant-Id", tenantId.toString()).header("X-User-Tenant-Id", tenantId.toString())
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(byte[].class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(new String(csv.getBody(), StandardCharsets.UTF_8)).contains("AE070331234567890123456").contains("1450.00");
    }

    @Test
    void aSuperAdminActingInTheOrganisationPostsAChequeRunAndATenantAdminPresentsTheCheque() throws Exception {
        ResponseEntity<String> created = call(HttpMethod.POST, "/api/v1/finance/payment-runs", superAdmin,
                runBody("CHEQUE", "2026-09-20", "000031"));
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        String id = json(created).get("id").asText();
        assertThat(call(HttpMethod.POST, "/api/v1/finance/payment-runs/" + id + "/post", superAdmin, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        JsonNode register = json(call(HttpMethod.GET, "/api/v1/finance/issued-cheques?status=ISSUED", tenantAdmin, null));
        assertThat(register).hasSize(1);
        assertThat(register.get(0).get("chequeNumber").asText()).isEqualTo("000031");
        String chequeId = register.get(0).get("id").asText();
        JsonNode summary = json(call(HttpMethod.GET, "/api/v1/finance/issued-cheques/summary", accountant, null));
        assertThat(summary.get("outstandingTotal").decimalValue()).isEqualByComparingTo("1450.00");
        assertThat(summary.get("difference").decimalValue()).isEqualByComparingTo("0.00");

        ResponseEntity<String> early = call(HttpMethod.POST, "/api/v1/finance/issued-cheques/" + chequeId + "/present",
                tenantAdmin, Map.of("date", "2026-09-15"));
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(early.getBody()).contains("cannot be presented before 20/09/2026");
        ResponseEntity<String> presented = call(HttpMethod.POST, "/api/v1/finance/issued-cheques/" + chequeId + "/present",
                tenantAdmin, Map.of("date", "2026-09-20"));
        assertThat(presented.getStatusCode()).as(presented.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(presented).get("status").asText()).isEqualTo("PRESENTED");

        // Another organisation's accountant: not found, not forbidden.
        UUID other = tenant("RunCtl-B-");
        User otherAccountant = user(UserRole.ACCOUNTANT, other);
        assertThat(call(HttpMethod.POST, "/api/v1/finance/issued-cheques/" + chequeId + "/unpresent", otherAccountant,
                Map.of("date", "2026-09-21", "reason", "x")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/v1/finance/payment-runs/" + id, otherAccountant, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aPropertyManagerHasNoRunOrChequeAccess() throws Exception {
        for (String path : List.of("/api/v1/finance/payment-runs", "/api/v1/finance/payment-runs/candidates",
                "/api/v1/finance/issued-cheques", "/api/v1/finance/issued-cheques/summary")) {
            assertThat(call(HttpMethod.GET, path, manager, null).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(call(HttpMethod.POST, "/api/v1/finance/payment-runs", manager, runBody("TRANSFER", null, null))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/api/v1/finance/issued-cheques/" + UUID.randomUUID() + "/present", manager,
                Map.of("date", "2026-09-20")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/api/v1/finance/issued-cheques/opening", manager, Map.of(
                "vendorId", vendor.getId().toString(), "bankAccountId", bank.getId().toString(), "chequeNumber", "000900",
                "chequeDate", "2026-09-20", "amount", new BigDecimal("10.00"))).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aSuperAdminWithNoOrganisationSelectedIsAskedToChooseOne() {
        ResponseEntity<String> res = send(HttpMethod.GET, "/api/v1/finance/payment-runs", superAdmin, null, false);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("Select an organisation first");
    }
}
