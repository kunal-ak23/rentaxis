package com.datagami.rentaxis.core.security;

import com.datagami.rentaxis.domain.entity.enums.UserRole;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What {@link ApiSecurityFilter}'s legacy X-User-* path checks a header
 * assertion against (break round 1, F1/F2).
 *
 * <p>The headers name a user; they no longer say who that user is. A demoted,
 * deleted or deactivated user, or one moved out of an organisation, used to keep
 * whatever the web session or the device had last asserted — the web session
 * revalidates every 5 minutes and froze its membership list at login. The
 * filter now takes the role, the home tenant and the memberships from the
 * database on every request, through the same short cache (and the same
 * evictions) that {@link TokenRevocationService} keeps for bearer tokens.
 * Production answer: {@link TokenRevocationService}; an interface so the
 * filter's unit tests can run without a database.
 */
public interface LegacyHeaderIdentityCheck {

    /** The user's current state, or empty when there is no such user. */
    Optional<CurrentUser> currentUser(UUID userId);

    /** Whether the organisation exists and is ACTIVE. */
    boolean orgActive(UUID tenantId);

    /**
     * @param role            the stored role (null only for a value this build does not know)
     * @param active          status is ACTIVE
     * @param homeTenantId    {@code users.tenant_id} (null for a SUPER_ADMIN)
     * @param memberTenantIds every {@code user_tenant_memberships} tenant
     */
    record CurrentUser(UserRole role, boolean active, UUID homeTenantId, Set<UUID> memberTenantIds) {

        /** The user may act in this tenant: it is their home tenant or one of their memberships. */
        public boolean belongsTo(UUID tenantId) {
            return tenantId != null && (tenantId.equals(homeTenantId) || memberTenantIds.contains(tenantId));
        }
    }
}
