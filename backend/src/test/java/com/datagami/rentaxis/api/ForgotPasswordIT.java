package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.UserService;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Public "forgot password" (2026-10-15): one answer for every email, a single-use
 * 45-minute link only for a login that can have one, the person's new password in
 * every organisation, and a per-IP limit. (The per-email limit is
 * {@code ForgotPasswordServiceTest}.)
 */
class ForgotPasswordIT extends AbstractCallerIdentityIT {

    private static final String OLD = "forgot-old-password-1";
    private static final String NEW = "forgot-new-password-2";
    /** A fresh client address per call, so the per-IP bucket never decides a test that is not about it. */
    private static final AtomicInteger IP = new AtomicInteger(1);

    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserService userService;
    private final ObjectMapper json = new ObjectMapper();

    private static String email() {
        return "forgot-" + UUID.randomUUID() + "@test.invalid";
    }

    private User row(UUID tenantId, UserRole role, String email) {
        User u = new User();
        u.setEmail(email);
        u.setName("Forgetful " + role);
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash(passwordEncoder.encode(OLD));
        u.setPasswordSelfSet(true);
        u.setTenantId(tenantId);
        User saved = userRepo.save(u);
        if (tenantId != null) userService.addTenantMembership(saved.getId(), tenantId);
        return saved;
    }

    private ResponseEntity<String> forgot(Object body, String ip) {
        var spec = client().post().uri("/api/auth/forgot-password")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) spec = spec.body(body);
        return spec.retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
    }

    private static String nextIp() {
        int n = IP.getAndIncrement();
        return "10.91." + (n / 250) + "." + (n % 250 + 1);
    }

    private int resetMails(UUID userId) {
        return jdbc.queryForObject("select count(*) from email_outbox where event_type = 'PASSWORD_RESET_REQUESTED' "
                + "and dedup_key like ?", Integer.class, "PASSWORD_RESET_REQUESTED:" + userId + ":forgot:%");
    }

    private int resetMailsFor(User... rows) {
        int n = 0;
        for (User r : rows) n += resetMails(r.getId());
        return n;
    }

    /** The link is issued off the request thread; wait for it (or prove it never comes). */
    private int awaitMails(int expected, User... rows) throws InterruptedException {
        Instant until = Instant.now().plusSeconds(10);
        int n = resetMailsFor(rows);
        while (n < expected && Instant.now().isBefore(until)) {
            Thread.sleep(100);
            n = resetMailsFor(rows);
        }
        return n;
    }

    private String tokenOf(User... rows) {
        for (User r : rows) {
            String t = jdbc.queryForObject("select invite_token from users where id = ?", String.class, r.getId());
            if (t != null) return t;
        }
        return null;
    }

    private ResponseEntity<String> setPassword(String token, String password) {
        return client().post().uri("/api/auth/set-password").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", nextIp())
                .body(Map.of("token", token, "newPassword", password))
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
    }

    private ResponseEntity<String> login(String email, String password) {
        return client().post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", nextIp())
                .body(Map.of("email", email, "password", password))
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
    }

    @Test
    void aPersonWithALoginGetsASingleUseLinkThatSetsTheirPasswordEverywhere() throws Exception {
        UUID orgA = newTenant("FORGOT-A");
        UUID orgB = newTenant("FORGOT-B");
        String email = email();
        User inA = row(orgA, UserRole.RENTER, email);
        User inB = row(orgB, UserRole.PROPERTY_MANAGER, email);
        String sessionB = json.readTree(client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).header("X-Forwarded-For", nextIp())
                .body(Map.of("email", email, "password", OLD, "tenantId", orgB.toString()))
                .retrieve().body(String.class)).get("token").asText();

        ResponseEntity<String> res = forgot(Map.of("email", "  " + email.toUpperCase() + " "), nextIp());

        assertThat(res.getStatusCode().value()).isEqualTo(202);
        assertThat(json.readTree(res.getBody()).get("message").asText())
                .isEqualTo(AuthController.FORGOT_PASSWORD_ANSWER);
        assertThat(awaitMails(1, inA, inB)).as("one link, to one of the person's rows").isEqualTo(1);
        String token = tokenOf(inA, inB);
        Instant expires = userRepo.findByInviteToken(token).orElseThrow().getInviteTokenExpiresAt();
        assertThat(Duration.between(Instant.now(), expires)).isBetween(Duration.ofMinutes(44), Duration.ofMinutes(46));
        assertThat(login(email, OLD).getStatusCode().value()).as("nothing changes until the link is used")
                .isEqualTo(409);

        assertThat(setPassword(token, NEW).getStatusCode().value()).isEqualTo(204);

        assertThat(passwordEncoder.matches(NEW, userRepo.findById(inA.getId()).orElseThrow().getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(NEW, userRepo.findById(inB.getId()).orElseThrow().getPasswordHash())).isTrue();
        assertThat(login(email, OLD).getStatusCode().value()).isEqualTo(401);
        assertThat(login(email, NEW).getStatusCode().value()).isEqualTo(409);
        assertThat(status(client().get().uri("/api/auth/me").header("Authorization", "Bearer " + sessionB)))
                .as("sessions end in every organisation").isEqualTo(401);
        assertThat(setPassword(token, "a-third-password-3").getStatusCode().value())
                .as("single use").isIn(404, 409);
    }

    @Test
    void everyEmailGetsTheSameAnswerAndOnlyALoginThatCanHaveOneGetsALink() throws Exception {
        UUID org = newTenant("FORGOT-X");
        String guardEmail = email();
        User guard = row(org, UserRole.SECURITY_GUARD, guardEmail);
        String adminEmail = email();
        User platformAdmin = row(null, UserRole.SUPER_ADMIN, adminEmail);
        String inactiveEmail = email();
        User inactive = row(org, UserRole.RENTER, inactiveEmail);
        jdbc.update("update users set status = 'INACTIVE' where id = ?", inactive.getId());

        Map<String, Object> blank = new HashMap<>();
        blank.put("email", null);
        Object[] bodies = {
                Map.of("email", email()),            // nobody
                Map.of("email", guardEmail),         // phone-OTP guard
                Map.of("email", adminEmail),         // platform administrator
                Map.of("email", inactiveEmail),      // deactivated
                Map.of("email", "not-an-email"),
                blank,
        };
        for (Object body : bodies) {
            ResponseEntity<String> res = forgot(body, nextIp());
            assertThat(res.getStatusCode().value()).as(String.valueOf(body)).isEqualTo(202);
            assertThat(res.getBody()).as(String.valueOf(body))
                    .isEqualTo("{\"message\":\"" + AuthController.FORGOT_PASSWORD_ANSWER + "\"}");
        }
        // Give the background work the time a real link takes, then prove none came.
        Thread.sleep(1500);
        assertThat(resetMailsFor(guard, platformAdmin, inactive)).isZero();
        assertThat(tokenOf(guard, platformAdmin, inactive)).isNull();
    }

    @Test
    void oneClientAddressIsLimitedToFiveRequestsAMinute() {
        String ip = "10.92.0." + (IP.getAndIncrement() % 250 + 1);
        for (int i = 0; i < 5; i++) {
            assertThat(forgot(Map.of("email", email()), ip).getStatusCode().value()).isEqualTo(202);
        }
        assertThat(forgot(Map.of("email", email()), ip).getStatusCode().value()).isEqualTo(429);
        assertThat(forgot(Map.of("email", email()), nextIp()).getStatusCode().value())
                .as("another address has its own budget").isEqualTo(202);
    }
}
