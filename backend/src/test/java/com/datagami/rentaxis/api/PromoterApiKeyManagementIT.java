package com.datagami.rentaxis.api;

import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Promoter developer API, task 4: the signed-in endpoints that manage keys. */
class PromoterApiKeyManagementIT extends AbstractPromoterIT {

    private static final String KEYS = "/api/v1/promoter/api-keys";
    private static final String ADMIN = "/api/admin/promoter-api-keys";

    private User newPromoter() {
        return promoter(org(), false, List.of(), List.of());
    }

    private User superAdmin() {
        return user(null, UserRole.SUPER_ADMIN);
    }

    @Test
    void promoterCreatesListsRotatesRevokes() {
        User p = newPromoter();

        Resp created = post(p, KEYS, body("name", "CRM sync"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("secret").asText()).startsWith("mfk_live_");
        String id = created.body().get("key").get("id").asText();
        assertThat(created.body().get("key").get("status").asText()).isEqualTo("active");
        assertThat(created.body().get("key").get("revokedByAdmin").asBoolean()).isFalse();

        Resp list = bearer(p, HttpMethod.GET, KEYS, null);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body()).hasSize(1);
        assertThat(list.body().get(0).get("keyPrefix").asText()).isEqualTo(created.body().get("secret").asText().substring(0, 12));

        Resp rotated = bearer(p, HttpMethod.POST, KEYS + "/" + id + "/rotate", null);
        assertThat(rotated.status()).isEqualTo(201);
        String newId = rotated.body().get("key").get("id").asText();
        assertThat(newId).isNotEqualTo(id);
        assertThat(rotated.body().get("key").get("name").asText()).isEqualTo("CRM sync");
        Resp old = get(p, KEYS);
        assertThat(statusOf(old, id)).isEqualTo("expiring");

        Resp revoked = post(p, KEYS + "/" + newId + "/revoke", body("reason", "leaked"));
        assertThat(revoked.status()).isEqualTo(200);
        assertThat(revoked.text("status")).isEqualTo("revoked");
        assertThat(revoked.text("revokedReason")).isEqualTo("leaked");
        assertThat(revoked.body().get("revokedByAdmin").asBoolean()).isFalse();

        // No body at all is fine: the reason is optional.
        Resp noBody = bearer(p, HttpMethod.POST, KEYS + "/" + id + "/revoke", null);
        assertThat(noBody.status()).isEqualTo(200);
        assertThat(noBody.text("status")).isEqualTo("revoked");
    }

    @Test
    void listNeverContainsSecret() {
        User p = newPromoter();
        String secret = post(p, KEYS, body("name", "k")).text("secret");

        Resp list = get(p, KEYS);

        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().toString()).doesNotContain(secret).doesNotContain("secret").doesNotContain("keyHash");
        List<String> fields = new ArrayList<>();
        list.body().get(0).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "name", "keyPrefix", "createdAt", "lastUsedAt", "expiresAt",
                "revokedAt", "revokedReason", "revokedByAdmin", "status");
    }

    @Test
    void createResponseIsNoStore() {
        User p = newPromoter();
        String token = tokens.issue(p.getId(), p.getRole(), p.getTenantId(), List.of(p.getTenantId()));

        ResponseEntity<String> create = client().post().uri(KEYS).header("Authorization", "Bearer " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body("name", "k"))
                .retrieve().toEntity(String.class);
        assertThat(create.getStatusCode().value()).isEqualTo(201);
        assertThat(create.getHeaders().getCacheControl()).contains("no-store");

        String id;
        try {
            id = json.readTree(create.getBody()).get("key").get("id").asText();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        ResponseEntity<String> rotate = client().post().uri(KEYS + "/" + id + "/rotate")
                .header("Authorization", "Bearer " + token).retrieve().toEntity(String.class);
        assertThat(rotate.getStatusCode().value()).isEqualTo(201);
        assertThat(rotate.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void nonPromoterCannotUsePromoterRoutes() {
        User tenantAdmin = user(org(), UserRole.TENANT_ADMIN);

        assertThat(get(tenantAdmin, KEYS).status()).isEqualTo(403);
        assertThat(post(tenantAdmin, KEYS, body("name", "k")).status()).isEqualTo(403);
        assertThat(bearer(tenantAdmin, HttpMethod.GET, KEYS, null).status()).isEqualTo(403);
    }

    @Test
    void anotherPromotersKeyIsNotFound() {
        User a = newPromoter();
        User b = newPromoter();
        String id = post(a, KEYS, body("name", "k")).body().get("key").get("id").asText();

        assertThat(post(b, KEYS + "/" + id + "/revoke", null).status()).isEqualTo(404);
        assertThat(post(b, KEYS + "/" + id + "/rotate", null).status()).isEqualTo(404);
        assertThat(statusOf(get(a, KEYS), id)).isEqualTo("active");
    }

    @Test
    void superAdminListsAcrossOrgsAndRevokes() {
        User admin = superAdmin();
        User p1 = newPromoter();
        User p2 = newPromoter();
        String id1 = post(p1, KEYS, body("name", "alpha")).body().get("key").get("id").asText();
        String id2 = post(p2, KEYS, body("name", "beta")).body().get("key").get("id").asText();

        Resp all = get(admin, ADMIN + "?size=100");
        assertThat(all.status()).isEqualTo(200);
        List<String> ids = all.body().get("content").findValuesAsText("id");
        assertThat(ids).contains(id1, id2);
        JsonNode row1 = row(all, id1);
        assertThat(row1.get("tenantId").asText()).isEqualTo(p1.getTenantId().toString());
        assertThat(row1.get("promoterEmail").asText()).isEqualTo(p1.getEmail());
        assertThat(all.body().toString()).doesNotContain("keyHash").doesNotContain("secret");

        assertThat(ids(get(admin, ADMIN + "?q=beta&size=100"))).containsExactly(id2);
        assertThat(ids(get(admin, ADMIN + "?status=revoked&size=100"))).doesNotContain(id1, id2);
        assertThat(get(admin, ADMIN + "?status=bogus").status()).isEqualTo(400);

        assertThat(post(admin, ADMIN + "/" + id1 + "/revoke", body("reason", " ")).status()).isEqualTo(400);
        assertThat(post(admin, ADMIN + "/" + id1 + "/revoke", null).status()).isEqualTo(400);
        Resp revoked = post(admin, ADMIN + "/" + id1 + "/revoke", body("reason", "abuse"));
        assertThat(revoked.status()).isEqualTo(200);
        assertThat(revoked.text("status")).isEqualTo("revoked");
        assertThat(revoked.text("revokedReason")).isEqualTo("abuse");
        assertThat(revoked.text("orgName")).isNotBlank();
        assertThat(ids(get(admin, ADMIN + "?status=revoked&size=100"))).contains(id1).doesNotContain(id2);
        assertThat(post(admin, ADMIN + "/" + UUID.randomUUID() + "/revoke", body("reason", "x")).status()).isEqualTo(404);
    }

    @Test
    void superAdminRevokeShowsAsRevokedByAdminToPromoter() {
        User admin = superAdmin();
        User p = newPromoter();
        String id = post(p, KEYS, body("name", "k")).body().get("key").get("id").asText();

        assertThat(post(admin, ADMIN + "/" + id + "/revoke", body("reason", "policy")).status()).isEqualTo(200);

        Resp list = get(p, KEYS);
        JsonNode k = list.body().get(0);
        assertThat(k.get("status").asText()).isEqualTo("revoked");
        assertThat(k.get("revokedByAdmin").asBoolean()).isTrue();
        assertThat(k.get("revokedReason").asText()).isEqualTo("policy");
    }

    @Test
    void tenantAdminCannotHitAdminRoutes() {
        User tenantAdmin = user(org(), UserRole.TENANT_ADMIN);
        String id = post(newPromoter(), KEYS, body("name", "k")).body().get("key").get("id").asText();

        assertThat(get(tenantAdmin, ADMIN).status()).isEqualTo(403);
        assertThat(post(tenantAdmin, ADMIN + "/" + id + "/revoke", body("reason", "x")).status()).isEqualTo(403);
        assertThat(statusOf(get(promoterOf(id), KEYS), id)).isEqualTo("active");
    }

    @Test
    void promoterCannotHitAdminRoutes() {
        User p = newPromoter();
        String id = post(p, KEYS, body("name", "k")).body().get("key").get("id").asText();

        assertThat(get(p, ADMIN).status()).isEqualTo(403);
        assertThat(bearer(p, HttpMethod.GET, ADMIN, null).status()).isEqualTo(403);
        assertThat(post(p, ADMIN + "/" + id + "/revoke", body("reason", "x")).status()).isEqualTo(403);
        assertThat(statusOf(get(p, KEYS), id)).isEqualTo("active");
    }

    // ------------------------------------------------------------------ helpers

    private User promoterOf(String keyId) {
        UUID userId = jdbc.queryForObject("SELECT user_id FROM promoter_api_key WHERE id = ?::uuid", UUID.class, keyId);
        return userRepo.findById(userId).orElseThrow();
    }

    private static JsonNode row(Resp page, String id) {
        for (JsonNode n : page.body().get("content")) {
            if (n.get("id").asText().equals(id)) {
                return n;
            }
        }
        throw new AssertionError("no row " + id);
    }

    private static List<String> ids(Resp page) {
        return page.body().get("content").findValuesAsText("id");
    }

    private static String statusOf(Resp list, String id) {
        for (JsonNode n : list.body()) {
            if (n.get("id").asText().equals(id)) {
                return n.get("status").asText();
            }
        }
        throw new AssertionError("no key " + id);
    }
}
