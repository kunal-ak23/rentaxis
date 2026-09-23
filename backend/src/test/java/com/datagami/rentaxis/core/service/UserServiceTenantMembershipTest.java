package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserTenantMembershipRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code user_tenant_memberships} auto-creation in {@link UserService}, over the
 * role list that decides it.
 *
 * <p>Exists because that list has now been missed twice for the same role.
 * SECURITY_GUARD was added to {@link UserRole} and neither {@code createUser} nor
 * {@code updateUser} was updated, so guards were provisioned with no membership
 * row and an empty {@code AuthResponse.tenantIds}. The parameterized cases below
 * are deliberately role-driven rather than one hand-written guard test: they
 * assert the <i>rule</i> ("every tenant-scoped role gets a row"), so the next role
 * added to the enum is covered without anyone remembering to come back here.
 */
@SpringBootTest
class UserServiceTenantMembershipTest extends AbstractPostgresIT {

    @Autowired UserService userService;
    @Autowired UserTenantMembershipRepository membershipRepository;
    @Autowired LandlordOrgRepository landlordOrgRepo;

    private LandlordOrg makeOrg() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Membership-" + UUID.randomUUID());
        return landlordOrgRepo.save(org);
    }

    private User create(LandlordOrg org, UserRole role) {
        return userService.createUser(
                role.name().toLowerCase() + "+" + UUID.randomUUID() + "@test",
                "TempPass@123",
                "User " + role,
                role,
                org.getId().toString(),
                null,
                "admin");
    }

    /**
     * The regression. A guard created through the API must come out of
     * {@code createUser} already able to name its tenant — provisioning is the
     * only place this can happen, since {@code PUT /gatepass/guards/{id}/properties}
     * deliberately does not create the row as a side effect.
     */
    @Test
    void securityGuardGetsATenantMembership() {
        LandlordOrg org = makeOrg();

        User guard = create(org, UserRole.SECURITY_GUARD);

        assertThat(membershipRepository.existsByUserIdAndTenantId(guard.getId(), org.getId()))
                .as("a SECURITY_GUARD must get a membership row like every other tenant-scoped role")
                .isTrue();
        assertThat(userService.getUserTenantIds(guard.getId()))
                .as("AuthResponse.tenantIds is built from this and must not come back empty")
                .containsExactly(org.getId());
    }

    /**
     * The rule, not just the one role that broke it. Every role except SUPER_ADMIN
     * is tenant-scoped and gets a row; asserting it over the enum means a new role
     * fails here if {@code getsTenantMembership} answers wrongly for it.
     */
    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = "SUPER_ADMIN", mode = EnumSource.Mode.EXCLUDE)
    void everyTenantScopedRoleGetsAMembership(UserRole role) {
        LandlordOrg org = makeOrg();

        User user = create(org, role);

        assertThat(membershipRepository.existsByUserIdAndTenantId(user.getId(), org.getId())).isTrue();
    }

    /**
     * The counterweight: SUPER_ADMIN is cross-tenant and enumerates orgs directly,
     * so a membership row would be meaningless. Without this, "add every role to
     * the list" would pass the test above and be wrong.
     */
    @Test
    void superAdminGetsNoTenantMembership() {
        User superAdmin = userService.createUser(
                "super+" + UUID.randomUUID() + "@test",
                "TempPass@123",
                "Super Admin",
                UserRole.SUPER_ADMIN,
                null,
                null,
                "system");

        assertThat(userService.getUserTenantIds(superAdmin.getId())).isEmpty();
    }

    /**
     * The second, previously-unreported instance of the same omission: a user
     * moved into a tenant by {@code updateUser} needs the row just as much as one
     * created with it. Reachable through {@code PUT /api/v1/users/{id}} whenever a
     * staff member is re-roled to SECURITY_GUARD.
     */
    @Test
    void updatingAUserIntoTheGuardRoleAlsoCreatesTheMembership() {
        LandlordOrg org = makeOrg();
        User user = create(org, UserRole.TENANT_USER);

        userService.updateUser(user.getId(), user.getEmail(), null, "Now A Guard",
                UserRole.SECURITY_GUARD, org.getId().toString(), "+971500000000");

        assertThat(membershipRepository.existsByUserIdAndTenantId(user.getId(), org.getId())).isTrue();
    }
}
