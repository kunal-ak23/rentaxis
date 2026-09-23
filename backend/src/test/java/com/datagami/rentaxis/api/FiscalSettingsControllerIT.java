package com.datagami.rentaxis.api;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HTTP-level cover for {@link FiscalSettingsController}: a fresh tenant reads
 * its defaults, an accountant moves the fiscal year and books start date, the
 * period lock only ever moves forward, and a property manager is refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FiscalSettingsControllerIT extends AbstractPostgresIT {

    private static final String PATH = "/api/v1/finance/fiscal-settings";

    @LocalServerPort int port;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;

    UUID tenantId;
    User accountant;
    User manager;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("FS-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        accountant = user(UserRole.ACCOUNTANT);
        manager = user(UserRole.PROPERTY_MANAGER);
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

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private Map<?, ?> get(User caller) {
        return client().get().uri(PATH)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Tenant-Id", tenantId.toString())
                .retrieve().body(Map.class);
    }

    private Map<?, ?> put(User caller, Map<String, Object> body) {
        return client().put().uri(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Tenant-Id", tenantId.toString())
                .body(body).retrieve().body(Map.class);
    }

    private Map<?, ?> lock(User caller, String through) {
        return client().post().uri(PATH + "/lock")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Tenant-Id", tenantId.toString())
                .body(Collections.singletonMap("through", through)).retrieve().body(Map.class);
    }

    @Test
    void accountantReadsDefaultsUpdatesThemAndMovesThePeriodLockForward() {
        Map<?, ?> fresh = get(accountant);
        assertThat(fresh.get("fiscalYearStartMonth")).isEqualTo(1);
        assertThat(fresh.get("booksStartDate")).isNull();
        assertThat(fresh.get("booksLockedThrough")).isNull();

        Map<String, Object> body = new HashMap<>();
        body.put("fiscalYearStartMonth", 6);
        body.put("booksStartDate", "2026-10-01");
        Map<?, ?> updated = put(accountant, body);
        assertThat(updated.get("fiscalYearStartMonth")).isEqualTo(6);
        assertThat(updated.get("booksStartDate")).isEqualTo("2026-10-01");
        // Setting the books start date closes everything before it.
        assertThat(updated.get("booksLockedThrough")).isEqualTo("2026-09-30");

        // The lock never moves backwards.
        assertThatThrownBy(() -> lock(accountant, "2026-08-31"))
                .isInstanceOf(HttpClientErrorException.BadRequest.class)
                .hasMessageContaining("backwards");

        assertThat(lock(accountant, "2026-10-31").get("booksLockedThrough")).isEqualTo("2026-10-31");
        assertThat(get(accountant).get("booksLockedThrough")).isEqualTo("2026-10-31");
    }

    @Test
    void propertyManagerIsForbidden() {
        assertThatThrownBy(() -> get(manager)).isInstanceOf(HttpClientErrorException.Forbidden.class);
        assertThatThrownBy(() -> put(manager, Collections.singletonMap("fiscalYearStartMonth", 4)))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
        assertThatThrownBy(() -> lock(manager, "2026-12-31"))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void lockWithoutADateIs400() {
        assertThatThrownBy(() -> lock(accountant, null))
                .isInstanceOf(HttpClientErrorException.BadRequest.class)
                .hasMessageContaining("required");
    }
}
