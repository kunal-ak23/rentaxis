package com.datagami.rentaxis.api;

import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in {@code @PreAuthorize("hasRole('RENTER')")} on
 * {@link PromotionFeedController}. Without this the renter feed's entire
 * authorization story is one annotation nobody has exercised.
 *
 * <p>Note the asymmetry with the admin controller: a TENANT_ADMIN is
 * deliberately NOT allowed here. The feed answers "what may this specific
 * renter see", which is meaningless for an admin, and letting one through
 * would resolve targeting against an admin's non-existent leases.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PromotionFeedControllerAuthorizationTest extends AbstractPostgresIT {

    @LocalServerPort int port;

    private final UUID tenantId = UUID.randomUUID();

    private int statusFor(String role) {
        RestClient client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
        try {
            client.get()
                    .uri("/api/v1/promotions/feed")
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
    void renterIsAllowed() {
        // No leases seeded, so the slate is empty — 200 with [] is the point.
        assertThat(statusFor("RENTER")).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void tenantAdminIsForbidden() {
        assertThat(statusFor("TENANT_ADMIN")).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void propertyManagerIsForbidden() {
        assertThat(statusFor("PROPERTY_MANAGER")).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void securityGuardIsForbidden() {
        assertThat(statusFor("SECURITY_GUARD")).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void anUnauthenticatedRequestIsRejected() {
        RestClient client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
        try {
            client.get().uri("/api/v1/promotions/feed").retrieve().toBodilessEntity();
            assertThat(false).as("expected the request to be rejected").isTrue();
        } catch (HttpStatusCodeException e) {
            assertThat(e.getStatusCode().is4xxClientError()).isTrue();
        }
    }
}
