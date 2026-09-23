package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.LegacyNotificationPayload;
import com.datagami.rentaxis.core.email.outbox.EmailOutbox;
import com.datagami.rentaxis.core.email.outbox.EmailOutboxRepository;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #342 review C1: EMAIL_NOTIFICATIONS defaults to OFF, and invite-only
 * onboarding made the emailed set-password link the only way into a new
 * account. Account-access mail must go out with the flag off; ordinary
 * notifications must still be held back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountMailBypassesTenantGateIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired EmailOutboxRepository outboxRepo;
    @Autowired TenantFeatureService tenantFeatureService;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;

    private UUID tenantWithEmailOff() {
        LandlordOrg org = new LandlordOrg();
        org.setName("MailGate-" + UUID.randomUUID());
        UUID id = orgRepo.save(org).getId();
        assertThat(tenantFeatureService.isEnabled(id, TenantFeature.EMAIL_NOTIFICATIONS)).isFalse();
        return id;
    }

    private User admin(UUID tenantId) {
        User u = new User();
        u.setEmail("mailgate-admin-" + UUID.randomUUID() + "@t.io");
        u.setName("Admin");
        u.setRole(UserRole.TENANT_ADMIN);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        return userRepo.save(u);
    }

    private List<EmailOutbox> rowsFor(UUID userId, String eventType) {
        return outboxRepo.findAll().stream()
                .filter(r -> userId.equals(r.getRecipientUserId()) && eventType.equals(r.getEventType()))
                .toList();
    }

    @Test
    void creatingARenterWithEmailNotificationsOffStillEmailsTheInvite() {
        UUID tenantId = tenantWithEmailOff();
        User admin = admin(tenantId);

        ResponseEntity<Map> res = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/v1/renters")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", admin.getId().toString())
                .header("X-User-Role", admin.getRole().name())
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Tenant-Id", tenantId.toString())
                .body(Map.of("nameEn", "Gate Renter", "email", "gate-renter-" + UUID.randomUUID() + "@t.io"))
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(Map.class);

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        UUID renterUserId = UUID.fromString((String) res.getBody().get("userId"));
        List<EmailOutbox> invites = rowsFor(renterUserId, "USER_INVITED");
        assertThat(invites).hasSize(1);
        assertThat(invites.get(0).getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void ordinaryNotificationsAreStillHeldBackWhileTheFlagIsOff() {
        UUID tenantId = tenantWithEmailOff();
        User recipient = admin(tenantId);

        tx.executeWithoutResult(status -> publisher.publishEvent(new EmailEvent(this,
                EmailEventType.MEETING_REQUESTED,
                tenantId,
                new LegacyNotificationPayload(recipient.getId(), "New Meeting", "Requested", "MEETING", UUID.randomUUID()),
                "MEETING_REQUESTED:gate:" + UUID.randomUUID())));

        assertThat(rowsFor(recipient.getId(), "MEETING_REQUESTED")).isEmpty();
    }

    @Test
    void onlyTheThreeAccountAccessEventsBypassTheGate() {
        assertThat(Arrays.stream(EmailEventType.values()).filter(EmailEventType::bypassesTenantGate))
                .containsExactlyInAnyOrder(EmailEventType.USER_INVITED,
                        EmailEventType.PASSWORD_RESET_REQUESTED, EmailEventType.PASSWORD_CHANGED);
    }
}
