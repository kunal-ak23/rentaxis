package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.service.UserService;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.api.dto.UserResponseDTO;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.ChangesetSql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #7: "Resend invite" re-issues an unused or expired set-password invite, and no
 * user-provisioning response ever carries a password.
 *
 * <p>The auth context the Next.js proxy injects is simulated with the X-User-* /
 * X-Tenant-* headers {@code ApiSecurityFilter} reads, as in
 * {@link UserControllerRoleAuthorizationTest}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@RecordApplicationEvents
class UserResendInviteIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired UserRepository userRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserService userService;
    @Autowired ApplicationEvents events;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private LandlordOrg org(String label) {
        LandlordOrg org = new LandlordOrg();
        org.setName("ResendInvite-" + label + "-" + UUID.randomUUID());
        return orgRepo.save(org);
    }

    private User user(LandlordOrg org, UserRole role, String inviteToken, Instant expiresAt) {
        User u = new User();
        u.setEmail("u-" + UUID.randomUUID() + "@test");
        u.setName("user");
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash(passwordEncoder.encode("pwd"));
        u.setTenantId(role == UserRole.SUPER_ADMIN ? null : org.getId());
        u.setInviteToken(inviteToken);
        u.setInviteTokenExpiresAt(expiresAt);
        return userRepo.save(u);
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private ResponseEntity<String> post(User caller, String path, Map<String, Object> body) {
        RestClient.RequestBodySpec req = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name());
        if (caller.getTenantId() != null) {
            req = req.header("X-Tenant-Id", caller.getTenantId().toString())
                    .header("X-User-Tenant-Id", caller.getTenantId().toString());
        }
        if (body != null) req = req.body(body);
        return req.retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
    }

    @Test
    void anExpiredInviteIsReissuedAndTheOldLinkStopsWorking() {
        LandlordOrg org = org("ok");
        User admin = user(org, UserRole.TENANT_ADMIN, null, null);
        String oldToken = token();
        User renter = user(org, UserRole.RENTER, oldToken, Instant.now().minusSeconds(60));

        ResponseEntity<String> res = post(admin, "/api/admin/users/" + renter.getId() + "/resend-invite", null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).doesNotContainIgnoringCase("password").doesNotContain(oldToken);
        assertThat(res.getBody()).contains("\"invitePending\":true");
        User after = userRepo.findById(renter.getId()).orElseThrow();
        assertThat(after.getInviteToken()).isNotNull().hasSize(64).isNotEqualTo(oldToken);
        assertThat(after.getInviteTokenExpiresAt()).isAfter(Instant.now().plusSeconds(6 * 24 * 3600));
        assertThat(userService.acceptInvite(oldToken, "a-new-password"))
                .isEqualTo(UserService.InviteResult.NOT_FOUND);
        assertThat(userService.acceptInvite(after.getInviteToken(), "a-new-password"))
                .isEqualTo(UserService.InviteResult.OK);
    }

    @Test
    void anotherTenantsAdminCannotResend() {
        User renter = user(org("victim"), UserRole.RENTER, token(), Instant.now().plusSeconds(3600));
        String before = renter.getInviteToken();
        User foreignAdmin = user(org("attacker"), UserRole.TENANT_ADMIN, null, null);

        ResponseEntity<String> res = post(foreignAdmin, "/api/admin/users/" + renter.getId() + "/resend-invite", null);

        // 404 (the tenant filter hides the row) or 403 (the controller's own
        // tenant check); either way nothing is re-issued.
        assertThat(res.getStatusCode().value()).isIn(403, 404);
        assertThat(userRepo.findById(renter.getId()).orElseThrow().getInviteToken()).isEqualTo(before);
    }

    @Test
    void aPropertyManagerCannotResend() {
        LandlordOrg org = org("pm");
        User pm = user(org, UserRole.PROPERTY_MANAGER, null, null);
        User renter = user(org, UserRole.RENTER, token(), Instant.now().plusSeconds(3600));

        assertThat(post(pm, "/api/admin/users/" + renter.getId() + "/resend-invite", null)
                .getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void aUserWhoAlreadySetAPasswordHasNoInviteToResend() {
        LandlordOrg org = org("used");
        User admin = user(org, UserRole.TENANT_ADMIN, null, null);
        User renter = user(org, UserRole.RENTER, null, null);

        ResponseEntity<String> res = post(admin, "/api/admin/users/" + renter.getId() + "/resend-invite", null);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(userRepo.findById(renter.getId()).orElseThrow().getInviteToken()).isNull();
    }

    /** A second USER_INVITED under the first one's dedup key would be dropped by the outbox. */
    @Test
    void theResendEmitsUserInvitedUnderAFreshDedupKey() {
        LandlordOrg org = org("event");
        User renter = user(org, UserRole.RENTER, token(), Instant.now().plusSeconds(3600));

        User after = userService.resendInvite(renter.getId());

        List<EmailEvent> invited = events.stream(EmailEvent.class)
                .filter(e -> e.getType() == EmailEventType.USER_INVITED).toList();
        assertThat(invited).hasSize(1);
        assertThat(invited.get(0).getDedupKey())
                .startsWith("USER_INVITED:" + renter.getId() + ":")
                .isNotEqualTo("USER_INVITED:" + renter.getId());
        assertThat(invited.get(0).getTenantId()).isEqualTo(org.getId());
        assertThat(after.getInviteToken()).isNotNull();
    }

    /** Creating an invited user without a password is the normal path, and nothing echoes one. */
    @Test
    void creatingARenterUserNeedsNoPasswordAndReturnsNone() {
        LandlordOrg org = org("create");
        User admin = user(org, UserRole.TENANT_ADMIN, null, null);
        Map<String, Object> body = new HashMap<>();
        body.put("email", "invitee-" + UUID.randomUUID() + "@test");
        body.put("name", "Invitee");
        body.put("role", "PROPERTY_MANAGER");

        ResponseEntity<String> res = post(admin, "/api/admin/users", body);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).doesNotContainIgnoringCase("password").contains("\"invitePending\":true");
    }
    // --- PR #342 review I1: an activated user has no invite to resend ---

    private User welcomed(User u) {
        u.setWelcomedAt(Instant.now().minusSeconds(86400));
        return userRepo.save(u);
    }

    @Test
    void aUserWhoHasSignedInIsNotReinvitedEvenWithALegacyToken() {
        LandlordOrg org = org("welcomed");
        User admin = user(org, UserRole.TENANT_ADMIN, null, null);
        String legacy = token();
        User renter = welcomed(user(org, UserRole.RENTER, legacy, Instant.now().minusSeconds(60)));

        ResponseEntity<String> res = post(admin, "/api/admin/users/" + renter.getId() + "/resend-invite", null);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(userRepo.findById(renter.getId()).orElseThrow().getInviteToken()).isEqualTo(legacy);
        assertThat(UserResponseDTO.from(renter).isInvitePending()).isFalse();
        assertThat(UserResponseDTO.from(renter).getInviteExpiresAt()).isNull();
    }

    @Test
    void anInactiveUserIsNotReinvited() {
        LandlordOrg org = org("inactive");
        User admin = user(org, UserRole.TENANT_ADMIN, null, null);
        User renter = user(org, UserRole.RENTER, token(), Instant.now().minusSeconds(60));
        renter.setStatus(UserStatus.INACTIVE);
        userRepo.save(renter);

        assertThat(post(admin, "/api/admin/users/" + renter.getId() + "/resend-invite", null)
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void aPasswordSignInRetiresTheInvite() {
        LandlordOrg org = org("login");
        User renter = user(org, UserRole.RENTER, token(), Instant.now().plusSeconds(3600));

        ResponseEntity<String> res = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", renter.getEmail(), "password", "pwd"))
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        User after = userRepo.findById(renter.getId()).orElseThrow();
        assertThat(after.getInviteToken()).isNull();
        assertThat(after.getInviteTokenExpiresAt()).isNull();
    }

    @Test
    void changingYourOwnPasswordRetiresTheInvite() {
        User renter = user(org("mepwd"), UserRole.RENTER, token(), Instant.now().plusSeconds(3600));

        userService.changePassword(renter, "a-brand-new-password");

        assertThat(userRepo.findById(renter.getId()).orElseThrow().getInviteToken()).isNull();
    }

    @Test
    void anAdminSettingAPasswordRetiresTheInviteButAnEditWithoutOneDoesNot() {
        LandlordOrg org = org("adminpwd");
        User keeps = user(org, UserRole.PROPERTY_MANAGER, token(), Instant.now().plusSeconds(3600));
        User loses = user(org, UserRole.PROPERTY_MANAGER, token(), Instant.now().plusSeconds(3600));

        userService.updateUser(keeps.getId(), keeps.getEmail(), null, "Renamed", keeps.getRole(),
                org.getId().toString(), null);
        userService.updateUser(loses.getId(), loses.getEmail(), "set-by-admin-1", "Renamed", loses.getRole(),
                org.getId().toString(), null);

        assertThat(userRepo.findById(keeps.getId()).orElseThrow().getInviteToken()).isNotNull();
        assertThat(userRepo.findById(loses.getId()).orElseThrow().getInviteToken()).isNull();
    }

    /** Changeset 97, run on rows this test controls and rolled back. */
    @Test
    void changeset97RetiresOnlyTheTokensOfUsersWhoHaveSignedIn() {
        LandlordOrg org = org("cs97");
        tx.executeWithoutResult(status -> {
            User signedIn = welcomed(user(org, UserRole.RENTER, token(), Instant.now().minusSeconds(60)));
            User neverSignedIn = user(org, UserRole.RENTER, token(), Instant.now().minusSeconds(60));
            userRepo.flush();

            ChangesetSql.of("97-retire-invites-of-activated-users.yaml").forEach(jdbc::execute);

            assertThat(jdbc.queryForObject("SELECT invite_token FROM users WHERE id = ?", String.class,
                    signedIn.getId())).isNull();
            assertThat(jdbc.queryForObject("SELECT invite_token_expires_at FROM users WHERE id = ?",
                    java.sql.Timestamp.class, signedIn.getId())).isNull();
            assertThat(jdbc.queryForObject("SELECT invite_token FROM users WHERE id = ?", String.class,
                    neverSignedIn.getId())).isEqualTo(neverSignedIn.getInviteToken());
            status.setRollbackOnly();
        });
    }
}
