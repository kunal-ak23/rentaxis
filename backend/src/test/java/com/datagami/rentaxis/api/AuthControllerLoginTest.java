package com.datagami.rentaxis.api;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the multi-tenant disambiguation flow introduced when migration 59
 * relaxed users.email from globally unique to per-tenant unique.
 *
 * The invariants we care about:
 *   - 1 candidate, right password → 200
 *   - 0 candidates                → 401 (and timing matches the wrong-pwd path
 *                                  because of the constant-time dummy hash)
 *   - >1 candidates, no tenantId  → 409 with the candidate list in the body
 *   - >1 candidates, with valid tenantId → 200
 *   - >1 candidates, with bogus tenantId → 401
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthControllerLoginTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort int port;
    @Autowired UserRepository userRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired PasswordEncoder passwordEncoder;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private LandlordOrg makeOrg(String label) {
        LandlordOrg org = new LandlordOrg();
        org.setName("LoginTest-" + label + "-" + UUID.randomUUID());
        return orgRepo.save(org);
    }

    private User makeUser(LandlordOrg org, String email, String rawPassword) {
        User u = new User();
        u.setEmail(email);
        u.setName("u-" + UUID.randomUUID());
        u.setRole(UserRole.RENTER);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        u.setTenantId(org.getId());
        return userRepo.save(u);
    }

    @Test
    void singleTenantSuccess() {
        LandlordOrg org = makeOrg("single");
        String email = "single-" + UUID.randomUUID() + "@test";
        makeUser(org, email, "correct-horse");

        Map<?, ?> resp = client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "correct-horse"))
                .retrieve().body(Map.class);

        assertThat(resp.get("email")).isEqualTo(email);
        assertThat(resp.get("tenantId")).isEqualTo(org.getId().toString());
    }

    @Test
    void unknownEmailReturns401() {
        String email = "ghost-" + UUID.randomUUID() + "@test";
        try {
            client().post().uri("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email, "password", "anything"))
                    .retrieve().body(Map.class);
            throw new AssertionError("expected 401");
        } catch (HttpStatusCodeException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void crossTenantSameEmailDifferentPasswordsLogsIntoTheMatchingOne() {
        // Password matches in ONE tenant — the other tenant's row is not
        // reported via 409 (no leak). Caller is logged in directly.
        LandlordOrg a = makeOrg("a");
        LandlordOrg b = makeOrg("b");
        String email = "shared-" + UUID.randomUUID() + "@test";
        makeUser(a, email, "pwd-in-a");
        makeUser(b, email, "pwd-in-b");

        Map<?, ?> resp = client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "pwd-in-a"))
                .retrieve().body(Map.class);

        assertThat(resp.get("tenantId")).isEqualTo(a.getId().toString());
    }

    @Test
    void crossTenantSameEmailWithTenantIdLogsIn() {
        LandlordOrg a = makeOrg("ax");
        LandlordOrg b = makeOrg("bx");
        String email = "pick-" + UUID.randomUUID() + "@test";
        makeUser(a, email, "pwd-a");
        makeUser(b, email, "pwd-b");

        Map<?, ?> resp = client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "pwd-b", "tenantId", b.getId().toString()))
                .retrieve().body(Map.class);

        assertThat(resp.get("tenantId")).isEqualTo(b.getId().toString());
    }

    @Test
    void superAdminLoginWorks() {
        // SUPER_ADMIN has tenant_id IS NULL — exercises the partial-index path.
        String email = "super-" + UUID.randomUUID() + "@test";
        User u = new User();
        u.setEmail(email);
        u.setName("Super");
        u.setRole(UserRole.SUPER_ADMIN);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash(passwordEncoder.encode("super-pwd"));
        u.setTenantId(null);
        userRepo.save(u);

        Map<?, ?> resp = client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "super-pwd"))
                .retrieve().body(Map.class);

        assertThat(resp.get("role")).isEqualTo("SUPER_ADMIN");
        assertThat(resp.get("tenantId")).isNull();
    }

    @Test
    void multiCandidateWithWrongPasswordReturns401NotAmbiguous() {
        // Regression test for the 409-without-password-check oracle. An
        // attacker who knows an email exists in multiple tenants but does NOT
        // know any password must get 401, not 409. The 409 body would
        // otherwise leak tenant identities to unauthenticated callers.
        LandlordOrg a = makeOrg("orphan-a");
        LandlordOrg b = makeOrg("orphan-b");
        String email = "leak-test-" + UUID.randomUUID() + "@test";
        makeUser(a, email, "real-pwd-a");
        makeUser(b, email, "real-pwd-b");

        try {
            client().post().uri("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email, "password", "nope-doesnt-match-anything"))
                    .retrieve().body(Map.class);
            throw new AssertionError("expected 401");
        } catch (HttpStatusCodeException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            // Body must not leak tenant names.
            String body = e.getResponseBodyAsString();
            assertThat(body).doesNotContain(a.getName());
            assertThat(body).doesNotContain(b.getName());
        }
    }

    @Test
    void crossTenantWithSamePasswordInBothReturns409() {
        // The legitimate 409 case: password genuinely matches in >1 tenant
        // (user reused the same password across orgs). Picker payload should
        // include both tenants because the caller has proven access to both.
        LandlordOrg a = makeOrg("dup-pwd-a");
        LandlordOrg b = makeOrg("dup-pwd-b");
        String email = "dup-pwd-" + UUID.randomUUID() + "@test";
        String sharedPwd = "same-everywhere";
        makeUser(a, email, sharedPwd);
        makeUser(b, email, sharedPwd);

        try {
            client().post().uri("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email, "password", sharedPwd))
                    .retrieve().body(Map.class);
            throw new AssertionError("expected 409");
        } catch (HttpStatusCodeException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            String body = e.getResponseBodyAsString();
            assertThat(body).contains(a.getId().toString());
            assertThat(body).contains(b.getId().toString());
        }
    }

    @Test
    void crossTenantWithBogusTenantIdReturns401() {
        LandlordOrg a = makeOrg("ay");
        LandlordOrg b = makeOrg("by");
        String email = "bogus-" + UUID.randomUUID() + "@test";
        makeUser(a, email, "pwd");
        makeUser(b, email, "pwd");

        UUID neverExisted = UUID.randomUUID();
        try {
            client().post().uri("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email, "password", "pwd", "tenantId", neverExisted.toString()))
                    .retrieve().body(Map.class);
            throw new AssertionError("expected 401");
        } catch (HttpStatusCodeException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
