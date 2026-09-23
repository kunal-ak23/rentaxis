package com.datagami.rentaxis.api;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who hosts a renter's meeting: the default host, and who may be named as one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeetingHostEligibilityIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private UUID tenantId;
    private User renter;

    @BeforeEach
    void setUp() {
        tenantId = org("MHE");
        renter = user(tenantId, UserRole.RENTER, UserStatus.ACTIVE, 0);
    }

    // ---------------------------------------------------------- default host

    @Test
    void theDefaultHostIsTheOldestActiveAdmin() {
        user(tenantId, UserRole.TENANT_ADMIN, UserStatus.INACTIVE, 300);   // oldest, but deactivated
        User oldestActive = user(tenantId, UserRole.TENANT_ADMIN, UserStatus.ACTIVE, 200);
        user(tenantId, UserRole.TENANT_ADMIN, UserStatus.ACTIVE, 100);
        user(tenantId, UserRole.PROPERTY_MANAGER, UserStatus.ACTIVE, 400);

        for (int i = 0; i < 3; i++) {
            assertThat(defaultHost(renter)).isEqualTo(oldestActive.getId().toString());
        }
    }

    @Test
    void withNoActiveAdminTheDefaultHostIsTheOldestActiveManager() {
        user(tenantId, UserRole.TENANT_ADMIN, UserStatus.INACTIVE, 500);
        user(tenantId, UserRole.PROPERTY_MANAGER, UserStatus.INACTIVE, 400);
        User oldestActivePm = user(tenantId, UserRole.PROPERTY_MANAGER, UserStatus.ACTIVE, 300);
        user(tenantId, UserRole.PROPERTY_MANAGER, UserStatus.ACTIVE, 100);

        assertThat(defaultHost(renter)).isEqualTo(oldestActivePm.getId().toString());
    }

    // ---------------------------------------------------- who may be a host

    /** A multi-tenant admin works here through a membership row; their home is elsewhere. */
    @Test
    void aMembershipAdminIsABookableHost() {
        UUID home = org("MHE-HOME");
        User memberAdmin = user(home, UserRole.TENANT_ADMIN, UserStatus.ACTIVE, 0);
        User homeOnlyAdmin = user(home, UserRole.TENANT_ADMIN, UserStatus.ACTIVE, 0);
        jdbc.update("INSERT INTO user_tenant_memberships (user_id, tenant_id) VALUES (?, ?)",
                memberAdmin.getId(), tenantId);

        assertThat(slots(renter, memberAdmin).getStatusCode().value()).isEqualTo(200);
        assertThat(slots(renter, homeOnlyAdmin).getStatusCode().value()).isEqualTo(404);

        jdbc.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", memberAdmin.getId());
        assertThat(slots(renter, memberAdmin).getStatusCode().value()).isEqualTo(404);
    }

    /** A super admin running an org with no staff hosts in it, as themselves only. */
    @Test
    void aSuperAdminHostsOnlyAsThemselves() {
        User superAdmin = user(null, UserRole.SUPER_ADMIN, UserStatus.ACTIVE, 0);
        User otherSuperAdmin = user(null, UserRole.SUPER_ADMIN, UserStatus.ACTIVE, 0);

        assertThat(slots(superAdmin, superAdmin).getStatusCode().value()).isEqualTo(200);
        assertThat(slots(superAdmin, otherSuperAdmin).getStatusCode().value()).isEqualTo(404);
        assertThat(slots(renter, superAdmin).getStatusCode().value()).isEqualTo(404);
    }

    private ResponseEntity<String> slots(User caller, User host) {
        return call(caller, tenantId, HttpMethod.GET, "/api/v1/meetings/slots?hostUserId=" + host.getId()
                + "&date=" + java.time.LocalDate.now().plusDays(1));
    }

    // ------------------------------------------------------------- plumbing

    private String defaultHost(User caller) {
        ResponseEntity<String> res = call(caller, tenantId, HttpMethod.GET, "/api/v1/meetings/default-host");
        assertThat(res.getStatusCode().is2xxSuccessful()).as(res.getBody()).isTrue();
        try {
            return json.readTree(res.getBody()).get("userId").asText();
        } catch (Exception e) {
            throw new AssertionError(res.getBody(), e);
        }
    }

    UUID org(String prefix) {
        LandlordOrg org = new LandlordOrg();
        org.setName(prefix + "-" + UUID.randomUUID());
        return orgRepo.save(org).getId();
    }

    /** A user whose account was created {@code minutesAgo} minutes ago. */
    User user(UUID tenant, UserRole role, UserStatus status, int minutesAgo) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(status);
        u.setPasswordHash("x");
        u.setTenantId(tenant);
        User saved = userRepo.save(u);
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(minutesAgo, ChronoUnit.MINUTES)), saved.getId());
        return saved;
    }

    ResponseEntity<String> call(User caller, UUID activeTenant, HttpMethod method, String path) {
        RestClient.RequestBodySpec spec = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .method(method).uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", activeTenant.toString());
        if (caller.getTenantId() != null) {
            spec = spec.header("X-User-Tenant-Id", caller.getTenantId().toString());
        }
        return spec.retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }
}
