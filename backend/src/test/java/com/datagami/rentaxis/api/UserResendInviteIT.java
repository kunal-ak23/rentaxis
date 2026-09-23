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
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
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
}
