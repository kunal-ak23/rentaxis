package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.security.TokenRevocationService;
import com.datagami.rentaxis.core.service.UserService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Break round 1, F1/F2: the legacy X-User-* path (what the web proxy sends) used
 * to believe {@code X-User-Role} and {@code X-User-Tenant-Id}. The web session
 * revalidates only every 5 minutes and froze its membership list at login, so a
 * demoted admin could re-promote themselves, a deleted user kept creating
 * renters, and a user moved to another organisation kept admin in the old one.
 * The filter now judges the named user against their stored row. Every test
 * here makes the change through the code path production uses and checks the
 * very next request, with no wait for the 30 s cache.
 */
class LegacyHeaderIdentityIT extends AbstractCallerIdentityIT {

    @Autowired UserService userService;
    @Autowired TokenRevocationService tokenRevocation;

    private User stored(UUID tenantId, UserRole role) {
        TenantContextHolder.setTenantId(tenantId);
        try {
            User u = user(tenantId, role, "x");
            if (tenantId != null && role != UserRole.RENTER) {
                userService.addTenantMembership(u.getId(), tenantId);
            }
            return u;
        } finally {
            TenantContextHolder.clear();
        }
    }

    /** What web/src/proxy.ts sends: the asserted role and the active tenant in both tenant headers. */
    private RestClient.RequestHeadersSpec<?> legacy(String uri, UUID userId, String role, UUID tenant) {
        RestClient.RequestHeadersSpec<?> spec = client().get().uri(uri)
                .header("X-User-Id", userId.toString())
                .header("X-User-Role", role);
        if (tenant != null) {
            spec = spec.header("X-Tenant-Id", tenant.toString())
                    .header("X-User-Tenant-Id", tenant.toString());
        }
        return spec;
    }

    private void update(User u, UserRole role, UUID tenantId) {
        userService.updateUser(u.getId(), u.getEmail(), null, u.getName(), role,
                tenantId == null ? null : tenantId.toString(), null);
    }

    @Test
    void aNormalValidCallIsUnchanged() {
        UUID org = newTenant("LHI-OK");
        User admin = stored(org, UserRole.TENANT_ADMIN);
        User renter = stored(org, UserRole.RENTER);

        assertThat(status(legacy("/api/admin/users", admin.getId(), "TENANT_ADMIN", org))).isEqualTo(200);
        assertThat(status(legacy("/api/v1/properties", admin.getId(), "TENANT_ADMIN", org))).isEqualTo(200);
        assertThat(status(legacy("/api/auth/me", admin.getId(), "TENANT_ADMIN", org))).isEqualTo(200);
        assertThat(status(legacy("/api/auth/me", renter.getId(), "RENTER", org))).isEqualTo(200);
    }

    @Test
    void aDemotedAdminLosesAdminOnTheNextRequest() {
        UUID org = newTenant("LHI-DEMOTE");
        User admin = stored(org, UserRole.TENANT_ADMIN);
        assertThat(status(legacy("/api/admin/users", admin.getId(), "TENANT_ADMIN", org))).isEqualTo(200);

        update(admin, UserRole.PROPERTY_MANAGER, org);

        // The web session still says TENANT_ADMIN; the stored role is what counts,
        // straight away (the first 200 cached the old row: the update evicts it).
        assertThat(status(legacy("/api/admin/users", admin.getId(), "TENANT_ADMIN", org)))
                .as("break round 1 F1: the demoted admin re-promoting themselves").isEqualTo(403);
        assertThat(status(legacy("/api/v1/properties", admin.getId(), "TENANT_ADMIN", org)))
                .as("still signed in, as what they now are").isEqualTo(200);
    }

    @Test
    void aForgedHigherRoleHeaderIsIgnored() {
        UUID org = newTenant("LHI-FORGE");
        User pm = stored(org, UserRole.PROPERTY_MANAGER);

        assertThat(status(legacy("/api/admin/users", pm.getId(), "TENANT_ADMIN", org))).isEqualTo(403);
        // A forged SUPER_ADMIN (gate off in tests: no proxy secret) is not
        // SUPER_ADMIN either, so a foreign organisation stays out of reach.
        UUID foreign = newTenant("LHI-FORGE-OTHER");
        assertThat(status(legacy("/api/v1/properties", pm.getId(), "SUPER_ADMIN", foreign))).isEqualTo(403);
        assertThat(status(legacy("/api/v1/properties", pm.getId(), "PROPERTY_MANAGER", org))).isEqualTo(200);
    }

    @Test
    void aDeletedUserIs401OnTheNextRequest() {
        UUID org = newTenant("LHI-DELETE");
        User admin = stored(org, UserRole.TENANT_ADMIN);
        assertThat(status(legacy("/api/v1/properties", admin.getId(), "TENANT_ADMIN", org))).isEqualTo(200);

        userService.deleteUser(admin.getId());

        assertThat(status(legacy("/api/v1/properties", admin.getId(), "TENANT_ADMIN", org))).isEqualTo(401);
        assertThat(status(legacy("/api/auth/me", admin.getId(), "TENANT_ADMIN", org))).isEqualTo(401);
    }

    @Test
    void anInactiveOrUnknownUserIs401() {
        UUID org = newTenant("LHI-INACTIVE");
        User admin = stored(org, UserRole.TENANT_ADMIN);
        assertThat(status(legacy("/api/v1/properties", admin.getId(), "TENANT_ADMIN", org))).isEqualTo(200);

        // No API deactivates a user; an operator writes the status directly.
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", UserStatus.INACTIVE.name(), admin.getId());
        tokenRevocation.evictUserAfterCommit(admin.getId());

        assertThat(status(legacy("/api/v1/properties", admin.getId(), "TENANT_ADMIN", org))).isEqualTo(401);
        assertThat(status(legacy("/api/v1/properties", UUID.randomUUID(), "TENANT_ADMIN", org))).isEqualTo(401);
    }

    @Test
    void aUserMovedToAnotherOrganisationLosesTheOldOneAndGetsTheNewOne() {
        UUID a = newTenant("LHI-MOVE-A");
        UUID b = newTenant("LHI-MOVE-B");
        User admin = stored(a, UserRole.TENANT_ADMIN);
        assertThat(status(legacy("/api/admin/users", admin.getId(), "TENANT_ADMIN", a))).isEqualTo(200);

        update(admin, UserRole.TENANT_ADMIN, b);

        assertThat(status(legacy("/api/admin/users", admin.getId(), "TENANT_ADMIN", a)))
                .as("break round 1 F2: the stale session (or a replayed cookie) naming the old org")
                .isEqualTo(403);
        assertThat(status(legacy("/api/admin/users", admin.getId(), "TENANT_ADMIN", b))).isEqualTo(200);
    }

    @Test
    void aRemovedMembershipIsRefusedAndAnAddedOneWorksOnTheNextRequest() {
        UUID home = newTenant("LHI-MEM-HOME");
        UUID second = newTenant("LHI-MEM-2");
        User admin = stored(home, UserRole.TENANT_ADMIN);

        assertThat(status(legacy("/api/v1/properties", admin.getId(), "TENANT_ADMIN", second))).isEqualTo(403);
        userService.addTenantMembership(admin.getId(), second);
        assertThat(status(legacy("/api/v1/properties", admin.getId(), "TENANT_ADMIN", second))).isEqualTo(200);

        userService.removeTenantMembership(admin.getId(), second);
        assertThat(status(legacy("/api/v1/properties", admin.getId(), "TENANT_ADMIN", second))).isEqualTo(403);
        assertThat(status(legacy("/api/v1/properties", admin.getId(), "TENANT_ADMIN", home))).isEqualTo(200);
    }

    @Test
    void aSuperAdminReachesAnyOrganisation() {
        User sa = stored(null, UserRole.SUPER_ADMIN);
        UUID any = newTenant("LHI-SA");
        UUID other = newTenant("LHI-SA-2");

        assertThat(status(legacy("/api/admin/users", sa.getId(), "SUPER_ADMIN", any))).isEqualTo(200);
        assertThat(status(legacy("/api/v1/properties", sa.getId(), "SUPER_ADMIN", other))).isEqualTo(200);
        assertThat(status(legacy("/api/admin/users", sa.getId(), "SUPER_ADMIN", null))).isEqualTo(200);
    }

    @Test
    void anInactiveOrganisationAdmitsOnlySuperAdmin() {
        UUID org = newTenant("LHI-ORG-OFF");
        User admin = stored(org, UserRole.TENANT_ADMIN);
        User sa = stored(null, UserRole.SUPER_ADMIN);
        assertThat(status(legacy("/api/v1/properties", admin.getId(), "TENANT_ADMIN", org))).isEqualTo(200);

        jdbc.update("UPDATE landlord_org SET status = 'INACTIVE' WHERE id = ?", org);
        tokenRevocation.evictOrg(org);

        assertThat(status(legacy("/api/v1/properties", admin.getId(), "TENANT_ADMIN", org))).isEqualTo(401);
        assertThat(status(legacy("/api/v1/properties", sa.getId(), "SUPER_ADMIN", org))).isEqualTo(200);
    }

    /**
     * Batch 2 review: the web session revalidates through /api/auth/me with its
     * stale home tenant as X-User-Tenant-Id. After a move that must still answer —
     * with the new tenant — or the session never learns about the move.
     */
    @Test
    void aMovedUsersProfileAnswersWithTheNewTenantEvenFromTheOldOne() {
        UUID a = newTenant("LHI-ME-A");
        UUID b = newTenant("LHI-ME-B");
        User admin = stored(a, UserRole.TENANT_ADMIN);
        update(admin, UserRole.TENANT_ADMIN, b);

        @SuppressWarnings("rawtypes")
        java.util.Map body = legacy("/api/auth/me", admin.getId(), "TENANT_ADMIN", a)
                .retrieve().body(java.util.Map.class);
        assertThat(body.get("tenantId")).isEqualTo(b.toString());
        // Everything else under the old tenant stays refused.
        assertThat(status(legacy("/api/v1/properties", admin.getId(), "TENANT_ADMIN", a))).isEqualTo(403);
    }

    @Test
    void aProfileReadStillRefusesAnInactiveUserAndAnInactiveOrgDoesNotBlockIt() {
        UUID org = newTenant("LHI-ME-OFF");
        User admin = stored(org, UserRole.TENANT_ADMIN);
        User other = stored(org, UserRole.TENANT_ADMIN);
        jdbc.update("UPDATE landlord_org SET status = 'INACTIVE' WHERE id = ?", org);
        tokenRevocation.evictOrg(org);
        assertThat(status(legacy("/api/auth/me", other.getId(), "TENANT_ADMIN", org))).isEqualTo(200);

        jdbc.update("UPDATE users SET status = ? WHERE id = ?", UserStatus.INACTIVE.name(), admin.getId());
        tokenRevocation.evictUserAfterCommit(admin.getId());
        assertThat(status(legacy("/api/auth/me", admin.getId(), "TENANT_ADMIN", org))).isEqualTo(401);
    }
}
