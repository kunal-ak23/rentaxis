package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/v1/tenant/features} and {@code GET /api/v1/tenant/info} for a
 * SUPER_ADMIN who has not selected an organisation.
 *
 * <p>{@code TenantContextHolder.getTenantId()} is null on that path — no
 * {@code X-Tenant-Id} header, and {@link com.datagami.rentaxis.core.security.ApiSecurityFilter}
 * never sets one for SUPER_ADMIN — so before the fix {@code TenantFeatureService.isEnabled}
 * NPE'd on the Caffeine cache's null-key lookup and {@code LandlordOrgService.findById} threw
 * {@code InvalidDataAccessApiUsageException}, both surfacing as 500s. Both endpoints are
 * {@code @PreAuthorize("isAuthenticated()")} and exist to gate navigation, so a tenant-less
 * caller must get a normal answer, not an error. This is a full-context HTTP test with the
 * legacy {@code X-User-*} headers the Next.js proxy sends, the same shape as
 * {@link LeaseControllerPostEndpointsIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TenantFeatureControllerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort int port;

    @Autowired UserRepository userRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired TenantFeatureService tenantFeatureService;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private UUID makeTenant() {
        LandlordOrg org = new LandlordOrg();
        org.setName("TenantFeatureControllerIT-" + UUID.randomUUID());
        return orgRepo.save(org).getId();
    }

    private User user(UserRole role, UUID tenantId) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        return userRepo.save(u);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> get(User caller, String path, UUID activeTenantId) {
        RestClient.RequestHeadersSpec<?> spec = RestClient.builder()
                .baseUrl("http://localhost:" + port).build()
                .get().uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name());
        // A SUPER_ADMIN with no organisation selected sends no X-Tenant-Id / X-User-Tenant-Id,
        // exactly like the web proxy before the user has picked an org.
        if (activeTenantId != null) {
            spec = ((RestClient.RequestHeadersSpec<?>) spec).headers(h -> {
                h.add("X-Tenant-Id", activeTenantId.toString());
                h.add("X-User-Tenant-Id", activeTenantId.toString());
            });
        }
        return spec.accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(Map.class);
    }

    @Test
    void superAdminWithNoTenantGetsDefaultFeatures() {
        User superAdmin = user(UserRole.SUPER_ADMIN, null);

        ResponseEntity<Map> response = get(superAdmin, "/api/v1/tenant/features", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get(TenantFeature.MOBILE_FINANCE.name())).isEqualTo(false);
        assertThat(response.getBody().get(TenantFeature.LISTINGS.name()))
                .isEqualTo(TenantFeature.LISTINGS.isDefaultEnabled());
    }

    @Test
    void superAdminWithNoTenantGetsEmptyTenantInfo() {
        User superAdmin = user(UserRole.SUPER_ADMIN, null);

        ResponseEntity<Map> response = get(superAdmin, "/api/v1/tenant/info", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("slug", "", "name", ""));
    }

    @Test
    void tenantUserGetsTheRealPerTenantFeatureMap() {
        UUID tenantId = makeTenant();
        User tenantUser = user(UserRole.TENANT_USER, tenantId);
        tenantFeatureService.setEnabled(tenantId, TenantFeature.MOBILE_FINANCE, true);

        ResponseEntity<Map> response = get(tenantUser, "/api/v1/tenant/features", tenantId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get(TenantFeature.MOBILE_FINANCE.name())).isEqualTo(true);
        assertThat(response.getBody().get(TenantFeature.GATEPASS.name())).isEqualTo(false);
    }
}
