package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.security.AuthTokenService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

/**
 * Shared setup for the caller-identity ITs (PR #342): a controller must take the
 * caller from the verified principal, so a caller holding a valid bearer token of
 * their own cannot become someone else by adding a forged X-User-Id/X-User-Role.
 *
 * <p>Same token secret as {@code TicketCallerIdentityIT}, so the suite reuses one
 * application context for all of them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.token-secret=ticket-caller-identity-it-secret-32-bytes-plus")
abstract class AbstractCallerIdentityIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired AuthTokenService tokens;
    @Autowired UserRepository userRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clearContexts() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    UUID newTenant(String prefix) {
        LandlordOrg org = new LandlordOrg();
        org.setName(prefix + "-" + UUID.randomUUID());
        return orgRepo.save(org).getId();
    }

    /** A user in {@code tenantId}; the caller must have set the tenant context. */
    User user(UUID tenantId, UserRole role, String passwordHash) {
        User u = new User();
        u.setEmail("cid-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name() + " " + UUID.randomUUID().toString().substring(0, 8));
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash(passwordHash);
        u.setTenantId(tenantId);
        return userRepo.save(u);
    }

    RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * {@code caller}'s own valid token, with every legacy identity header claiming
     * to be {@code victim} as a TENANT_ADMIN.
     */
    RestClient.RequestBodySpec forged(HttpMethod method, String uri, User caller, User victim) {
        String token = tokens.issue(caller.getId(), caller.getRole(), caller.getTenantId(),
                List.of(caller.getTenantId()));
        return client().method(method).uri(uri)
                .header("Authorization", "Bearer " + token)
                .header("X-User-Id", victim.getId().toString())
                .header("X-User-Role", "TENANT_ADMIN")
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString());
    }

    /** {@code caller}'s own valid token and nothing else. */
    RestClient.RequestBodySpec asSelf(HttpMethod method, String uri, User caller) {
        String token = tokens.issue(caller.getId(), caller.getRole(), caller.getTenantId(),
                List.of(caller.getTenantId()));
        return client().method(method).uri(uri)
                .header("Authorization", "Bearer " + token);
    }

    /** Sends the request and returns its status without throwing on 4xx/5xx. */
    static int status(RestClient.RequestHeadersSpec<?> spec) {
        ResponseEntity<Void> res = spec.retrieve()
                .onStatus(s -> true, (req, resp) -> { })
                .toBodilessEntity();
        return res.getStatusCode().value();
    }
}
