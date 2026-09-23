package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserTenantMembership;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.UserTenantMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The self-service profile routes under /api/auth/me act as the signed-in user,
 * and since PR #342 take that user from the verified principal.
 *
 * <p>Before, the whole of /api/auth/** was on ApiSecurityFilter's skip list and
 * permitAll, so these routes ran with no principal at all and read X-User-Id
 * straight off the request: anyone who knew a user id could read that user's
 * profile, rename them or change their phone number, with or without a token.
 * The pre-login routes (login, register, set-password, firebase, apple) are
 * unchanged and still skip the filter.
 */
class AuthProfileCallerIdentityIT extends AbstractCallerIdentityIT {

    private static final String A_PASSWORD = "a-password-123";
    private static final String VICTIM_PASSWORD = "victim-password-123";

    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserTenantMembershipRepository membershipRepo;

    private UUID tenantId;
    private User userA;
    private User victim;

    @BeforeEach
    void setUp() {
        tenantId = newTenant("ACI");
        TenantContextHolder.setTenantId(tenantId);
        userA = user(tenantId, UserRole.RENTER, passwordEncoder.encode(A_PASSWORD));
        victim = user(tenantId, UserRole.RENTER, passwordEncoder.encode(VICTIM_PASSWORD));
        TenantContextHolder.clear();
    }

    private String nameOf(User u) {
        return jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, u.getId());
    }

    private String hashOf(User u) {
        return jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, u.getId());
    }

    @Test
    void theProfileReadWithAForgedUserIdIsTheCallers() {
        @SuppressWarnings("rawtypes")
        Map me = forged(HttpMethod.GET, "/api/auth/me", userA, victim).retrieve().body(Map.class);
        assertThat(me.get("id")).isEqualTo(userA.getId().toString());
        assertThat(me.get("email")).isEqualTo(userA.getEmail());
    }

    @Test
    void aProfileUpdateWithAForgedUserIdChangesOnlyTheCaller() {
        String victimsName = nameOf(victim);

        assertThat(status(forged(HttpMethod.PUT, "/api/auth/me", userA, victim)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Hijacked")))).isEqualTo(200);

        assertThat(nameOf(victim)).isEqualTo(victimsName);
        assertThat(nameOf(userA)).isEqualTo("Hijacked");
    }

    @Test
    void aPasswordChangeWithAForgedUserIdDoesNotTouchTheVictim() {
        String victimsHash = hashOf(victim);

        // The victim's own current password, sent as the victim: it is checked
        // against the caller's hash, so it is refused.
        assertThat(status(forged(HttpMethod.PUT, "/api/auth/me/password", userA, victim)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("currentPassword", VICTIM_PASSWORD, "newPassword", "taken-over-123"))))
                .isEqualTo(400);

        assertThat(hashOf(victim)).isEqualTo(victimsHash);
    }

    @Test
    void theTenantListWithAForgedUserIdIsTheCallers() {
        // The victim belongs to a second organisation the caller does not.
        UUID otherTenant = newTenant("ACI-other");
        UserTenantMembership m = new UserTenantMembership();
        m.setUserId(victim.getId());
        m.setTenantId(otherTenant);
        membershipRepo.save(m);

        @SuppressWarnings("rawtypes")
        List tenants = forged(HttpMethod.GET, "/api/auth/me/tenants", userA, victim).retrieve().body(List.class);
        assertThat(tenants).noneMatch(t -> otherTenant.toString().equals(((Map<?, ?>) t).get("id")));
    }

    @Test
    void aBareUserIdHeaderNoLongerReadsAProfile() {
        // No token, no role: before the fix this answered with the victim's profile.
        assertThat(status(client().get().uri("/api/auth/me")
                .header("X-User-Id", victim.getId().toString())))
                .isIn(401, 403);
        assertThat(status(client().put().uri("/api/auth/me")
                .header("X-User-Id", victim.getId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Hijacked"))))
                .isIn(401, 403);
        assertThat(nameOf(victim)).isNotEqualTo("Hijacked");
    }

    @Test
    void theLegacyHeaderCallersStillWork() {
        // Web proxy and installed mobile builds: the full legacy header set, no token.
        @SuppressWarnings("rawtypes")
        Map me = client().get().uri("/api/auth/me")
                .header("X-User-Id", userA.getId().toString())
                .header("X-User-Role", "RENTER")
                .header("X-User-Tenant-Id", tenantId.toString())
                .retrieve().body(Map.class);
        assertThat(me.get("id")).isEqualTo(userA.getId().toString());
    }

    @Test
    void aMultiTenantAdminOnASecondaryTenantStillSeesEveryMembership() {
        // Routing /me through the filter now sets a tenant context on it; the
        // membership list must not be narrowed to the active tenant.
        UUID second = newTenant("ACI-second");
        TenantContextHolder.setTenantId(tenantId);
        User admin = user(tenantId, UserRole.TENANT_ADMIN, "x");
        TenantContextHolder.clear();
        for (UUID t : List.of(tenantId, second)) {
            UserTenantMembership m = new UserTenantMembership();
            m.setUserId(admin.getId());
            m.setTenantId(t);
            membershipRepo.save(m);
        }
        String token = tokens.issue(admin.getId(), UserRole.TENANT_ADMIN, tenantId, List.of(tenantId, second));

        @SuppressWarnings("rawtypes")
        List tenants = client().get().uri("/api/auth/me/tenants")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", second.toString())
                .retrieve().body(List.class);
        assertThat(tenants).extracting(t -> ((Map<?, ?>) t).get("id"))
                .containsExactlyInAnyOrder(tenantId.toString(), second.toString());

        @SuppressWarnings("rawtypes")
        Map me = client().get().uri("/api/auth/me")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", second.toString())
                .retrieve().body(Map.class);
        assertThat(me.get("id")).isEqualTo(admin.getId().toString());
    }
}
