package com.datagami.rentaxis.api;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the role-hierarchy guard added to {@link UserController}: a
 * TENANT_ADMIN must not be able to provision a SUPER_ADMIN (privilege
 * escalation), and every user a TENANT_ADMIN creates is forced into the
 * caller's own tenant regardless of the tenantId they submit.
 *
 * <p>Auth context normally injected by the Next.js proxy is simulated here via
 * the X-User-* / X-Tenant-* headers that {@code ApiSecurityFilter} reads.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class UserControllerRoleAuthorizationTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort int port;
    @Autowired UserRepository userRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired PasswordEncoder passwordEncoder;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private LandlordOrg makeOrg(String label) {
        LandlordOrg org = new LandlordOrg();
        org.setName("RoleAuth-" + label + "-" + UUID.randomUUID());
        return orgRepo.save(org);
    }

    private User makeAdmin(LandlordOrg org, UserRole role) {
        User u = new User();
        u.setEmail("admin-" + UUID.randomUUID() + "@test");
        u.setName("admin");
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash(passwordEncoder.encode("pwd"));
        u.setTenantId(role == UserRole.SUPER_ADMIN ? null : org.getId());
        return userRepo.save(u);
    }

    /** Builds a create-user request as the given caller, returning the raw response Map. */
    private Map<?, ?> createAs(User caller, Map<String, Object> body) {
        RestClient.RequestBodySpec req = client().post().uri("/api/admin/users")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name());
        if (caller.getTenantId() != null) {
            req = req.header("X-Tenant-Id", caller.getTenantId().toString())
                    .header("X-User-Tenant-Id", caller.getTenantId().toString());
        }
        return req.body(body).retrieve().body(Map.class);
    }

    private Map<String, Object> userBody(String role, String tenantId) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", "new-" + UUID.randomUUID() + "@test");
        body.put("password", "password123");
        body.put("name", "New User");
        body.put("role", role);
        body.put("tenantId", tenantId);
        return body;
    }

    @Test
    void tenantAdminCannotCreateSuperAdmin() {
        LandlordOrg org = makeOrg("a");
        User tenantAdmin = makeAdmin(org, UserRole.TENANT_ADMIN);

        try {
            createAs(tenantAdmin, userBody("SUPER_ADMIN", null));
            throw new AssertionError("expected 403");
        } catch (HttpStatusCodeException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void tenantAdminCanCreatePeerAndLowerRolesInOwnTenant() {
        LandlordOrg org = makeOrg("b");
        User tenantAdmin = makeAdmin(org, UserRole.TENANT_ADMIN);

        for (String role : new String[] { "TENANT_ADMIN", "PROPERTY_MANAGER", "TENANT_USER" }) {
            Map<?, ?> resp = createAs(tenantAdmin, userBody(role, org.getId().toString()));
            assertThat(resp.get("role")).isEqualTo(role);
            assertThat(resp.get("tenantId")).isEqualTo(org.getId().toString());
        }
    }

    @Test
    void tenantAdminCreationIsForcedIntoOwnTenantEvenIfAnotherTenantIsRequested() {
        LandlordOrg own = makeOrg("own");
        LandlordOrg other = makeOrg("other");
        User tenantAdmin = makeAdmin(own, UserRole.TENANT_ADMIN);

        // Caller submits a foreign tenantId; the server must ignore it.
        Map<?, ?> resp = createAs(tenantAdmin, userBody("TENANT_USER", other.getId().toString()));
        assertThat(resp.get("tenantId")).isEqualTo(own.getId().toString());
    }

    @Test
    void superAdminCanCreateSuperAdmin() {
        LandlordOrg org = makeOrg("super");
        User superAdmin = makeAdmin(org, UserRole.SUPER_ADMIN);

        Map<?, ?> resp = createAs(superAdmin, userBody("SUPER_ADMIN", null));
        assertThat(resp.get("role")).isEqualTo("SUPER_ADMIN");
        assertThat(resp.get("tenantId")).isNull();
    }
}
