package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.CrossTenantHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.CrossTenantHttp.nestedId;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #340 review I1: a {@code payableAccount} in a vendor body is ignored. A POST
 * carrying one used to 500 at flush; a PUT carrying one inserted a client-shaped,
 * parentless account and moved the vendor's payable onto it. The vendor's leaf is
 * the server's, created with the vendor and kept for life.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VendorControllerIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired PropertyService propertyService;
    @Autowired JdbcTemplate jdbc;

    private CrossTenantHttp http;
    private UUID tenantA;
    private User admin;
    private UUID ownLiabilityLeafId;
    private UUID otherTenantAccountId;

    @BeforeEach
    void setUp() {
        http = new CrossTenantHttp(port, orgRepo, userRepo, accountService, propertyAccountService, propertyService);
        UUID tenantB = http.tenant("Vendor-other-");
        otherTenantAccountId = accountService.getAccountsByType(AccountType.LIABILITY).stream()
                .filter(a -> tenantB.equals(a.getTenantId()) && !a.isGroup())
                .findFirst().orElseThrow().getId();
        tenantA = http.tenant("Vendor-");
        ownLiabilityLeafId = accountService.getAccountsByType(AccountType.LIABILITY).stream()
                .filter(a -> tenantA.equals(a.getTenantId()) && !a.isGroup())
                .findFirst().orElseThrow().getId();
        admin = http.admin(tenantA);
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private long accountsOfA() {
        return jdbc.queryForObject("select count(*) from accounts where tenant_id = ?", Long.class, tenantA);
    }

    private static Map<String, Object> body(String name, UUID payableAccountId) {
        Map<String, Object> b = new HashMap<>();
        b.put("nameEn", name);
        b.put("email", "ops@acme.ae");
        b.put("active", true);
        if (payableAccountId != null) {
            b.put("payableAccount", Map.of("id", payableAccountId.toString(),
                    "code", "ZZ-CLIENT", "name", "Client-made", "accountType", "LIABILITY", "group", false));
        }
        return b;
    }

    private String parentCodeOf(String accountId) {
        return jdbc.queryForObject(
                "select p.code from accounts a join accounts p on p.id = a.parent_id where a.id = ?::uuid",
                String.class, accountId);
    }

    @Test
    void createWithoutAPayableAccountGetsItsOwnLeaf() {
        var res = http.call(admin, HttpMethod.POST, "/api/v1/vendors", body("Acme Pest Control", null));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(parentCodeOf(nestedId(res, "payableAccount"))).isEqualTo("B-01-04");
    }

    @Test
    void createWithAnotherTenantsPayableAccountIgnoresItAndDoesNot500() {
        var res = http.call(admin, HttpMethod.POST, "/api/v1/vendors", body("Acme", otherTenantAccountId));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        String leaf = nestedId(res, "payableAccount");
        assertThat(leaf).isNotEqualTo(otherTenantAccountId.toString());
        assertThat(parentCodeOf(leaf)).isEqualTo("B-01-04");
    }

    @Test
    void updateCarryingAPayableAccountKeepsTheVendorsLeafAndCreatesNoAccount() {
        var created = http.call(admin, HttpMethod.POST, "/api/v1/vendors", body("Acme", null));
        String id = (String) created.getBody().get("id");
        String leaf = nestedId(created, "payableAccount");
        long accountsBefore = accountsOfA();

        for (UUID sent : new UUID[] {ownLiabilityLeafId, otherTenantAccountId}) {
            var res = http.call(admin, HttpMethod.PUT, "/api/v1/vendors/" + id, body("Acme Renamed", sent));
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(nestedId(res, "payableAccount")).isEqualTo(leaf);
        }
        assertThat(accountsOfA()).isEqualTo(accountsBefore);
        assertThat(jdbc.queryForObject("select payable_account_id::text from vendors where id = ?::uuid", String.class, id))
                .isEqualTo(leaf);
        assertThat(jdbc.queryForObject("select name from accounts where id = ?::uuid", String.class, leaf))
                .isEqualTo("Acme Renamed");
    }

    /**
     * Finance-ops audit S1 (P0): {@code VendorController} used to be
     * {@code @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")} at the
     * class level, so an ACCOUNTANT's {@code GET /v1/vendors} 403'd and the
     * Purchase/Service Invoice and Payment Voucher forms rendered an empty,
     * unexplained vendor dropdown. The class now admits ACCOUNTANT.
     */
    @Test
    void anAccountantCanListVendors() {
        User accountant = http.user(tenantA, UserRole.ACCOUNTANT);
        assertThat(http.status(accountant, HttpMethod.GET, "/api/v1/vendors")).isEqualTo(200);
    }

    @Test
    void aRenterIsStillRefused() {
        User renter = http.user(tenantA, UserRole.RENTER);
        assertThat(http.status(renter, HttpMethod.GET, "/api/v1/vendors")).isEqualTo(403);
    }

    @Test
    void aTenantUserIsStillRefused() {
        User tenantUser = http.user(tenantA, UserRole.TENANT_USER);
        assertThat(http.status(tenantUser, HttpMethod.GET, "/api/v1/vendors")).isEqualTo(403);
    }

    /**
     * Unchanged by this fix: PROPERTY_MANAGER was never granted vendor access
     * and stays that way (finance-ops audit; web/src/lib/rbac.ts
     * canAccessFinanceOps stays SA/TA-only for Bank Accounts/Staff, and the new
     * canManageVendors key deliberately does not include PROPERTY_MANAGER).
     */
    @Test
    void aPropertyManagerIsStillRefused() {
        User pm = http.user(tenantA, UserRole.PROPERTY_MANAGER);
        assertThat(http.status(pm, HttpMethod.GET, "/api/v1/vendors")).isEqualTo(403);
    }
}
