package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.RenewalTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PendingFollowUpsControllerTest extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired LeaseInteractionRepository interactionRepo;

    private UUID tenantId;
    private UUID managerUserId;
    private Lease lease;

    // Fixed cutoff date used throughout — avoids flakiness from LocalDate.now()
    private static final LocalDate CUTOFF = LocalDate.of(2026, 5, 13);

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("TestOrg-" + UUID.randomUUID());
        org = orgRepo.save(org);
        tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);

        User manager = new User();
        manager.setEmail("pm+" + UUID.randomUUID() + "@test");
        manager.setName("Property Manager");
        manager.setRole(UserRole.PROPERTY_MANAGER);
        manager.setStatus(UserStatus.ACTIVE);
        manager.setPasswordHash("ph");
        manager.setTenantId(tenantId);
        manager = userRepo.save(manager);
        managerUserId = manager.getId();

        lease = RenewalTestFixtures.createActiveLease(
                orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo,
                tenantId,
                LocalDate.of(2025, 6, 1),
                LocalDate.of(2026, 5, 31));

        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private RestClient pmClient() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id", managerUserId.toString())
                .defaultHeader("X-User-Role", "PROPERTY_MANAGER")
                .defaultHeader("X-Tenant-Id", tenantId.toString())
                .defaultHeader("X-User-Tenant-Id", tenantId.toString())
                .build();
    }

    private RestClient pmClientForTenant(UUID userId, UUID tid) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id", userId.toString())
                .defaultHeader("X-User-Role", "PROPERTY_MANAGER")
                .defaultHeader("X-Tenant-Id", tid.toString())
                .defaultHeader("X-User-Tenant-Id", tid.toString())
                .build();
    }

    private LeaseInteraction saveInteraction(UUID tid, Lease l, LocalDate followUpDate,
                                             InteractionOutcome outcome) {
        TenantContextHolder.setTenantId(tid);
        LeaseInteraction i = new LeaseInteraction();
        i.setTenantId(tid);
        i.setLease(l);
        i.setType(InteractionType.CALL);
        i.setDirection(InteractionDirection.OUTBOUND);
        i.setOccurredAt(Instant.now().minusSeconds(3600));
        i.setSummary("Test interaction");
        i.setFollowUpDate(followUpDate);
        i.setOutcome(outcome);
        i.setCreatedBy(managerUserId);
        LeaseInteraction saved = interactionRepo.save(i);
        TenantContextHolder.clear();
        return saved;
    }

    private List<Map<String, Object>> listFollowUps(RestClient client, LocalDate date) {
        return client.get()
                .uri("/api/v1/renewals/follow-ups?date=" + date)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    // -----------------------------------------------------------------------
    // Scenario A: past follow_up_date + null outcome — SHOULD appear
    // -----------------------------------------------------------------------

    @Test
    void interactionA_withPastFollowUpAndNullOutcome_isReturned() {
        LeaseInteraction a = saveInteraction(tenantId, lease, CUTOFF.minusDays(1), null);

        List<Map<String, Object>> results = listFollowUps(pmClient(), CUTOFF);

        assertThat(results).isNotNull();
        assertThat(results.stream().map(m -> m.get("id")))
                .contains(a.getId().toString());
    }

    // -----------------------------------------------------------------------
    // Scenario B: future follow_up_date — should NOT appear
    // -----------------------------------------------------------------------

    @Test
    void interactionB_withFutureFollowUp_isExcluded() {
        LeaseInteraction b = saveInteraction(tenantId, lease, CUTOFF.plusDays(1), null);

        List<Map<String, Object>> results = listFollowUps(pmClient(), CUTOFF);

        assertThat(results).isNotNull();
        assertThat(results.stream().map(m -> m.get("id")))
                .doesNotContain(b.getId().toString());
    }

    // -----------------------------------------------------------------------
    // Scenario C: past follow_up_date + POSITIVE outcome — should NOT appear
    // -----------------------------------------------------------------------

    @Test
    void interactionC_withPositiveOutcome_isExcluded() {
        LeaseInteraction c = saveInteraction(tenantId, lease, CUTOFF.minusDays(1), InteractionOutcome.POSITIVE);

        List<Map<String, Object>> results = listFollowUps(pmClient(), CUTOFF);

        assertThat(results).isNotNull();
        assertThat(results.stream().map(m -> m.get("id")))
                .doesNotContain(c.getId().toString());
    }

    // -----------------------------------------------------------------------
    // Cross-tenant isolation: second tenant's interaction must not leak
    // -----------------------------------------------------------------------

    @Test
    void crossTenant_otherTenantsInteraction_doesNotAppearInFirstTenantResponse() {
        // Set up second tenant
        LandlordOrg org2 = new LandlordOrg();
        org2.setName("Org2-" + UUID.randomUUID());
        org2 = orgRepo.save(org2);
        UUID tenantId2 = org2.getId();
        TenantContextHolder.setTenantId(tenantId2);

        User manager2 = new User();
        manager2.setEmail("pm2+" + UUID.randomUUID() + "@test");
        manager2.setName("Manager 2");
        manager2.setRole(UserRole.PROPERTY_MANAGER);
        manager2.setStatus(UserStatus.ACTIVE);
        manager2.setPasswordHash("ph");
        manager2.setTenantId(tenantId2);
        manager2 = userRepo.save(manager2);
        UUID manager2Id = manager2.getId();

        Lease lease2 = RenewalTestFixtures.createActiveLease(
                orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo,
                tenantId2,
                LocalDate.of(2025, 6, 1),
                LocalDate.of(2026, 5, 31));
        TenantContextHolder.clear();

        // Create an interaction belonging to tenant2 — past follow_up, null outcome (would appear if leaked)
        LeaseInteraction otherTenantInteraction = new LeaseInteraction();
        otherTenantInteraction.setTenantId(tenantId2);
        otherTenantInteraction.setLease(lease2);
        otherTenantInteraction.setType(InteractionType.CALL);
        otherTenantInteraction.setDirection(InteractionDirection.OUTBOUND);
        otherTenantInteraction.setOccurredAt(Instant.now().minusSeconds(3600));
        otherTenantInteraction.setSummary("Other tenant interaction");
        otherTenantInteraction.setFollowUpDate(CUTOFF);
        otherTenantInteraction.setOutcome(null);
        otherTenantInteraction.setCreatedBy(manager2Id);
        TenantContextHolder.setTenantId(tenantId2);
        otherTenantInteraction = interactionRepo.save(otherTenantInteraction);
        TenantContextHolder.clear();

        // Query as tenant1 — should not see tenant2's interaction
        List<Map<String, Object>> results = listFollowUps(pmClient(), CUTOFF);

        assertThat(results).isNotNull();
        String otherId = otherTenantInteraction.getId().toString();
        assertThat(results.stream().map(m -> m.get("id")))
                .doesNotContain(otherId);
    }
}
