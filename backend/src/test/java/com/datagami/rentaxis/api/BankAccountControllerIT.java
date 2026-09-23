package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.BankAccountRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gap #67: {@code POST /api/v1/bank-accounts} with {@code coaAccount: {id}} — the web
 * form's normal path whenever a ledger account is picked — returned a 500.
 *
 * <p>{@code Account.id} is READ_ONLY to Jackson (a client-supplied id on an account
 * create must never turn an insert into an update), so the {@code {id}} stub arrived
 * as an Account with a null id, and {@code save()} tripped over a transient reference
 * at flush. The fix resolves both references by id inside the tenant, so the same
 * code also refuses another tenant's account or property, and refuses anything that
 * is not a BANK leaf.</p>
 *
 * <p>Full HTTP, because the bug lives in request binding: a service-level test that
 * hands in a managed Account would never have seen it.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BankAccountControllerIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired PropertyService propertyService;
    @Autowired AccountResolver resolver;
    @Autowired BankAccountRepository bankAccountRepo;

    private User admin;
    private UUID propertyId;
    private UUID bankLeafId;
    private UUID rentReceivableLeafId;
    private UUID bankGroupId;

    private User otherAdmin;
    private UUID otherBankLeafId;
    private UUID otherPropertyId;

    @BeforeEach
    void setUp() {
        UUID t2 = tenant("BA-other-");
        Property op = property("Other Tower");
        otherPropertyId = op.getId();
        otherBankLeafId = resolver.resolve(AccountRole.BANK, otherPropertyId).getId();
        otherAdmin = admin(t2);

        UUID t1 = tenant("BA-");
        Property p = property("Marina Heights");
        propertyId = p.getId();
        bankLeafId = resolver.resolve(AccountRole.BANK, propertyId).getId();
        rentReceivableLeafId = resolver.resolve(AccountRole.RENT_RECEIVABLE, propertyId).getId();
        bankGroupId = accountService.getAccountByCode("A-02-02").getId();
        admin = admin(t1);
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private UUID tenant(String prefix) {
        LandlordOrg org = new LandlordOrg();
        org.setName(prefix + UUID.randomUUID());
        UUID id = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(id);
        accountService.seedDefaultAccounts();
        propertyAccountService.seedDefaultTemplateAndDefaults();
        return id;
    }

    private Property property(String name) {
        Property p = new Property();
        p.setNameEn(name + " " + UUID.randomUUID());
        p.setEmirate(Emirate.DUBAI);
        return propertyService.createProperty(p);
    }

    private User admin(UUID tenantId) {
        User u = new User();
        u.setEmail("ba-admin-" + UUID.randomUUID() + "@t.io");
        u.setName("Admin");
        u.setRole(UserRole.TENANT_ADMIN);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        return userRepo.save(u);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> call(User caller, HttpMethod method, String path, Object body) {
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
        return spec.retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(Map.class);
    }

    private static Map<String, Object> body(String last4, UUID coaAccountId, UUID propertyId) {
        Map<String, Object> b = new HashMap<>();
        b.put("bankName", "Emirates NBD");
        b.put("accountNumber", "10000" + last4);
        b.put("currency", "AED");
        if (coaAccountId != null) b.put("coaAccount", Map.of("id", coaAccountId.toString()));
        if (propertyId != null) b.put("property", Map.of("id", propertyId.toString()));
        return b;
    }

    @SuppressWarnings("unchecked")
    private static String coaId(ResponseEntity<Map> res) {
        Map<String, Object> coa = (Map<String, Object>) res.getBody().get("coaAccount");
        return coa == null ? null : (String) coa.get("id");
    }

    @SuppressWarnings("unchecked")
    private static String propId(ResponseEntity<Map> res) {
        Map<String, Object> p = (Map<String, Object>) res.getBody().get("property");
        return p == null ? null : (String) p.get("id");
    }

    private long bankAccountCount() {
        return bankAccountRepo.count();
    }

    // ---- create ----

    @Test
    void createWithAnExplicitBankLeafLinksIt() {
        var res = call(admin, HttpMethod.POST, "/api/v1/bank-accounts", body("0004", bankLeafId, null));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(coaId(res)).isEqualTo(bankLeafId.toString());
    }

    @Test
    void createWithAnExplicitBankLeafAndPropertyLinksBoth() {
        var res = call(admin, HttpMethod.POST, "/api/v1/bank-accounts", body("0005", bankLeafId, propertyId));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(coaId(res)).isEqualTo(bankLeafId.toString());
        assertThat(propId(res)).isEqualTo(propertyId.toString());
    }

    @Test
    void createWithAnotherTenantsAccountIsRefused() {
        long before = bankAccountCount();
        var res = call(admin, HttpMethod.POST, "/api/v1/bank-accounts", body("0006", otherBankLeafId, null));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(bankAccountCount()).isEqualTo(before);
    }

    @Test
    void createWithAnotherTenantsPropertyIsRefused() {
        long before = bankAccountCount();
        var res = call(admin, HttpMethod.POST, "/api/v1/bank-accounts", body("0007", null, otherPropertyId));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(bankAccountCount()).isEqualTo(before);
    }

    @Test
    void createWithANonBankAccountIs400() {
        var res = call(admin, HttpMethod.POST, "/api/v1/bank-accounts", body("0008", rentReceivableLeafId, null));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void createWithTheBankGroupItselfIs400() {
        var res = call(admin, HttpMethod.POST, "/api/v1/bank-accounts", body("0009", bankGroupId, null));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void createWithoutAnAccountStillDefaultsToThePropertyBankLeaf() {
        var res = call(admin, HttpMethod.POST, "/api/v1/bank-accounts", body("0010", null, propertyId));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(coaId(res)).isEqualTo(bankLeafId.toString());
    }

    @Test
    void createWithAnIdOnlyStubWithoutAnIdIs400() {
        Map<String, Object> b = body("0011", null, null);
        b.put("coaAccount", Map.of());
        var res = call(admin, HttpMethod.POST, "/api/v1/bank-accounts", b);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    // ---- update ----

    private String createPlain() {
        var res = call(admin, HttpMethod.POST, "/api/v1/bank-accounts", body("0012", null, null));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return (String) res.getBody().get("id");
    }

    @Test
    void updateToAnExplicitBankLeafLinksIt() {
        String id = createPlain();
        var res = call(admin, HttpMethod.PUT, "/api/v1/bank-accounts/" + id, body("0012", bankLeafId, propertyId));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(coaId(res)).isEqualTo(bankLeafId.toString());
        assertThat(propId(res)).isEqualTo(propertyId.toString());
    }

    @Test
    void updateToAnotherTenantsAccountIsRefusedAndLeavesTheRowAlone() {
        String id = createPlain();
        var res = call(admin, HttpMethod.PUT, "/api/v1/bank-accounts/" + id, body("0012", otherBankLeafId, null));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        var after = call(admin, HttpMethod.GET, "/api/v1/bank-accounts/" + id, null);
        assertThat(coaId(after)).isNotEqualTo(otherBankLeafId.toString());
    }

    @Test
    void updateToAnotherTenantsPropertyIsRefused() {
        String id = createPlain();
        var res = call(admin, HttpMethod.PUT, "/api/v1/bank-accounts/" + id, body("0012", null, otherPropertyId));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        var after = call(admin, HttpMethod.GET, "/api/v1/bank-accounts/" + id, null);
        assertThat(propId(after)).isNull();
    }

    @Test
    void updateToANonBankAccountIs400() {
        String id = createPlain();
        var res = call(admin, HttpMethod.PUT, "/api/v1/bank-accounts/" + id, body("0012", rentReceivableLeafId, null));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    /**
     * A row linked under the old ASSET-wide picker to a non-BANK leaf. Saved straight
     * through the repository, as the pre-#66 service allowed, because the endpoint now
     * refuses to create one.
     */
    private String createLegacyLinked() {
        TenantContextHolder.setTenantId(admin.getTenantId());
        try {
            var b = new com.datagami.rentaxis.domain.entity.BankAccount();
            b.setBankName("Emirates NBD");
            b.setAccountNumber("100000013");
            b.setCurrency("AED");
            b.setCoaAccount(accountService.getAccountById(rentReceivableLeafId));
            return bankAccountRepo.save(b).getId().toString();
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void editingOnlyTheIbanOfALegacyLinkedRowKeepsItsLink() {
        String id = createLegacyLinked();
        Map<String, Object> b = body("0013", rentReceivableLeafId, null);
        b.put("iban", "AE070331234567890123456");
        var res = call(admin, HttpMethod.PUT, "/api/v1/bank-accounts/" + id, b);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("iban")).isEqualTo("AE070331234567890123456");
        assertThat(coaId(res)).isEqualTo(rentReceivableLeafId.toString());
    }

    @Test
    void changingALegacyLinkedRowToAnotherNonBankLeafIs400() {
        String id = createLegacyLinked();
        var res = call(admin, HttpMethod.PUT, "/api/v1/bank-accounts/" + id, body("0013", bankGroupId, null));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        var after = call(admin, HttpMethod.GET, "/api/v1/bank-accounts/" + id, null);
        assertThat(coaId(after)).isEqualTo(rentReceivableLeafId.toString());
    }

    @Test
    void changingALegacyLinkedRowToABankLeafSucceeds() {
        String id = createLegacyLinked();
        var res = call(admin, HttpMethod.PUT, "/api/v1/bank-accounts/" + id, body("0013", bankLeafId, null));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(coaId(res)).isEqualTo(bankLeafId.toString());
    }

    @Test
    void anotherTenantCannotUpdateThisTenantsBankAccount() {
        String id = createPlain();
        var res = call(otherAdmin, HttpMethod.PUT, "/api/v1/bank-accounts/" + id, body("0012", otherBankLeafId, null));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
    }
}
