package com.datagami.rentaxis.testsupport;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;

import java.util.UUID;

/**
 * Real rows behind the X-User-* headers a test sends. Since break round 1 (F1/F2)
 * {@code ApiSecurityFilter} judges the named user against their stored row — an
 * unknown user id is 401, the stored role is what is granted, and the requested
 * organisation must be theirs and ACTIVE — so a header identity invented with
 * {@code UUID.randomUUID()} no longer authenticates.
 */
public final class TestIdentities {

    private TestIdentities() {
    }

    /** A new ACTIVE organisation. */
    public static UUID org(LandlordOrgRepository orgs) {
        LandlordOrg org = new LandlordOrg();
        org.setName("TestIdentities-" + UUID.randomUUID());
        return orgs.save(org).getId();
    }

    /** An ACTIVE user with this role whose home organisation is {@code tenantId} (null for SUPER_ADMIN). */
    public static UUID user(UserRepository users, UserRole role, UUID tenantId) {
        User u = new User();
        u.setEmail("tid-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        if (role == UserRole.SECURITY_GUARD) {
            u.setPhoneNumber("+9715" + String.format("%08d",
                    Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 100_000_000L)));
        }
        return users.save(u).getId();
    }
}
