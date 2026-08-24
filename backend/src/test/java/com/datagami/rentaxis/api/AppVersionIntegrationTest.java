package com.datagami.rentaxis.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end cover for the app-version gate against a real Postgres (so the
 * Liquibase seed in {@code 73-app-versions.yaml} runs) and the real security
 * stack (so {@code ApiSecurityFilter} + the {@code @PreAuthorize} on the admin
 * controller are actually exercised, not merely present).
 *
 * <p>Proves the four contract points the pure-Mockito tests cannot: the seed
 * lands the right values on the right rows; the public endpoint fails open with
 * 200 (never 404) for an unknown app; a SUPER_ADMIN bump persists and the very
 * next public read reflects it; and the bump is SUPER_ADMIN-only.
 *
 * <p>Auth context normally injected by the Next.js proxy is simulated via the
 * X-User-* headers {@code ApiSecurityFilter} reads (same technique as
 * {@code PromotionAdminControllerAuthorizationTest}). The SUPER_ADMIN
 * header-assertion gate is off here because {@code app.auth.internal-proxy-secret}
 * is blank by default in tests.
 *
 * <p>Bodies are read as {@code String} and parsed with a locally-constructed
 * {@link ObjectMapper} — the same pattern the other tests in this package use,
 * which sidesteps the RestClient message-converter entirely.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AppVersionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort
    int port;

    private final ObjectMapper mapper = new ObjectMapper();

    /** A client that never throws on 4xx/5xx, so the test can assert the status itself. */
    private RestClient client() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, (req, res) -> { })
                .build();
    }

    private ResponseEntity<String> publicGet(String query) {
        return client().get()
                .uri("/api/v1/public/app-version" + query)
                .retrieve()
                .toEntity(String.class);
    }

    private JsonNode json(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException("response was not JSON: " + body, e);
        }
    }

    // ── public GET: seeded values ────────────────────────────────────────────

    @Test
    void publicGet_seededRenterAndroid_returnsSeededFloor() {
        ResponseEntity<String> resp = publicGet("?app=RENTER&platform=ANDROID");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(resp.getBody());
        assertThat(body.get("minSupportedBuild").asInt()).isEqualTo(0);
        assertThat(body.get("latestBuild").asInt()).isEqualTo(3);
        assertThat(body.get("latestVersionName").asText()).isEqualTo("1.2.0");
        assertThat(body.get("storeUrl").asText()).isEqualTo("");
    }

    @Test
    void publicGet_isCaseInsensitive() {
        ResponseEntity<String> resp = publicGet("?app=renter&platform=android");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(resp.getBody()).get("latestBuild").asInt()).isEqualTo(3);
    }

    // ── public GET: fail open (the server half of fail-open) ─────────────────

    @Test
    void publicGet_unknownApp_returns200PermissiveFallback_not404() {
        ResponseEntity<String> resp = publicGet("?app=NOPE&platform=ANDROID");

        // The load-bearing assertion: a miss is 200 + min 0, NEVER 404/500.
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getStatusCode().value()).isNotEqualTo(404);
        JsonNode body = json(resp.getBody());
        assertThat(body.get("minSupportedBuild").asInt()).isEqualTo(0);
        assertThat(body.get("latestBuild").asInt()).isEqualTo(0);
    }

    @Test
    void publicGet_missingParams_returns200Permissive() {
        ResponseEntity<String> resp = publicGet("");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(resp.getBody()).get("minSupportedBuild").asInt()).isEqualTo(0);
    }

    // ── admin bump: persists and is reflected by the public read ─────────────

    @Test
    void adminBump_asSuperAdmin_persists_andPublicReadReflectsIt() {
        // Bump SECURITY/IOS (a row no other test reads) so the change is isolated.
        ResponseEntity<Void> put = client().put()
                .uri("/api/v1/admin/app-versions/SECURITY/IOS")
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Role", "SUPER_ADMIN")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "minSupportedBuild", 2,
                        "latestBuild", 5,
                        "latestVersionName", "1.4.0",
                        "storeUrl", "https://example.test/app"))
                .retrieve()
                .toBodilessEntity();
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The public read is the client's source of truth — it must reflect the bump.
        ResponseEntity<String> resp = publicGet("?app=SECURITY&platform=IOS");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(resp.getBody());
        assertThat(body.get("minSupportedBuild").asInt()).isEqualTo(2);
        assertThat(body.get("latestBuild").asInt()).isEqualTo(5);
        assertThat(body.get("latestVersionName").asText()).isEqualTo("1.4.0");
        assertThat(body.get("storeUrl").asText()).isEqualTo("https://example.test/app");
    }

    @Test
    void adminList_asSuperAdmin_returnsAllSixSeededRows() {
        ResponseEntity<String> resp = client().get()
                .uri("/api/v1/admin/app-versions")
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Role", "SUPER_ADMIN")
                .retrieve()
                .toEntity(String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(resp.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isGreaterThanOrEqualTo(6);
    }

    // ── admin bump: SUPER_ADMIN only ─────────────────────────────────────────

    private int bumpStatusFor(String role) {
        UUID tenantId = UUID.randomUUID();
        // Targets MANAGER/ANDROID — a real, valid row (so authorization is the
        // only thing that can reject the request) that no read test in this
        // class asserts on. That keeps this test honest even if the guard is
        // ever removed: the leaked write would land on a row nothing reads,
        // rather than silently corrupting another test's expectation.
        ResponseEntity<Void> resp = client().put()
                .uri("/api/v1/admin/app-versions/MANAGER/ANDROID")
                // Full tenant-scoped headers so ApiSecurityFilter authorises the
                // request and establishes the role context; the @PreAuthorize is
                // then the only thing that can reject it — an honest 403, not a
                // filter-level 401 for missing context.
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Role", role)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Tenant-Id", tenantId.toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("minSupportedBuild", 1, "latestBuild", 1))
                .retrieve()
                .toBodilessEntity();
        return resp.getStatusCode().value();
    }

    @Test
    void adminBump_asRenter_isForbidden() {
        assertThat(bumpStatusFor("RENTER")).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void adminBump_asTenantAdmin_isForbidden() {
        // The bump is a global, cross-tenant lever, so even a tenant admin is
        // denied — only SUPER_ADMIN may raise the floor.
        assertThat(bumpStatusFor("TENANT_ADMIN")).isEqualTo(HttpStatus.FORBIDDEN.value());
    }
}
