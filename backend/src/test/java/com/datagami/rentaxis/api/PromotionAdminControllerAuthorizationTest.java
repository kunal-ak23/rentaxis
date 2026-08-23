package com.datagami.rentaxis.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the class-level {@code @PreAuthorize} on
 * {@link PromotionAdminController}. The Mockito test alongside this one calls
 * the controller directly and so proves nothing about enforcement — without
 * this file, an admin API that creates content pushed to renters' phones would
 * be guarded by an annotation nobody had ever exercised.
 *
 * <p>Auth context normally injected by the Next.js proxy is simulated via the
 * X-User-* headers that {@code ApiSecurityFilter} reads.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PromotionAdminControllerAuthorizationTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort int port;

    private final UUID tenantId = UUID.randomUUID();

    private int statusFor(String role) {
        RestClient client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
        try {
            client.get()
                    .uri("/api/v1/promotions/ads")
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Role", role)
                    .header("X-Tenant-Id", tenantId.toString())
                    .header("X-User-Tenant-Id", tenantId.toString())
                    .retrieve()
                    .toBodilessEntity();
            return HttpStatus.OK.value();
        } catch (HttpStatusCodeException e) {
            return e.getStatusCode().value();
        }
    }

    @Test
    void renterIsForbidden() {
        assertThat(statusFor("RENTER")).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void propertyManagerIsForbidden() {
        // Deliberate: promotions are tenant-wide, so there is no property
        // assignment that would meaningfully scope a manager's view.
        assertThat(statusFor("PROPERTY_MANAGER")).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void securityGuardIsForbidden() {
        assertThat(statusFor("SECURITY_GUARD")).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void tenantAdminIsAllowed() {
        // The counterpart to the three above: proves the annotation is
        // discriminating, not simply denying everyone.
        assertThat(statusFor("TENANT_ADMIN")).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void anUnauthenticatedRequestIsRejected() {
        RestClient client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
        try {
            client.get().uri("/api/v1/promotions/ads").retrieve().toBodilessEntity();
            assertThat(false).as("expected the request to be rejected").isTrue();
        } catch (HttpStatusCodeException e) {
            assertThat(e.getStatusCode().is4xxClientError()).isTrue();
        }
    }
}
