package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.LandlordOrgService;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers {@link LandlordOrgService#deleteTenant} end-to-end. The critical
 * invariants:
 *
 *   - All tenant-scoped rows across the schema are cleared.
 *   - The landlord_org row itself is removed at the end (which would FK-fail
 *     if any FK-referencing rows survived — implicitly tests org_settings
 *     and friends).
 *   - confirmName mismatch refuses the delete.
 *   - Cross-tenant users (those with memberships in OTHER tenants) are NOT
 *     hard-deleted; their users.tenant_id is NULL'd so they keep other access.
 */
@SpringBootTest
@Testcontainers
class LandlordOrgDeleteTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired LandlordOrgService service;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired JdbcTemplate jdbc;

    private LandlordOrg seedTenantWithOnboardingArtifacts(String label) {
        // provisionTenant goes through the real service so any default-row
        // hooks fire (org_settings, etc).
        LandlordOrg org = service.provisionTenant("DeleteTest-" + label + "-" + UUID.randomUUID());

        // Add a few rows directly into representative tenanted tables to
        // simulate live data. We deliberately don't hit higher-level services
        // for these — we just need rows that the cascade has to clear.
        jdbc.update(
                "INSERT INTO properties (id, tenant_id, name_en, address, type, emirate, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), org.getId(), "TEST-Prop", "test addr",
                "RESIDENTIAL", "DUBAI", OffsetDateTime.now(), OffsetDateTime.now());

        return org;
    }

    @Test
    void deleteTenant_cascadesEverything() {
        LandlordOrg org = seedTenantWithOnboardingArtifacts("happy");
        UUID tenantId = org.getId();

        // Sanity: at least one tenanted row exists for the cascade to clear.
        Integer propsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM properties WHERE tenant_id = ?",
                Integer.class, tenantId);
        assertThat(propsBefore).isGreaterThan(0);

        service.deleteTenant(tenantId, org.getName());

        // Org gone.
        assertThat(orgRepo.findById(tenantId)).isEmpty();

        // No surviving tenanted rows in any discovered table.
        var tenantedTables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.columns " +
                        "WHERE column_name = 'tenant_id' AND table_schema = 'public'",
                String.class);
        for (String t : tenantedTables) {
            Integer surviving = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM \"" + t + "\" WHERE tenant_id = ?",
                    Integer.class, tenantId);
            assertThat(surviving).as("rows surviving in %s", t).isZero();
        }
    }

    @Test
    void deleteTenant_rejectsConfirmNameMismatch() {
        LandlordOrg org = seedTenantWithOnboardingArtifacts("namecheck");
        UUID tenantId = org.getId();

        assertThatThrownBy(() -> service.deleteTenant(tenantId, "wrong-name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirmName");

        // Org must still exist.
        assertThat(orgRepo.findById(tenantId)).isPresent();
    }

    @Test
    void deleteTenant_preservesUsersWithCrossTenantMemberships() {
        // User belongs primarily to A (users.tenant_id = A) AND has a
        // membership row in B. Deleting A must NOT hard-delete this user.
        LandlordOrg a = service.provisionTenant("CrossA-" + UUID.randomUUID());
        LandlordOrg b = service.provisionTenant("CrossB-" + UUID.randomUUID());

        User crossUser = new User();
        crossUser.setEmail("cross-" + UUID.randomUUID() + "@test");
        crossUser.setName("CrossUser");
        crossUser.setRole(UserRole.TENANT_USER);
        crossUser.setStatus(UserStatus.ACTIVE);
        crossUser.setPasswordHash("x");
        crossUser.setTenantId(a.getId());
        crossUser = userRepo.save(crossUser);

        // Manual membership row pointing to B. The (user_id, tenant_id) pair
        // is the PK; created_at defaults to now().
        jdbc.update(
                "INSERT INTO user_tenant_memberships (user_id, tenant_id) VALUES (?, ?)",
                crossUser.getId(), b.getId());

        // Delete tenant A.
        service.deleteTenant(a.getId(), a.getName());

        // User still exists; tenant_id detached.
        User survived = userRepo.findById(crossUser.getId()).orElseThrow();
        assertThat(survived.getTenantId()).isNull();

        // Their membership to B remains.
        Integer memberships = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_tenant_memberships WHERE user_id = ? AND tenant_id = ?",
                Integer.class, crossUser.getId(), b.getId());
        assertThat(memberships).isEqualTo(1);
    }
}
