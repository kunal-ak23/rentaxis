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
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaseInteractionControllerTest extends AbstractPostgresIT {

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
    private UUID superAdminUserId;
    private Lease lease;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("TestOrg-" + UUID.randomUUID());
        org = orgRepo.save(org);
        tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);

        // Create a manager user
        User manager = new User();
        manager.setEmail("pm+" + UUID.randomUUID() + "@test");
        manager.setName("Property Manager");
        manager.setRole(UserRole.PROPERTY_MANAGER);
        manager.setStatus(UserStatus.ACTIVE);
        manager.setPasswordHash("ph");
        manager.setTenantId(tenantId);
        manager = userRepo.save(manager);
        managerUserId = manager.getId();

        // A real SUPER_ADMIN user (interaction.created_by has an FK to users).
        User superAdmin = new User();
        superAdmin.setEmail("sa+" + UUID.randomUUID() + "@test");
        superAdmin.setName("System Admin");
        superAdmin.setRole(UserRole.SUPER_ADMIN);
        superAdmin.setStatus(UserStatus.ACTIVE);
        superAdmin.setPasswordHash("ph");
        superAdmin.setTenantId(tenantId);
        superAdmin = userRepo.save(superAdmin);
        superAdminUserId = superAdmin.getId();

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

    private RestClient superAdminClient() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id", superAdminUserId.toString())
                .defaultHeader("X-User-Role", "SUPER_ADMIN")
                .defaultHeader("X-Tenant-Id", tenantId.toString())
                .defaultHeader("X-User-Tenant-Id", tenantId.toString())
                .build();
    }

    private RestClient renterClient() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id", UUID.randomUUID().toString())
                .defaultHeader("X-User-Role", "RENTER")
                .defaultHeader("X-Tenant-Id", tenantId.toString())
                .defaultHeader("X-User-Tenant-Id", tenantId.toString())
                .build();
    }

    private Map<String, Object> createPayload() {
        return Map.of(
                "type", "CALL",
                "direction", "OUTBOUND",
                "occurredAt", Instant.now().minusSeconds(60).toString(),
                "summary", "Called renter about renewal"
        );
    }

    @Test
    void pm_can_create() {
        Map<?, ?> body = pmClient().post()
                .uri("/api/v1/leases/" + lease.getId() + "/interactions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(createPayload())
                .retrieve()
                .body(Map.class);

        assertThat(body).isNotNull();
        assertThat(body.get("id")).isNotNull();
        assertThat(body.get("type")).isEqualTo("CALL");
        assertThat(body.get("leaseId")).isEqualTo(lease.getId().toString());
    }

    @Test
    void super_admin_can_create_and_list() {
        // Regression: interactions endpoints previously omitted SUPER_ADMIN from
        // @PreAuthorize, 403-ing the system admin.
        Map<?, ?> created = superAdminClient().post()
                .uri("/api/v1/leases/" + lease.getId() + "/interactions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(createPayload())
                .retrieve()
                .body(Map.class);
        assertThat(created).isNotNull();
        assertThat(created.get("id")).isNotNull();

        Map<?, ?> listed = superAdminClient().get()
                .uri("/api/v1/leases/" + lease.getId() + "/interactions")
                .retrieve()
                .body(Map.class);
        assertThat(listed).isNotNull();
        assertThat((List<?>) listed.get("content")).isNotEmpty();
    }

    @Test
    void renter_role_403_on_create() {
        HttpStatusCodeException ex = null;
        try {
            renterClient().post()
                    .uri("/api/v1/leases/" + lease.getId() + "/interactions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createPayload())
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            ex = e;
        }

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void system_intent_edit_returns_400() {
        // Pre-insert a SYSTEM_INTENT interaction directly via repo
        TenantContextHolder.setTenantId(tenantId);
        LeaseInteraction systemInteraction = new LeaseInteraction();
        systemInteraction.setTenantId(tenantId);
        systemInteraction.setLease(lease);
        systemInteraction.setType(InteractionType.SYSTEM_INTENT);
        systemInteraction.setDirection(InteractionDirection.INTERNAL);
        systemInteraction.setOccurredAt(Instant.now().minusSeconds(300));
        systemInteraction.setSummary("Renter submitted RENEW intent via portal");
        systemInteraction.setCreatedBy(managerUserId);
        systemInteraction = interactionRepo.save(systemInteraction);
        TenantContextHolder.clear();

        UUID interactionId = systemInteraction.getId();

        HttpStatusCodeException ex = null;
        try {
            pmClient().patch()
                    .uri("/api/v1/leases/" + lease.getId() + "/interactions/" + interactionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("summary", "Trying to edit a system entry"))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            ex = e;
        }

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void list_returns_paged() {
        // Insert 3 interactions
        TenantContextHolder.setTenantId(tenantId);
        for (int i = 0; i < 3; i++) {
            LeaseInteraction interaction = new LeaseInteraction();
            interaction.setTenantId(tenantId);
            interaction.setLease(lease);
            interaction.setType(InteractionType.NOTE);
            interaction.setDirection(InteractionDirection.INTERNAL);
            interaction.setOccurredAt(Instant.now().minusSeconds(300L * (i + 1)));
            interaction.setSummary("Note " + i);
            interaction.setCreatedBy(managerUserId);
            interactionRepo.save(interaction);
        }
        TenantContextHolder.clear();

        Map<?, ?> body = pmClient().get()
                .uri("/api/v1/leases/" + lease.getId() + "/interactions")
                .retrieve()
                .body(Map.class);

        assertThat(body).isNotNull();
        List<?> content = (List<?>) body.get("content");
        assertThat(content).hasSize(3);
    }
}
