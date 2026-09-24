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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Supplier AP over HTTP (finance-ops spec §2): the role gate (SUPER_ADMIN acting
 * in an organisation, TENANT_ADMIN, ACCOUNTANT; PROPERTY_MANAGER only on the
 * property-filtered aging of an assigned property), the tenant-less SUPER_ADMIN
 * guard, posting a payment with allocations, and tenant isolation. Same plumbing
 * as {@code VoucherControllerIT}: the legacy {@code X-User-*} headers over a real port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PayablesControllerIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired ObjectMapper json;
    @Autowired AccountService accounts;
    @Autowired VendorService vendorService;
    @Autowired VoucherService vouchers;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UserPropertyAssignmentRepository assignments;

    UUID tenantId;
    Property p1, p2;
    Account rmP1, bank;
    Vendor vendor;
    Voucher invoice;
    User accountant, tenantAdmin, superAdmin, manager, renter;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("APCtl-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        p1 = property("Marina Tower");
        p2 = property("Palm Residence");
        rmP1 = accounts.createLeaf("Repairs & Maintenance - Marina Tower", accounts.getAccountByCode("D-01"), p1.getId());
        bank = accounts.createLeaf("Emirates Islamic - Marina Tower", accounts.getAccountByCode("A-02-02"), null);
        Vendor v = new Vendor();
        v.setNameEn("Gulf AC Services LLC");
        v.setTrn("100123456700003");
        vendor = vendorService.createVendor(v);
        fiscal.setBooksStartDate(LocalDate.of(2026, 8, 1));
        invoice = vouchers.post(vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.PISR,
                LocalDate.of(2026, 8, 1), vendor.getId(), "INV-7781", "AC", null, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(rmP1.getId(), "AC", new BigDecimal("1450.00"),
                        BigDecimal.ZERO, p1.getId(), null)))).getId());

        accountant = user(UserRole.ACCOUNTANT);
        tenantAdmin = user(UserRole.TENANT_ADMIN);
        superAdmin = user(UserRole.SUPER_ADMIN);
        manager = user(UserRole.PROPERTY_MANAGER);
        renter = user(UserRole.RENTER);
        UserPropertyAssignment a = new UserPropertyAssignment();
        a.setUserId(manager.getId());
        a.setPropertyId(p1.getId());
        assignments.save(a);
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    private Property property(String name) {
        Property p = new Property();
        p.setNameEn(name);
        p.setEmirate(Emirate.DUBAI);
        return propertyRepo.save(p);
    }

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

    private ResponseEntity<byte[]> bytes(String path, User caller) {
        return RestClient.builder().build().get().uri(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString())
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(byte[].class);
    }

    private JsonNode json(ResponseEntity<String> res) {
        try {
            return json.readTree(res.getBody());
        } catch (Exception e) {
            throw new AssertionError("Response was not JSON: " + res.getBody(), e);
        }
    }

    private String bpvBody(String amount) throws Exception {
        return json.writeValueAsString(Map.of(
                "docType", "BPV",
                "docDate", "2026-08-15",
                "vendorId", vendor.getId().toString(),
                "paymentAccountId", bank.getId().toString(),
                "paymentMethod", "TRANSFER",
                "paymentReference", "TRF-7781",
                "lines", List.of(Map.of(
                        "accountId", vendor.getPayableAccount().getId().toString(),
                        "amount", new BigDecimal(amount),
                        "vatRate", BigDecimal.ZERO))));
    }

    /** A posted BPV settling part of the invoice, created and posted by {@code caller} over HTTP. */
    private String postPayment(User caller, String amount, String allocate) throws Exception {
        ResponseEntity<String> draft = call(HttpMethod.POST, "/api/v1/finance/vouchers", caller, bpvBody(amount));
        assertThat(draft.getStatusCode()).as(draft.getBody()).isEqualTo(HttpStatus.CREATED);
        String id = json(draft).get("id").asText();
        String body = allocate == null ? null : json.writeValueAsString(Map.of("allocations",
                List.of(Map.of("invoiceId", invoice.getId().toString(), "amount", new BigDecimal(allocate)))));
        ResponseEntity<String> posted = call(HttpMethod.POST, "/api/v1/finance/vouchers/" + id + "/post", caller, body);
        assertThat(posted.getStatusCode()).as(posted.getBody()).isEqualTo(HttpStatus.OK);
        return id;
    }

    // ------------------------------------------------------------------

    @Test
    void aSuperAdminActingInAnOrganisationUsesEveryApEndpoint() throws Exception {
        String payment = postPayment(superAdmin, "1000.00", "600.00");

        JsonNode inv = json(call(HttpMethod.GET, "/api/v1/finance/vouchers/" + invoice.getId(), superAdmin, null));
        assertThat(inv.get("settlement").get("status").asText()).isEqualTo("PART_PAID");
        assertThat(inv.get("settlement").get("open").decimalValue()).isEqualByComparingTo("850.00");
        assertThat(inv.get("dueDate").asText()).isEqualTo("2026-08-31");
        JsonNode pay = json(call(HttpMethod.GET, "/api/v1/finance/vouchers/" + payment, superAdmin, null));
        assertThat(pay.get("paymentMethod").asText()).isEqualTo("TRANSFER");
        assertThat(pay.get("settlement").get("open").decimalValue()).isEqualByComparingTo("400.00");

        // Apply the 400 advance.
        ResponseEntity<String> alloc = call(HttpMethod.POST, "/api/v1/finance/voucher-allocations", superAdmin,
                json.writeValueAsString(Map.of("paymentId", payment, "invoiceId", invoice.getId().toString(),
                        "amount", new BigDecimal("400.00"))));
        assertThat(alloc.getStatusCode()).as(alloc.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(alloc).get("invoiceNumber").asText()).isEqualTo("INV-7781");

        ResponseEntity<String> list = call(HttpMethod.GET, "/api/v1/finance/vouchers/" + invoice.getId() + "/allocations",
                superAdmin, null);
        assertThat(json(list)).hasSize(2);

        ResponseEntity<String> release = call(HttpMethod.DELETE,
                "/api/v1/finance/voucher-allocations/" + json(alloc).get("id").asText() + "?reason=wrong", superAdmin, null);
        assertThat(release.getStatusCode()).as(release.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(release).get("live").asBoolean()).isFalse();

        ResponseEntity<String> aging = call(HttpMethod.GET, "/api/v1/finance/reports/payables-aging?asOf=2026-09-30",
                superAdmin, null);
        assertThat(aging.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode row = json(aging).get("rows").get(0);
        assertThat(row.get("figures").get("d1to30").decimalValue()).isEqualByComparingTo("850.00");
        assertThat(row.get("figures").get("advances").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(row.get("figures").get("delta").decimalValue()).isEqualByComparingTo("0.00");

        assertThat(call(HttpMethod.GET, "/api/v1/finance/vouchers/open-items?vendorId=" + vendor.getId(), superAdmin, null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/v1/finance/vouchers/advances?vendorId=" + vendor.getId(), superAdmin, null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/v1/finance/ap-opening-items", superAdmin, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> dup = call(HttpMethod.GET, "/api/v1/finance/vouchers/duplicate-check?vendorId="
                + vendor.getId() + "&invoiceNumber=inv%207781", superAdmin, null);
        assertThat(json(dup).get("duplicateOf").asText()).isEqualTo(invoice.getVoucherNumber());

        ResponseEntity<byte[]> pdf = bytes("/api/v1/finance/vendors/" + vendor.getId()
                + "/statement.pdf?from=2026-08-01&to=2026-09-30&lang=ar", superAdmin);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(pdf.getBody(), 0, 5)).isEqualTo("%PDF-");
        ResponseEntity<byte[]> csv = bytes("/api/v1/finance/reports/payables-aging.csv?asOf=2026-09-30", superAdmin);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(csv.getBody(), java.nio.charset.StandardCharsets.UTF_8)).contains("INV-7781", "850.00");
    }

    @Test
    void aSuperAdminWithNoOrganisationSelectedGetsA400() {
        for (String path : List.of("/api/v1/finance/reports/payables-aging", "/api/v1/finance/ap-opening-items",
                "/api/v1/finance/vouchers/open-items")) {
            ResponseEntity<String> res = send(HttpMethod.GET, path, superAdmin, null, false);
            assertThat(res.getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody()).contains("Select an organisation first");
        }
    }

    @Test
    void accountantAndTenantAdminAreAdmitted() throws Exception {
        postPayment(accountant, "100.00", "100.00");
        assertThat(call(HttpMethod.GET, "/api/v1/finance/reports/payables-aging", tenantAdmin, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> item = call(HttpMethod.POST, "/api/v1/finance/ap-opening-items", accountant,
                json.writeValueAsString(Map.of("vendorId", vendor.getId().toString(), "invoiceNumber", "OLD-1",
                        "invoiceDate", "2026-07-01", "amount", new BigDecimal("500.00"))));
        assertThat(item.getStatusCode()).as(item.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(item).get("dueDate").asText()).isEqualTo("2026-07-31");
    }

    @Test
    void aManagerSeesOnlyTheFilteredAgingOfAnAssignedProperty() {
        ResponseEntity<String> none = call(HttpMethod.GET, "/api/v1/finance/reports/payables-aging", manager, null);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> own = call(HttpMethod.GET,
                "/api/v1/finance/reports/payables-aging?asOf=2026-09-30&propertyId=" + p1.getId(), manager, null);
        assertThat(own.getStatusCode()).as(own.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(own).get("vendorLevel").asBoolean()).isFalse();
        assertThat(json(own).get("rows").get(0).get("figures").get("advances").isNull()).isTrue();

        ResponseEntity<String> foreign = call(HttpMethod.GET,
                "/api/v1/finance/reports/payables-aging?propertyId=" + p2.getId(), manager, null);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Nothing else in AP is open to a manager.
        assertThat(call(HttpMethod.GET, "/api/v1/finance/ap-opening-items", manager, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/v1/finance/vouchers/open-items", manager, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aRenterIsRefused() {
        assertThat(call(HttpMethod.GET, "/api/v1/finance/reports/payables-aging", renter, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // A well-formed body, so the refusal is the role gate and not validation.
        String body = "{\"paymentId\":\"" + invoice.getId() + "\",\"invoiceId\":\"" + invoice.getId()
                + "\",\"amount\":1}";
        assertThat(call(HttpMethod.POST, "/api/v1/finance/voucher-allocations", renter, body).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anotherTenantsVendorAndInvoiceAreNotFound() throws Exception {
        String payment = postPayment(accountant, "100.00", null);

        LandlordOrg other = new LandlordOrg();
        other.setName("APCtl-B-" + UUID.randomUUID());
        UUID tenantB = orgRepo.save(other).getId();
        User outsider = new User();
        outsider.setEmail("acct-b-" + UUID.randomUUID() + "@t.io");
        outsider.setName("B");
        outsider.setRole(UserRole.ACCOUNTANT);
        outsider.setStatus(UserStatus.ACTIVE);
        outsider.setPasswordHash("x");
        outsider.setTenantId(tenantB);
        outsider = userRepo.save(outsider);

        assertThat(call(HttpMethod.GET, "/api/v1/finance/vendors/" + vendor.getId() + "/open-items", outsider, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST, "/api/v1/finance/voucher-allocations", outsider,
                json.writeValueAsString(Map.of("paymentId", payment, "invoiceId", invoice.getId().toString(),
                        "amount", new BigDecimal("10")))).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(call(HttpMethod.GET, "/api/v1/finance/reports/payables-aging", outsider, null)).get("rows"))
                .isEmpty();
    }

    @Test
    void aVendorKeepsItsTermsAndRefusesABadTrn() throws Exception {
        String path = "/api/v1/vendors/" + vendor.getId();
        ResponseEntity<String> bad = call(HttpMethod.PUT, path, tenantAdmin,
                json.writeValueAsString(Map.of("nameEn", "Gulf AC Services LLC", "trn", "12345", "active", true)));
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> terms = call(HttpMethod.PUT, path, accountant, json.writeValueAsString(Map.of(
                "nameEn", "Gulf AC Services LLC", "trn", "100123456700003", "paymentTermsDays", 45, "active", true)));
        assertThat(terms.getStatusCode()).as(terms.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(terms).get("paymentTermsDays").asInt()).isEqualTo(45);
        // A later update that leaves the terms out keeps them.
        ResponseEntity<String> rename = call(HttpMethod.PUT, path, accountant, json.writeValueAsString(Map.of(
                "nameEn", "Gulf AC Services", "trn", "100123456700003", "active", true)));
        assertThat(json(rename).get("paymentTermsDays").asInt()).isEqualTo(45);
    }
}
