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
    void crossTenantSameEmailWithoutTenantIdReturns409WithCandidates() {
        LandlordOrg a = makeOrg("a");
        LandlordOrg b = makeOrg("b");
        String email = "shared-" + UUID.randomUUID() + "@test";
        makeUser(a, email, "pwd-in-a");
        makeUser(b, email, "pwd-in-b");

        try {
            client().post().uri("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email, "password", "pwd-in-a"))
                    .retrieve().body(Map.class);
            throw new AssertionError("expected 409");
        } catch (HttpStatusCodeException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            // Body should contain a `tenants` array with both org IDs.
            String body = e.getResponseBodyAsString();
            assertThat(body).contains(a.getId().toString());
            assertThat(body).contains(b.getId().toString());
        }
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
