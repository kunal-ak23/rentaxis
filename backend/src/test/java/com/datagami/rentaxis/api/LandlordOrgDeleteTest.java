package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.LandlordOrgService;
import com.datagami.rentaxis.core.service.ContractGenerationService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
    @MockitoBean ContractGenerationService contractGenerationService;

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

        verify(contractGenerationService).cleanupTenantDocuments(eq(tenantId), eq(java.util.List.of()));

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

        verify(contractGenerationService, never())
                .cleanupTenantDocuments(eq(tenantId), anyList());

        // Org must still exist.
        assertThat(orgRepo.findById(tenantId)).isPresent();
    }

    @Test
    void deleteTenant_reparentsUsersWithCrossTenantMembershipsToOtherTenant() {
        // User primarily in A, also member of A (typical) AND B. Deleting A
        // must reparent them to B, not hard-delete and not NULL their tenant_id.
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

        // Real-world setup: membership rows in BOTH tenants.
        jdbc.update("INSERT INTO user_tenant_memberships (user_id, tenant_id) VALUES (?, ?)",
                crossUser.getId(), a.getId());
        jdbc.update("INSERT INTO user_tenant_memberships (user_id, tenant_id) VALUES (?, ?)",
                crossUser.getId(), b.getId());

        service.deleteTenant(a.getId(), a.getName());

        // User survives, reparented to B.
        User survived = userRepo.findById(crossUser.getId()).orElseThrow();
        assertThat(survived.getTenantId()).isEqualTo(b.getId());

        // Membership in A is gone (cascade); B remains.
        Integer membershipsA = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_tenant_memberships WHERE user_id = ? AND tenant_id = ?",
                Integer.class, crossUser.getId(), a.getId());
        assertThat(membershipsA).isZero();
        Integer membershipsB = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_tenant_memberships WHERE user_id = ? AND tenant_id = ?",
                Integer.class, crossUser.getId(), b.getId());
        assertThat(membershipsB).isEqualTo(1);
    }

    @Test
    void deleteTenant_hardDeletesUsersWithoutCrossTenantMemberships() {
        // Negative-case companion: a user with ONLY a membership in the
        // tenant being deleted should be hard-deleted by the cascade. This
        // guards against an incorrect "preserve everyone" regression.
        LandlordOrg a = service.provisionTenant("SoloA-" + UUID.randomUUID());

        User soloUser = new User();
        soloUser.setEmail("solo-" + UUID.randomUUID() + "@test");
        soloUser.setName("SoloUser");
        soloUser.setRole(UserRole.TENANT_USER);
        soloUser.setStatus(UserStatus.ACTIVE);
        soloUser.setPasswordHash("x");
        soloUser.setTenantId(a.getId());
        soloUser = userRepo.save(soloUser);

        jdbc.update("INSERT INTO user_tenant_memberships (user_id, tenant_id) VALUES (?, ?)",
                soloUser.getId(), a.getId());

        service.deleteTenant(a.getId(), a.getName());

        assertThat(userRepo.findById(soloUser.getId())).isEmpty();
    }

    @Test
    void deleteTenant_doesNotCollideOnEmailWhenTwoCrossTenantUsersShareEmail() {
        // Regression for the partial-unique collision on ux_users_email_super_admin
        // and (post-fix) on tenanted index when reparenting. Two distinct
        // users in tenants A and B share an email; both have memberships in
        // tenant C. Deleting A then deleting B must NOT collide.
        LandlordOrg a = service.provisionTenant("CollA-" + UUID.randomUUID());
        LandlordOrg b = service.provisionTenant("CollB-" + UUID.randomUUID());
        LandlordOrg c = service.provisionTenant("CollC-" + UUID.randomUUID());

        String sharedEmail = "shared-" + UUID.randomUUID() + "@test";

        User uA = new User();
        uA.setEmail(sharedEmail); uA.setName("U-A"); uA.setRole(UserRole.TENANT_USER);
        uA.setStatus(UserStatus.ACTIVE); uA.setPasswordHash("x"); uA.setTenantId(a.getId());
        uA = userRepo.save(uA);

        User uB = new User();
        uB.setEmail(sharedEmail); uB.setName("U-B"); uB.setRole(UserRole.TENANT_USER);
        uB.setStatus(UserStatus.ACTIVE); uB.setPasswordHash("x"); uB.setTenantId(b.getId());
        uB = userRepo.save(uB);

        jdbc.update("INSERT INTO user_tenant_memberships (user_id, tenant_id) VALUES (?, ?)", uA.getId(), c.getId());
        jdbc.update("INSERT INTO user_tenant_memberships (user_id, tenant_id) VALUES (?, ?)", uB.getId(), c.getId());

        UUID uAid = uA.getId();
        UUID uBid = uB.getId();

        // Delete A — uA's safe-reparent check passes (no one in C has the
        // shared email yet), so uA moves to C.
        service.deleteTenant(a.getId(), a.getName());
        assertThat(userRepo.findById(uAid)).isPresent();
        assertThat(userRepo.findById(uAid).get().getTenantId()).isEqualTo(c.getId());

        // Delete B — uB's safe-reparent check fails (uA now occupies C with
        // the same email). The collision-skip leaves uB with tenant_id=B,
        // which gets hard-deleted by the cascade. uB is GONE.
        service.deleteTenant(b.getId(), b.getName());
        assertThat(userRepo.findById(uBid))
                .as("collision-losing user must be hard-deleted, not violate the index")
                .isEmpty();

        // uA still safely in C.
        assertThat(userRepo.findById(uAid)).isPresent();
    }
}
