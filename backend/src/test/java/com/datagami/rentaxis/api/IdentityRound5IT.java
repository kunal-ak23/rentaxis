package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.email.outbox.EmailOutbox;
import com.datagami.rentaxis.core.email.outbox.EmailOutboxRepository;
import com.datagami.rentaxis.core.security.TokenRevocationService;
import com.datagami.rentaxis.core.service.UserService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.UserTenantMembershipRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round 5, identity P2s from audit part A: F3 (a tenant move drops the old
 * membership), F4 (8-character minimum), F5 (login refuses deactivated users and
 * organisations, after the password and only then), F9 (the outbox admin API does
 * not hand out rendered bodies carrying invite links).
 */
class IdentityRound5IT extends AbstractCallerIdentityIT {

    private static final String PASSWORD = "round5-password-ok";

    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserService userService;
    @Autowired UserTenantMembershipRepository memberships;
    @Autowired TokenRevocationService tokenRevocation;
    @Autowired EmailOutboxRepository outbox;

    // ------------------------------------------------------------------ F3

    @Test
    @SuppressWarnings("unchecked")
    void movingAUserToAnotherTenantDropsTheOldMembershipAndRevokesTokens() {
        UUID a = newTenant("MOVE-A");
        UUID b = newTenant("MOVE-B");
        TenantContextHolder.setTenantId(a);
        User admin = user(a, UserRole.TENANT_ADMIN, passwordEncoder.encode(PASSWORD));
        TenantContextHolder.clear();
        userService.addTenantMembership(admin.getId(), a);
        int before = tokenRevocation.currentTokenVersion(admin.getId());

        userService.updateUser(admin.getId(), admin.getEmail(), null, admin.getName(),
                UserRole.TENANT_ADMIN, b.toString(), null);

        assertThat(memberships.existsByUserIdAndTenantId(admin.getId(), a)).isFalse();
        assertThat(memberships.existsByUserIdAndTenantId(admin.getId(), b)).isTrue();
        assertThat(tokenRevocation.currentTokenVersion(admin.getId())).isGreaterThan(before);

        // And the next login's token no longer lists the old organisation.
        Map<?, ?> login = login(admin.getEmail(), PASSWORD).getBody();
        assertThat((List<Object>) login.get("tenantIds")).containsExactly((Object) b.toString());
    }

    // ------------------------------------------------------------------ F4

    @Test
    void newPasswordsMustBeEightCharacters() {
        UUID t = newTenant("PWD");
        TenantContextHolder.setTenantId(t);
        User admin = user(t, UserRole.TENANT_ADMIN, passwordEncoder.encode(PASSWORD));
        TenantContextHolder.clear();
        userService.addTenantMembership(admin.getId(), t);

        assertThat(status(asSelf(HttpMethod.PUT, "/api/auth/me/password", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("currentPassword", PASSWORD, "newPassword", "seven77")))).isEqualTo(400);
        assertThat(status(client().post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "198.51.100." + (int) (Math.random() * 250))
                .body(Map.of("fullName", "Short Pwd", "companyName", "Short Pwd Co " + UUID.randomUUID(),
                        "email", "short-" + UUID.randomUUID() + "@t.io", "password", "seven77")))).isEqualTo(400);
        assertThatThrownBy(() -> userService.updateUser(admin.getId(), admin.getEmail(), "seven77",
                admin.getName(), UserRole.TENANT_ADMIN, t.toString(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 8");

        assertThat(status(asSelf(HttpMethod.PUT, "/api/auth/me/password", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("currentPassword", PASSWORD, "newPassword", "eight888")))).isEqualTo(200);
    }

    // ------------------------------------------------------------------ F5

    @Test
    void anInactiveUserCannotLogInAndIsToldSoOnlyAfterTheRightPassword() {
        UUID t = newTenant("INACTIVE-USER");
        TenantContextHolder.setTenantId(t);
        User u = user(t, UserRole.TENANT_ADMIN, passwordEncoder.encode(PASSWORD));
        u.setStatus(UserStatus.INACTIVE);
        userRepo.save(u);
        TenantContextHolder.clear();

        ResponseEntity<Map> right = login(u.getEmail(), PASSWORD);
        assertThat(right.getStatusCode().value()).isEqualTo(403);
        assertThat(right.getBody()).containsEntry("error", "ACCOUNT_INACTIVE");
        assertThat(right.getBody()).doesNotContainKey("token");

        assertThat(login(u.getEmail(), "wrong-password-x").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void aUserWhoseOnlyOrganisationIsInactiveCannotLogIn() {
        UUID t = newTenant("INACTIVE-ORG");
        TenantContextHolder.setTenantId(t);
        User u = user(t, UserRole.PROPERTY_MANAGER, passwordEncoder.encode(PASSWORD));
        TenantContextHolder.clear();
        userService.addTenantMembership(u.getId(), t);
        assertThat(login(u.getEmail(), PASSWORD).getStatusCode().value()).isEqualTo(200);

        LandlordOrg org = orgRepo.findById(t).orElseThrow();
        org.setStatus("INACTIVE");
        orgRepo.save(org);

        ResponseEntity<Map> res = login(u.getEmail(), PASSWORD);
        assertThat(res.getStatusCode().value()).isEqualTo(403);
        assertThat(res.getBody()).containsEntry("error", "ORG_INACTIVE");
    }

    // ------------------------------------------------------------------ F9

    @Test
    void theOutboxAdminApiNeverReturnsTheRenderedBody() {
        UUID t = newTenant("OUTBOX");
        TenantContextHolder.setTenantId(t);
        User admin = user(t, UserRole.TENANT_ADMIN, passwordEncoder.encode(PASSWORD));
        TenantContextHolder.clear();
        userService.addTenantMembership(admin.getId(), t);

        EmailOutbox row = new EmailOutbox();
        row.setTenantId(t);
        row.setEventType("USER_INVITED");
        row.setEventCategory("TRANSACTIONAL");
        row.setRecipientUserId(admin.getId());
        row.setRecipientEmail("invitee-" + UUID.randomUUID() + "@t.io");
        row.setRecipientLocale("en");
        row.setSubject("You're invited");
        row.setBodyHtml("<a href=\"https://app/auth/set-password?token=SECRET-INVITE-TOKEN\">Set password</a>");
        row.setBodyText("https://app/auth/set-password?token=SECRET-INVITE-TOKEN");
        row.setScheduledAt(Instant.now());
        row.setStatus(EmailOutbox.Status.SENT); // not for the dispatcher to pick up
        row.setDedupKey("round5-" + UUID.randomUUID());
        row = outbox.save(row);

        String list = asSelf(HttpMethod.GET, "/api/v1/admin/email/outbox", admin)
                .header("X-Tenant-Id", t.toString())
                .retrieve().body(String.class);
        String one = asSelf(HttpMethod.GET, "/api/v1/admin/email/outbox/" + row.getId(), admin)
                .header("X-Tenant-Id", t.toString())
                .retrieve().body(String.class);

        assertThat(list).contains(row.getId().toString()).contains("You're invited");
        for (String body : List.of(list, one)) {
            assertThat(body).doesNotContain("SECRET-INVITE-TOKEN").doesNotContain("bodyHtml")
                    .doesNotContain("bodyText");
        }
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> login(String email, String password) {
        return client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(Map.class);
    }
}
