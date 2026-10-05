package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
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
import static com.datagami.rentaxis.testsupport.CrossTenantHttp.ref;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #340 review C3: {@code POST}/{@code PUT /api/v1/staff} bound the {@code Staff}
 * entity. {@code property: {id}} could name another tenant's property (and the
 * EAGER join then leaked it into {@code GET /staff}); {@code salaryAccount: {id}}
 * arrived with a null id and 500'd at flush, the #67 bug. Both ids are now
 * resolved in the caller's tenant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaffControllerIT extends AbstractPostgresIT {

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
    private User otherAdmin;
    private UUID propertyId;
    private UUID expenseLeafId;
    private UUID expenseGroupId;
    private UUID incomeLeafId;
    private UUID otherPropertyId;
    private UUID otherExpenseLeafId;

    @BeforeEach
    void setUp() {
        http = new CrossTenantHttp(port, orgRepo, userRepo, accountService, propertyAccountService, propertyService);

        UUID tenantB = http.tenant("Staff-other-");
        otherPropertyId = http.property("Other Tower").getId();
        otherExpenseLeafId = account(tenantB, AccountType.EXPENSE, false).getId();
        otherAdmin = http.admin(tenantB);

        tenantA = http.tenant("Staff-");
        propertyId = http.property("Marina Heights").getId();
        expenseLeafId = account(tenantA, AccountType.EXPENSE, false).getId();
        expenseGroupId = account(tenantA, AccountType.EXPENSE, true).getId();
        incomeLeafId = account(tenantA, AccountType.INCOME, false).getId();
        admin = http.admin(tenantA);
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    /** An active account of the given shape from the tenant's seeded chart. */
    private Account account(UUID tenant, AccountType type, boolean group) {
        return accountService.getAccountsByType(type).stream()
                .filter(a -> tenant.equals(a.getTenantId()) && a.isGroup() == group && a.isActive())
                .findFirst().orElseThrow();
    }

    private long staffOfA() {
        return jdbc.queryForObject("select count(*) from staff where tenant_id = ?", Long.class, tenantA);
    }

    private static Map<String, Object> body(UUID property, UUID salaryAccount) {
        Map<String, Object> b = new HashMap<>();
        b.put("nameEn", "Ravi Kumar");
        b.put("designation", "Watchman");
        b.put("monthlySalary", "2500");
        b.put("joinDate", "");
        b.put("active", true);
        if (property != null) b.put("property", ref(property));
        if (salaryAccount != null) b.put("salaryAccount", ref(salaryAccount));
        return b;
    }

    private String createPlain() {
        var res = http.call(admin, HttpMethod.POST, "/api/v1/staff", body(propertyId, null));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return (String) res.getBody().get("id");
    }

    // ---- create ----

    @Test
    void createWithOwnPropertyAndSalaryAccountLinksBoth() {
        var res = http.call(admin, HttpMethod.POST, "/api/v1/staff", body(propertyId, expenseLeafId));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(nestedId(res, "property")).isEqualTo(propertyId.toString());
        assertThat(nestedId(res, "salaryAccount")).isEqualTo(expenseLeafId.toString());
        assertThat(staffOfA()).isEqualTo(1);
    }

    @Test
    void createWithAnotherTenantsPropertyIs404AndSavesNothing() {
        var res = http.call(admin, HttpMethod.POST, "/api/v1/staff", body(otherPropertyId, null));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(staffOfA()).isZero();
    }

    @Test
    void createWithAnotherTenantsSalaryAccountIs404AndSavesNothing() {
        var res = http.call(admin, HttpMethod.POST, "/api/v1/staff", body(propertyId, otherExpenseLeafId));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(staffOfA()).isZero();
    }

    @Test
    void createWithANonExpenseOrGroupSalaryAccountIs400() {
        assertThat(http.call(admin, HttpMethod.POST, "/api/v1/staff", body(propertyId, incomeLeafId))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(http.call(admin, HttpMethod.POST, "/api/v1/staff", body(propertyId, expenseGroupId))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(staffOfA()).isZero();
    }

    // ---- update ----

    @Test
    void updateToOwnSalaryAccountLinksIt() {
        String id = createPlain();
        var res = http.call(admin, HttpMethod.PUT, "/api/v1/staff/" + id, body(propertyId, expenseLeafId));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(nestedId(res, "salaryAccount")).isEqualTo(expenseLeafId.toString());
        // The mobile sheet re-sends the current link on every edit: that must keep working.
        var again = http.call(admin, HttpMethod.PUT, "/api/v1/staff/" + id, body(propertyId, expenseLeafId));
        assertThat(again.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void updateToAnotherTenantsPropertyIs404AndLeavesTheRowAlone() {
        String id = createPlain();
        var res = http.call(admin, HttpMethod.PUT, "/api/v1/staff/" + id, body(otherPropertyId, null));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select property_id from staff where id = ?::uuid", UUID.class, id))
                .isEqualTo(propertyId);
    }

    @Test
    void updateToAnotherTenantsSalaryAccountIs404AndLeavesTheRowAlone() {
        String id = createPlain();
        var res = http.call(admin, HttpMethod.PUT, "/api/v1/staff/" + id, body(propertyId, otherExpenseLeafId));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select salary_account_id from staff where id = ?::uuid", UUID.class, id))
                .isNull();
    }

    @Test
    void anotherTenantCannotUpdateThisTenantsStaff() {
        String id = createPlain();
        var res = http.call(otherAdmin, HttpMethod.PUT, "/api/v1/staff/" + id, body(otherPropertyId, null));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select property_id from staff where id = ?::uuid", UUID.class, id))
                .isEqualTo(propertyId);
    }

    // ---- active flag (tutorial 22) ----

    @Test
    void anInactiveStaffMemberReadsInactiveAndAnEditWithoutTheFlagKeepsThemInactive() {
        Map<String, Object> b = body(propertyId, null);
        b.put("active", false);
        var created = http.call(admin, HttpMethod.POST, "/api/v1/staff", b);
        String id = (String) created.getBody().get("id");
        assertThat(created.getBody().get("active")).isEqualTo(false);
        assertThat(http.call(admin, HttpMethod.GET, "/api/v1/staff/" + id, null).getBody().get("active"))
                .isEqualTo(false);

        Map<String, Object> edit = body(propertyId, null);
        edit.remove("active");
        edit.put("designation", "Head Watchman");
        assertThat(http.call(admin, HttpMethod.PUT, "/api/v1/staff/" + id, edit).getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select is_active from staff where id = ?::uuid", Boolean.class, id)).isFalse();

        edit.put("active", true);
        http.call(admin, HttpMethod.PUT, "/api/v1/staff/" + id, edit);
        assertThat(jdbc.queryForObject("select is_active from staff where id = ?::uuid", Boolean.class, id)).isTrue();
    }

    // ---- give login (tutorial 22) ----

    @Test
    @SuppressWarnings("rawtypes")
    void giveLoginInvitesAStaffUserWhoThenAppearsUnderAssignToWithTheirDesignation() {
        String id = createPlain();
        String email = "ravi-" + UUID.randomUUID() + "@example.test";

        var res = http.call(admin, HttpMethod.POST, "/api/v1/staff/" + id + "/login", Map.of("email", email.toUpperCase()));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        String userId = (String) res.getBody().get("userId");
        assertThat(userId).isNotNull();
        Map<String, Object> user = jdbc.queryForMap(
                "select email, name, role, tenant_id, invite_token from users where id = ?::uuid", userId);
        assertThat(user.get("email")).isEqualTo(email);
        assertThat(user.get("name")).isEqualTo("Ravi Kumar");
        assertThat(user.get("role")).isEqualTo("TENANT_USER");
        assertThat(user.get("tenant_id")).isEqualTo(tenantA);
        assertThat(user.get("invite_token")).isNotNull();

        // A second login for the same record is refused.
        assertThat(http.call(admin, HttpMethod.POST, "/api/v1/staff/" + id + "/login",
                Map.of("email", "other-" + email)).getStatusCode().value()).isEqualTo(400);

        // The assignee picker offers them, with the staff designation.
        var ticket = http.call(admin, HttpMethod.POST, "/api/v1/tickets",
                Map.of("propertyId", propertyId.toString(), "title", "Leaking tap"));
        String ticketId = (String) ticket.getBody().get("id");
        java.util.List<Map> assignees = http.request(admin, HttpMethod.GET, "/api/v1/tickets/" + ticketId + "/assignees")
                .retrieve().body(java.util.List.class);
        assertThat(assignees).anySatisfy(a -> {
            assertThat(a.get("id")).isEqualTo(userId);
            assertThat(a.get("designation")).isEqualTo("Watchman");
        });
    }

    @Test
    void giveLoginLinksAnExistingStaffLoginWithThatEmailInsteadOfCreatingOne() {
        String id = createPlain();
        TenantContextHolder.setTenantId(tenantA);
        User existing = http.user(tenantA, UserRole.TENANT_USER);
        TenantContextHolder.clear();
        long usersBefore = jdbc.queryForObject("select count(*) from users where tenant_id = ?", Long.class, tenantA);

        var res = http.call(admin, HttpMethod.POST, "/api/v1/staff/" + id + "/login", Map.of("email", existing.getEmail()));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("userId")).isEqualTo(existing.getId().toString());
        assertThat(jdbc.queryForObject("select count(*) from users where tenant_id = ?", Long.class, tenantA))
                .isEqualTo(usersBefore);

        // ...and one login backs one staff record.
        String second = createPlain();
        assertThat(http.call(admin, HttpMethod.POST, "/api/v1/staff/" + second + "/login",
                Map.of("email", existing.getEmail())).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void giveLoginIsTenantScopedAndAdminOnly() {
        String id = createPlain();
        assertThat(http.call(otherAdmin, HttpMethod.POST, "/api/v1/staff/" + id + "/login",
                Map.of("email", "x-" + UUID.randomUUID() + "@example.test")).getStatusCode().value()).isEqualTo(404);
        TenantContextHolder.setTenantId(tenantA);
        User manager = http.user(tenantA, UserRole.PROPERTY_MANAGER);
        TenantContextHolder.clear();
        assertThat(http.call(manager, HttpMethod.POST, "/api/v1/staff/" + id + "/login",
                Map.of("email", "y-" + UUID.randomUUID() + "@example.test")).getStatusCode().value()).isEqualTo(403);
        assertThat(jdbc.queryForObject("select user_id from staff where id = ?::uuid", UUID.class, id)).isNull();
    }
}
