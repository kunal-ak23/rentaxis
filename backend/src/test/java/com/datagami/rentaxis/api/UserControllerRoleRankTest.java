package com.datagami.rentaxis.api;

import com.datagami.rentaxis.domain.entity.enums.UserRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The role-assignment rank table, locked down where ACCOUNTANT was inserted.
 *
 * <p>An accountant posts journal entries, so only a TENANT_ADMIN (or a
 * SUPER_ADMIN, which short-circuits before the rank check) may create one.
 * {@code canAssignRole} is {@code rank(target) >= rank(caller)}, so giving
 * ACCOUNTANT the same rank as PROPERTY_MANAGER would have permitted exactly
 * what it is meant to forbid — a property manager may already assign another
 * property manager, and equal ranks make the two cases identical. ACCOUNTANT
 * therefore ranks strictly above PROPERTY_MANAGER.
 *
 * <p>The whole of {@link UserController} is {@code @PreAuthorize}d to
 * SUPER_ADMIN/TENANT_ADMIN, so a PROPERTY_MANAGER cannot reach these endpoints
 * today at all. That is precisely why the table is worth pinning here: it is
 * the layer that survives someone widening that annotation later.
 */
class UserControllerRoleRankTest {

    @Test
    void propertyManagerCannotAssignAccountant() {
        assertThat(UserController.canAssignRole(UserRole.PROPERTY_MANAGER, UserRole.ACCOUNTANT)).isFalse();
    }

    @Test
    void tenantAdminAndSuperAdminCanAssignAccountant() {
        assertThat(UserController.canAssignRole(UserRole.TENANT_ADMIN, UserRole.ACCOUNTANT)).isTrue();
        assertThat(UserController.canAssignRole(UserRole.SUPER_ADMIN, UserRole.ACCOUNTANT)).isTrue();
    }

    @Test
    void accountantRanksAboveEveryRoleItMayNotCreateItselfFrom() {
        assertThat(UserController.privilegeRank(UserRole.ACCOUNTANT))
                .isGreaterThan(UserController.privilegeRank(UserRole.TENANT_ADMIN))
                .isLessThan(UserController.privilegeRank(UserRole.PROPERTY_MANAGER));
    }

    /** Inserting ACCOUNTANT must not have reshuffled anyone else's relative standing. */
    @Test
    void theRestOfTheHierarchyIsUnchanged() {
        assertThat(UserController.privilegeRank(UserRole.SUPER_ADMIN))
                .isLessThan(UserController.privilegeRank(UserRole.TENANT_ADMIN));
        assertThat(UserController.privilegeRank(UserRole.PROPERTY_MANAGER))
                .isLessThan(UserController.privilegeRank(UserRole.TENANT_USER));
        assertThat(UserController.privilegeRank(UserRole.TENANT_USER))
                .isLessThan(UserController.privilegeRank(UserRole.RENTER));
        assertThat(UserController.privilegeRank(UserRole.RENTER))
                .isLessThan(UserController.privilegeRank(UserRole.SECURITY_GUARD));

        assertThat(UserController.canAssignRole(UserRole.PROPERTY_MANAGER, UserRole.TENANT_USER)).isTrue();
        assertThat(UserController.canAssignRole(UserRole.PROPERTY_MANAGER, UserRole.PROPERTY_MANAGER)).isTrue();
        assertThat(UserController.canAssignRole(UserRole.TENANT_USER, UserRole.TENANT_ADMIN)).isFalse();
    }
}
