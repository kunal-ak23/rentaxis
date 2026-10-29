package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.security.ApiKeySecrets;
import com.datagami.rentaxis.core.service.LandlordOrgService;
import com.datagami.rentaxis.core.service.PromoterApiKeyService;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Promoter developer API, task 2: {@code /api/v1/partner/**} is authenticated by
 * {@code X-Api-Key} alone. A key opens nothing else, a JWT or X-User-* headers open
 * nothing there, and a key stops working the moment its promoter stops being an
 * ACTIVE PROMOTER of the key's organisation.
 *
 * <p>Every call comes "from" its own client IP (X-Forwarded-For, which the backend
 * honours from loopback), so the per-IP failed-auth budget one test spends never
 * decides another test's outcome — this context is shared with the other promoter ITs.
 */
class PartnerApiKeyAuthIT extends AbstractPromoterIT {

    static final String PING = "/api/v1/partner/promoter/ping";
    static final String INVALID = "invalid_api_key";

    @Autowired PromoterApiKeyService keys;
    @Autowired LandlordOrgService orgService;

    UUID orgA;
    User promoterA;

    @BeforeEach
    void setUp() {
        orgA = org();
        promoterA = promoter(orgA, false, List.of(), List.of());
    }

    @Test
    void validKeyReachesPing() {
        String secret = keys.create(promoterA.getId(), "Feed").secret();
        PartnerResp res = partner(secret, PING, Map.of(), freshIp());
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.text("promoterUserId")).isEqualTo(promoterA.getId().toString());
        assertThat(res.text("tenantId")).isEqualTo(orgA.toString());
    }

    @Test
    void missingKeyIs401() {
        PartnerResp res = partner(null, PING, Map.of(), freshIp());
        assertThat(res.status()).isEqualTo(401);
        assertThat(res.text("error")).isEqualTo(INVALID);
    }

    @Test
    void unknownKeyIs401SameBody() {
        String ip = freshIp();
        PartnerResp unknown = partner(ApiKeySecrets.generate(), PING, Map.of(), ip);
        PartnerResp malformed = partner("not-a-key", PING, Map.of(), ip);
        PartnerResp missing = partner(null, PING, Map.of(), ip);
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(malformed.status()).isEqualTo(401);
        assertThat(unknown.body()).isEqualTo(missing.body());
        assertThat(malformed.body()).isEqualTo(missing.body());
    }

    @Test
    void revokedKeyIs401() {
        PromoterApiKeyService.IssuedKey issued = keys.create(promoterA.getId(), "Feed");
        assertThat(partner(issued.secret(), PING, Map.of(), freshIp()).status()).isEqualTo(200);
        keys.revokeByPromoter(promoterA.getId(), issued.key().getId(), null);
        PartnerResp res = partner(issued.secret(), PING, Map.of(), freshIp());
        assertThat(res.status()).isEqualTo(401);
        assertThat(res.text("error")).isEqualTo(INVALID);
    }

    @Test
    void expiredKeyIs401() {
        PromoterApiKeyService.IssuedKey issued = keys.create(promoterA.getId(), "Feed");
        jdbc.update("UPDATE promoter_api_key SET expires_at = now() - interval '1 second' WHERE id = ?",
                issued.key().getId());
        assertThat(partner(issued.secret(), PING, Map.of(), freshIp()).status()).isEqualTo(401);
    }

    /** Review focus 2: deactivated, demoted, moved — the very next call is refused, nothing cached. */
    @Test
    void keyDiesWithItsPromoter() {
        String secret = keys.create(promoterA.getId(), "Feed").secret();
        UUID id = promoterA.getId();
        assertThat(partner(secret, PING, Map.of(), freshIp()).status()).isEqualTo(200);

        jdbc.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", id);
        assertThat(partner(secret, PING, Map.of(), freshIp()).status()).as("inactive").isEqualTo(401);
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", id);
        assertThat(partner(secret, PING, Map.of(), freshIp()).status()).as("restored").isEqualTo(200);

        jdbc.update("UPDATE users SET role = 'TENANT_USER' WHERE id = ?", id);
        assertThat(partner(secret, PING, Map.of(), freshIp()).status()).as("demoted").isEqualTo(401);
        jdbc.update("UPDATE users SET role = 'PROMOTER' WHERE id = ?", id);
        assertThat(partner(secret, PING, Map.of(), freshIp()).status()).as("restored").isEqualTo(200);

        jdbc.update("UPDATE users SET tenant_id = ? WHERE id = ?", org(), id);
        PartnerResp moved = partner(secret, PING, Map.of(), freshIp());
        assertThat(moved.status()).as("moved org").isEqualTo(401);
        assertThat(moved.text("error")).isEqualTo(INVALID);
    }

    /**
     * Final review I2: a key is refused while its organisation is not ACTIVE, the same
     * rule the JWT and X-User-* paths apply, and works again once it is re-activated.
     */
    @Test
    void keyDiesWithItsOrganisation() {
        String secret = keys.create(promoterA.getId(), "Feed").secret();
        assertThat(partner(secret, PING, Map.of(), freshIp()).status()).isEqualTo(200);

        orgService.updateLocked(orgA, o -> o.setStatus("INACTIVE"));
        PartnerResp suspended = partner(secret, PING, Map.of(), freshIp());
        assertThat(suspended.status()).as("org suspended").isEqualTo(401);
        assertThat(suspended.text("error")).isEqualTo(INVALID);

        orgService.updateLocked(orgA, o -> o.setStatus("ACTIVE"));
        assertThat(partner(secret, PING, Map.of(), freshIp()).status()).as("org restored").isEqualTo(200);
    }

    /** Review focus 3: a signed-in promoter's JWT or the proxy's headers are not a partner credential. */
    @Test
    void jwtAloneIsRejectedOnPartnerPaths() {
        Resp viaBearer = bearer(promoterA, HttpMethod.GET, PING, null);
        Resp viaHeaders = get(promoterA, PING);
        assertThat(viaBearer.status()).isEqualTo(401);
        assertThat(viaHeaders.status()).isEqualTo(401);
        assertThat(viaBearer.text("error")).isEqualTo(INVALID);
        assertThat(viaHeaders.text("error")).isEqualTo(INVALID);
    }

    @Test
    void keyOpensNothingOutsidePartnerPaths() {
        String secret = keys.create(promoterA.getId(), "Feed").secret();
        for (String path : List.of("/api/v1/promoter/me", "/api/v1/properties", "/api/auth/me",
                "/api/v1/promoter/api-keys")) {
            int status = partner(secret, path, Map.of(), freshIp()).status();
            assertThat(status).as(path).isIn(401, 403);
        }
    }

    /** Review focus 3: the key decides who and which organisation; identity headers and a JWT are ignored. */
    @Test
    void spoofedHeadersAlongsideKeyAreIgnored() {
        String secret = keys.create(promoterA.getId(), "Feed").secret();
        UUID orgB = org();
        User adminB = user(orgB, UserRole.TENANT_ADMIN);
        String tokenB = tokens.issue(adminB.getId(), adminB.getRole(), orgB, List.of(orgB));
        Map<String, String> spoof = new LinkedHashMap<>();
        spoof.put("X-Tenant-Id", orgB.toString());
        spoof.put("X-User-Id", adminB.getId().toString());
        spoof.put("X-User-Role", "TENANT_ADMIN");
        spoof.put("X-User-Tenant-Id", orgB.toString());
        spoof.put("Authorization", "Bearer " + tokenB);

        PartnerResp res = partner(secret, PING, spoof, freshIp());
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.text("tenantId")).isEqualTo(orgA.toString());
        assertThat(res.text("promoterUserId")).isEqualTo(promoterA.getId().toString());
    }

    /**
     * The partner prefix is matched on the decoded path, as the dispatcher routes it:
     * a percent-encoded spelling still needs a key, and with one it still runs in the
     * key's organisation (the legacy filter must not clear or replace that context).
     */
    @Test
    void encodedPartnerPathIsStillKeyAuthenticated() {
        String secret = keys.create(promoterA.getId(), "Feed").secret();
        String encoded = "/api/v1/p%61rtner/promoter/ping";
        PartnerResp withKey = partner(secret, encoded, Map.of(), freshIp());
        assertThat(withKey.status()).isEqualTo(200);
        assertThat(withKey.text("tenantId")).isEqualTo(orgA.toString());

        Map<String, String> jwtOnly = Map.of("Authorization", "Bearer " + tokens.issue(promoterA.getId(),
                promoterA.getRole(), orgA, List.of(orgA)));
        assertThat(partner(null, encoded, jwtOnly, freshIp()).status()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ HTTP

    record PartnerResp(int status, JsonNode body, String retryAfter) {
        String text(String field) {
            JsonNode n = body == null ? null : body.get(field);
            return n == null || n.isNull() ? null : n.asText();
        }
    }

    /** A documentation-range address per call, so per-IP buckets never carry over. */
    static String freshIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "198.51." + r.nextInt(0, 256) + "." + r.nextInt(1, 255);
    }

    /** GET {@code path} with {@code X-Api-Key} (when not null), extra headers, from {@code ip}. */
    PartnerResp partner(String secret, String path, Map<String, String> headers, String ip) {
        return partnerCall(port, json, secret, path, headers, ip);
    }

    static PartnerResp partnerCall(int port, ObjectMapper json, String secret, String path,
                                   Map<String, String> headers, String ip) {
        // The JDK client, not the default Apache one: Apache HttpClient 5 silently
        // retries a 429 after its Retry-After, which would hide the very answer under test.
        RestClient.RequestHeadersSpec<?> spec = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory()).build().get()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-Forwarded-For", ip);
        if (secret != null) {
            spec = spec.header("X-Api-Key", secret);
        }
        for (Map.Entry<String, String> h : headers.entrySet()) {
            spec = spec.header(h.getKey(), h.getValue());
        }
        ResponseEntity<byte[]> res = spec.retrieve().onStatus(s -> true, (req, r) -> { }).toEntity(byte[].class);
        JsonNode node = null;
        byte[] bytes = res.getBody();
        if (bytes != null && bytes.length > 0) {
            try {
                node = json.readTree(bytes);
            } catch (Exception notJson) {
                node = null;
            }
        }
        return new PartnerResp(res.getStatusCode().value(), node, res.getHeaders().getFirst("Retry-After"));
    }
}
