package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.security.TokenRevocationService;
import com.datagami.rentaxis.core.service.LandlordOrgService;
import com.datagami.rentaxis.core.service.UserService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security audit 2026-09-24, P1-2: a bearer token used to be good for 30 days
 * whatever happened to the account behind it — a demoted or deleted admin kept
 * admin, and a stolen token survived a password change. Every test here signs
 * in for real (so the token carries the row's real {@code tv}), proves the token
 * works, makes the change through the code path production uses, and proves the
 * same token is now refused with 401.
 */
class BearerTokenRevocationIT extends AbstractCallerIdentityIT {

    private static final String PASSWORD = "revocation-it-password-1";

    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserService userService;
    @Autowired LandlordOrgService orgService;
    @Autowired TokenRevocationService tokenRevocation;

    private UUID tenantId;
    private User admin;

    @BeforeEach
    void setUp() {
        tenantId = newTenant("REVOKE");
        TenantContextHolder.setTenantId(tenantId);
        admin = user(tenantId, UserRole.TENANT_ADMIN, passwordEncoder.encode(PASSWORD));
        TenantContextHolder.clear();
        userService.addTenantMembership(admin.getId(), tenantId);
    }

    private String login(User u, String password) {
        @SuppressWarnings("rawtypes")
        Map body = client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", u.getEmail(), "password", password))
                .retrieve().body(Map.class);
        String token = (String) body.get("token");
        assertThat(token).as("login must issue a token when the secret is set").isNotBlank();
        return token;
    }

    private int get(String uri, String token) {
        return status(client().get().uri(uri).header("Authorization", "Bearer " + token));
    }

    @Test
    void anUntouchedTokenKeepsWorking() {
        String token = login(admin, PASSWORD);
        assertThat(get("/api/auth/me", token)).isEqualTo(200);
        assertThat(get("/api/admin/users", token)).isEqualTo(200);
        // Twice: the second answer comes from the cached row state.
        assertThat(get("/api/admin/users", token)).isEqualTo(200);
    }

    @Test
    void aPasswordChangeRevokesTheOldTokenAndHandsTheCallerANewOne() {
        String stolen = login(admin, PASSWORD);
        String mine = login(admin, PASSWORD);
        assertThat(get("/api/auth/me", stolen)).isEqualTo(200);

        @SuppressWarnings("rawtypes")
        Map body = client().put().uri("/api/auth/me/password")
                .header("Authorization", "Bearer " + mine)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("currentPassword", PASSWORD, "newPassword", "a-new-password-2"))
                .retrieve().body(Map.class);
        String replacement = (String) body.get("token");

        assertThat(get("/api/auth/me", stolen)).as("a stolen token dies with the old password").isEqualTo(401);
        assertThat(get("/api/auth/me", mine)).as("so does the changer's own old token").isEqualTo(401);
        assertThat(replacement).isNotBlank();
        assertThat(get("/api/auth/me", replacement)).as("the replacement keeps the changer signed in")
                .isEqualTo(200);
    }

    @Test
    void anAdminSetPasswordRevokesTheToken() {
        String token = login(admin, PASSWORD);
        userService.updateUser(admin.getId(), admin.getEmail(), "admin-set-password-3", admin.getName(),
                UserRole.TENANT_ADMIN, tenantId.toString(), null);
        assertThat(get("/api/auth/me", token)).isEqualTo(401);
    }

    @Test
    void aDemotionRevokesTheAdminToken() {
        String token = login(admin, PASSWORD);
        assertThat(get("/api/admin/users", token)).isEqualTo(200);

        userService.updateUser(admin.getId(), admin.getEmail(), null, admin.getName(),
                UserRole.TENANT_USER, tenantId.toString(), null);

        assertThat(get("/api/admin/users", token))
                .as("the audit's exploit: a demoted admin listing (and next, creating) admins")
                .isEqualTo(401);
        assertThat(get("/api/auth/me", token)).isEqualTo(401);
    }

    @Test
    void anEditThatChangesNothingSecurityRelevantDoesNotRevoke() {
        String token = login(admin, PASSWORD);
        userService.updateUser(admin.getId(), admin.getEmail(), null, "Renamed Admin",
                UserRole.TENANT_ADMIN, tenantId.toString(), null);
        assertThat(get("/api/auth/me", token)).isEqualTo(200);
    }

    @Test
    void aDeactivatedUsersTokenIsRefused() {
        String token = login(admin, PASSWORD);
        assertThat(get("/api/auth/me", token)).isEqualTo(200);

        // No API deactivates a user today; the status is written directly, as an
        // operator would. Such a write reaches the filter when the 30 s cached
        // row state expires; evict to observe it now.
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", UserStatus.INACTIVE.name(), admin.getId());
        tokenRevocation.evictUserAfterCommit(admin.getId());

        assertThat(get("/api/auth/me", token)).isEqualTo(401);
    }

    @Test
    void aDeletedUsersTokenIsRefused() {
        TenantContextHolder.setTenantId(tenantId);
        User doomed = user(tenantId, UserRole.TENANT_ADMIN, passwordEncoder.encode(PASSWORD));
        TenantContextHolder.clear();
        String token = login(doomed, PASSWORD);
        assertThat(get("/api/auth/me", token)).isEqualTo(200);

        userService.deleteUser(doomed.getId());

        assertThat(get("/api/auth/me", token)).isEqualTo(401);
    }

    @Test
    void aDeactivatedOrganisationsTokensAreRefused() {
        String token = login(admin, PASSWORD);
        assertThat(get("/api/admin/users", token)).isEqualTo(200);

        LandlordOrg org = orgService.findById(tenantId).orElseThrow();
        org.setStatus("INACTIVE");
        orgService.save(org);

        assertThat(get("/api/admin/users", token)).isEqualTo(401);
        assertThat(get("/api/auth/me", token)).isEqualTo(401);

        org.setStatus("ACTIVE");
        orgService.save(org);
        assertThat(get("/api/auth/me", token)).as("reactivating restores the unrevoked token").isEqualTo(200);
    }

    @Test
    void removingAMembershipRevokesTheTokenThatListsIt() {
        UUID second = newTenant("REVOKE-2");
        userService.addTenantMembership(admin.getId(), second);
        String token = login(admin, PASSWORD);
        assertThat(status(client().get().uri("/api/admin/users")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", second.toString()))).isEqualTo(200);

        userService.removeTenantMembership(admin.getId(), second);

        assertThat(status(client().get().uri("/api/admin/users")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", second.toString())))
                .as("the token's tids claim still lists the removed tenant")
                .isEqualTo(401);
    }

    @Test
    void aTokenMintedWithAStaleVersionIsRefused() {
        String current = login(admin, PASSWORD);
        tokenRevocation.revokeAllTokens(admin.getId());
        assertThat(get("/api/auth/me", current)).isEqualTo(401);
        assertThat(get("/api/auth/me", login(admin, PASSWORD))).as("a fresh sign-in gets the new version")
                .isEqualTo(200);
    }
}
