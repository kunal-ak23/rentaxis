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

    /** Forgot-password mails to this address (person-level links, review P2-1). */
    private int resetMailsTo(String email) {
        return jdbc.queryForObject("select count(*) from email_outbox where event_type = 'PASSWORD_RESET_REQUESTED' "
                + "and dedup_key like '%:forgot:%' and lower(recipient_email) = lower(?)", Integer.class, email);
    }

    /** The link is issued off the request thread; wait for it (or prove it never comes). */
    private int awaitMails(int expected, String email) throws InterruptedException {
        Instant until = Instant.now().plusSeconds(10);
        int n = resetMailsTo(email);
        while (n < expected && Instant.now().isBefore(until)) {
            Thread.sleep(100);
            n = resetMailsTo(email);
        }
        return n;
    }

    /** The newest link mailed to this address, read from the mail itself (only a hash is stored). */
    private String linkTokenFrom(String email) {
        String body = jdbc.queryForObject("select body_html from email_outbox where event_type = 'PASSWORD_RESET_REQUESTED' "
                + "and dedup_key like '%:forgot:%' and lower(recipient_email) = lower(?) order by created_at desc limit 1",
                String.class, email);
        var m = java.util.regex.Pattern.compile("token=([0-9a-f]{64})").matcher(body);
        assertThat(m.find()).as("the mail carries the link").isTrue();
        return m.group(1);
    }

    private UUID mailTenant(String email) {
        return jdbc.queryForObject("select tenant_id from email_outbox where event_type = 'PASSWORD_RESET_REQUESTED' "
                + "and dedup_key like '%:forgot:%' and lower(recipient_email) = lower(?) order by created_at desc limit 1",
                UUID.class, email);
    }

    private ResponseEntity<String> register(String email, String password) {
        return client().post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", nextIp())
                .body(Map.of("fullName", "Mallory", "companyName", "Miftah Account Security " + UUID.randomUUID(),
                        "email", email, "password", password))
                .retrieve().onStatus(st -> true, (rq, rs) -> { }).toEntity(String.class);
    }

    private ResponseEntity<String> loginInto(String email, String password, UUID tenantId) {
        return client().post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", nextIp())
                .body(Map.of("email", email, "password", password, "tenantId", tenantId.toString()))
                .retrieve().onStatus(st -> true, (rq, rs) -> { }).toEntity(String.class);
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
        assertThat(awaitMails(1, email)).as("one link, to the address").isEqualTo(1);
        assertThat(mailTenant(email)).as("Miftah's branding, not an organisation's").isNull();
        String token = linkTokenFrom(email);
        Double minutesLeft = jdbc.queryForObject(
                "select extract(epoch from (expires_at - now())) / 60 from password_reset_tokens where token_hash = ?",
                Double.class, com.datagami.rentaxis.core.service.auth.PasswordResetTokenStore.hash(token));
        assertThat(jdbc.queryForObject("select count(*) from users where invite_token is not null and id in (?, ?)",
                Integer.class, inA.getId(), inB.getId())).as("no row carries it").isZero();
        assertThat(minutesLeft).isBetween(44.0, 46.0);
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
        assertThat(resetMailsTo(guardEmail) + resetMailsTo(adminEmail) + resetMailsTo(inactiveEmail)).isZero();
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

    // --- review round 1 -------------------------------------------------------

    /**
     * The reviewer's scenario (P2-1): someone registers an organisation with the
     * victim's address and their own password, then keeps signing in to it. The
     * victim's link is still theirs: it lives on no row, carries no organisation's
     * branding, survives those sign-ins, and sets the victim's password — not the
     * attacker's row.
     */
    @Test
    void aRowSomeoneRegisteredWithYourEmailCanNeitherCatchNorKillYourLink() throws Exception {
        UUID orgB = newTenant("FORGOT-B");
        String email = email();
        User victim = row(orgB, UserRole.RENTER, email);
        ResponseEntity<String> reg = register(email, "attacker-knows-1");
        assertThat(reg.getStatusCode().value()).as(reg.getBody()).isEqualTo(200);
        UUID attackerRow = UUID.fromString(json.readTree(reg.getBody()).get("id").asText());
        UUID attackerOrg = UUID.fromString(json.readTree(reg.getBody()).get("tenantId").asText());

        forgot(Map.of("email", email), nextIp());
        assertThat(awaitMails(1, email)).isEqualTo(1);
        assertThat(mailTenant(email)).as("not the attacker's org branding").isNull();
        String token = linkTokenFrom(email);
        for (int i = 0; i < 3; i++) {
            assertThat(loginInto(email, "attacker-knows-1", attackerOrg).getStatusCode().value()).isEqualTo(200);
        }

        assertThat(setPassword(token, NEW).getStatusCode().value()).as("the attacker's sign-ins did not kill it")
                .isEqualTo(204);
        assertThat(passwordEncoder.matches(NEW, userRepo.findById(victim.getId()).orElseThrow().getPasswordHash()))
                .isTrue();
        assertThat(passwordEncoder.matches(NEW, userRepo.findById(attackerRow).orElseThrow().getPasswordHash()))
                .as("the registered org does not join the victim's login").isFalse();
        ResponseEntity<String> in = login(email, NEW);
        assertThat(in.getStatusCode().value()).isEqualTo(200);
        assertThat(in.getBody()).doesNotContain(attackerOrg.toString());
    }

    @Test
    void aLinkIsSentEvenWhileARowHasAnOpenInviteOrAdminResetAndLeavesThemAlone() throws Exception {
        UUID org = newTenant("FORGOT-I");
        String email = email();
        User r = row(org, UserRole.RENTER, email);
        jdbc.update("update users set invite_token = ?, invite_token_expires_at = now() + interval '7 days' where id = ?",
                "i".repeat(64), r.getId());

        forgot(Map.of("email", email), nextIp());

        assertThat(awaitMails(1, email)).as("review P3-2: always sent").isEqualTo(1);
        assertThat(jdbc.queryForObject("select invite_token from users where id = ?", String.class, r.getId()))
                .as("the row's own link is untouched").isEqualTo("i".repeat(64));
    }

    @Test
    void aMixedCaseLegacyRowCanResetAndThenSignIn() throws Exception {
        UUID org = newTenant("FORGOT-L");
        String lower = email();
        String legacy = "Legacy-" + lower.substring(0, 1).toUpperCase() + lower.substring(1);
        User r = row(org, UserRole.RENTER, legacy);

        assertThat(login(legacy.toLowerCase(), OLD).getStatusCode().value())
                .as("review P3-3: sign-in matches case-insensitively").isEqualTo(200);
        forgot(Map.of("email", legacy.toLowerCase()), nextIp());
        assertThat(awaitMails(1, legacy)).isEqualTo(1);
        assertThat(setPassword(linkTokenFrom(legacy), NEW).getStatusCode().value()).isEqualTo(204);
        assertThat(login(legacy.toLowerCase(), NEW).getStatusCode().value()).isEqualTo(200);
        assertThat(passwordEncoder.matches(NEW, userRepo.findById(r.getId()).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void someoneWhoseOnlyLoginIsTheirOwnRegisteredOrgCanStillResetIt() throws Exception {
        String email = email();
        ResponseEntity<String> reg = register(email, "my-own-password-1");
        UUID row = UUID.fromString(json.readTree(reg.getBody()).get("id").asText());

        forgot(Map.of("email", email), nextIp());
        assertThat(awaitMails(1, email)).isEqualTo(1);
        assertThat(setPassword(linkTokenFrom(email), NEW).getStatusCode().value()).isEqualTo(204);
        assertThat(passwordEncoder.matches(NEW, userRepo.findById(row).orElseThrow().getPasswordHash())).isTrue();
        assertThat(jdbc.queryForObject("select self_registered_unverified from users where id = ?", Boolean.class, row))
                .as("the link proved the inbox").isFalse();
    }

    @Test
    void aResetAlsoClearsTheOldPasswordOnAPausedRow() throws Exception {
        UUID orgA = newTenant("FORGOT-A");
        UUID orgC = newTenant("FORGOT-C");
        String email = email();
        row(orgA, UserRole.RENTER, email);
        User paused = row(orgC, UserRole.RENTER, email);
        jdbc.update("update users set status = 'INACTIVE' where id = ?", paused.getId());

        forgot(Map.of("email", email), nextIp());
        assertThat(awaitMails(1, email)).isEqualTo(1);
        assertThat(setPassword(linkTokenFrom(email), NEW).getStatusCode().value()).isEqualTo(204);

        String pausedHash = userRepo.findById(paused.getId()).orElseThrow().getPasswordHash();
        assertThat(passwordEncoder.matches(OLD, pausedHash)).isFalse();
        assertThat(passwordEncoder.matches(NEW, pausedHash)).isFalse();
    }
}
