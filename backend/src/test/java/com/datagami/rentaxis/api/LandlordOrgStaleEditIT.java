package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Break-it round 3 (ops3) F6, the exact repro: two SUPER_ADMIN tabs on the
 * organisations page. Tab A opens Edit (status Active). Tab B deactivates the
 * organisation. Tab A changes only the address and saves. The organisation used to
 * be ACTIVE again — its users could sign straight back in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LandlordOrgStaleEditIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired PropertyService propertyService;
    @Autowired JdbcTemplate jdbc;

    private CrossTenantHttp http;
    private User superAdmin;
    private UUID org;

    @BeforeEach
    void setUp() {
        http = new CrossTenantHttp(port, orgRepo, userRepo, accountService, propertyAccountService, propertyService);
        UUID home = http.tenant("SA-home-");
        superAdmin = http.user(home, UserRole.SUPER_ADMIN);
        org = http.tenant("BRK3-OPS Org ");
        jdbc.update("update landlord_org set address = 'Old address' where id = ?", org);
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private String status() {
        return jdbc.queryForObject("select status from landlord_org where id = ?", String.class, org);
    }

    private String address() {
        return jdbc.queryForObject("select address from landlord_org where id = ?", String.class, org);
    }

    private String name() {
        return jdbc.queryForObject("select name from landlord_org where id = ?", String.class, org);
    }

    @Test
    void aStaleEditDialogFromAnOlderClientNoLongerReactivates() {
        // Tab A's dialog, as the old web client sent it: every field, status included.
        Map<String, Object> tabA = new HashMap<>();
        tabA.put("name", name());
        tabA.put("address", "Old address");
        tabA.put("trn", "");
        tabA.put("status", "ACTIVE");
        tabA.put("logoUrl", "");
        tabA.put("ticketOtpRequired", true);
        tabA.put("phone", "");

        // Tab B deactivates.
        var off = http.call(superAdmin, HttpMethod.PUT, "/api/admin/tenants/" + org + "/status",
                Map.of("status", "INACTIVE", "expectedStatus", "ACTIVE"));
        assertThat(off.getStatusCode().value()).isEqualTo(200);
        assertThat(status()).isEqualTo("INACTIVE");

        // Tab A changes only the address.
        tabA.put("address", "New address");
        var save = http.call(superAdmin, HttpMethod.PUT, "/api/admin/tenants/" + org, tabA);

        assertThat(save.getStatusCode().value()).isEqualTo(200);
        assertThat(address()).isEqualTo("New address");
        assertThat(status()).isEqualTo("INACTIVE");
    }

    @Test
    void theEditDialogSendsOnlyWhatChangedAndIsRefusedWhenThatFieldMovedMeanwhile() {
        // Tab B changes the address first.
        assertThat(http.call(superAdmin, HttpMethod.PUT, "/api/admin/tenants/" + org,
                Map.of("address", "Tab B address", "expected", Map.of("address", "Old address")))
                .getStatusCode().value()).isEqualTo(200);

        // Tab A, opened before, sends its own address change with what it loaded.
        var stale = http.call(superAdmin, HttpMethod.PUT, "/api/admin/tenants/" + org,
                Map.of("address", "Tab A address", "expected", Map.of("address", "Old address")));

        assertThat(stale.getStatusCode().value()).isEqualTo(409);
        assertThat(stale.getBody().get("code")).isEqualTo("org.changed");
        assertThat(address()).isEqualTo("Tab B address");
    }

    @Test
    void statusChangesOnlyThroughTheStatusAction() {
        var stale = http.call(superAdmin, HttpMethod.PUT, "/api/admin/tenants/" + org + "/status",
                Map.of("status", "INACTIVE", "expectedStatus", "INACTIVE"));
        assertThat(stale.getStatusCode().value()).isEqualTo(409);
        assertThat(stale.getBody().get("code")).isEqualTo("org.changed");
        assertThat(status()).isEqualTo("ACTIVE");

        assertThat(http.call(superAdmin, HttpMethod.PUT, "/api/admin/tenants/" + org + "/status",
                Map.of("status", "SUSPENDED")).getStatusCode().value()).isEqualTo(400);

        assertThat(http.call(superAdmin, HttpMethod.PUT, "/api/admin/tenants/" + org,
                Map.of("status", "INACTIVE")).getStatusCode().value()).isEqualTo(200);
        assertThat(status()).isEqualTo("ACTIVE");

        assertThat(http.call(superAdmin, HttpMethod.PUT, "/api/admin/tenants/" + org + "/status",
                Map.of("status", "INACTIVE")).getStatusCode().value()).isEqualTo(200);
        assertThat(status()).isEqualTo("INACTIVE");
        assertThat(http.call(superAdmin, HttpMethod.PUT, "/api/admin/tenants/" + org + "/status",
                Map.of("status", "ACTIVE", "expectedStatus", "INACTIVE")).getStatusCode().value()).isEqualTo(200);
        assertThat(status()).isEqualTo("ACTIVE");
    }

    @Test
    void aTenantAdminCannotChangeStatus() {
        User admin = http.admin(org);
        assertThat(http.call(admin, HttpMethod.PUT, "/api/admin/tenants/" + org + "/status",
                Map.of("status", "INACTIVE")).getStatusCode().value()).isEqualTo(403);
        assertThat(status()).isEqualTo("ACTIVE");
    }
}
