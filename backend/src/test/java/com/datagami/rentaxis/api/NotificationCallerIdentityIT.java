package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Notification;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The notification API takes the caller from the verified principal (PR #342).
 * PR #98 scoped mark-as-read to the caller, but keyed it off X-User-Id, which on
 * the bearer path is the caller's to choose: a user could read, count and mark
 * another user's notifications, and register a push device against them.
 */
class NotificationCallerIdentityIT extends AbstractCallerIdentityIT {

    @Autowired NotificationRepository notificationRepo;

    private User userA;
    private User victim;
    private UUID victimsNotification;

    @BeforeEach
    void setUp() {
        UUID tenantId = newTenant("NCI");
        TenantContextHolder.setTenantId(tenantId);
        userA = user(tenantId, UserRole.RENTER, "x");
        victim = user(tenantId, UserRole.RENTER, "x");
        victimsNotification = notification(victim, "For the victim");
        notification(userA, "For A");
        TenantContextHolder.clear();
    }

    private UUID notification(User to, String title) {
        Notification n = new Notification();
        n.setUserId(to.getId());
        n.setType("GENERAL");
        n.setTitle(title);
        n.setMessage(title);
        return notificationRepo.save(n).getId();
    }

    private boolean isRead(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT is_read FROM notifications WHERE id = ?",
                Boolean.class, id));
    }

    @Test
    void theVictimCanMarkTheirOwnNotificationRead() {
        // Control: the 404 below is about who is asking, not a missing row.
        assertThat(status(asSelf(HttpMethod.PUT, "/api/v1/notifications/" + victimsNotification + "/read", victim)))
                .isEqualTo(200);
        assertThat(isRead(victimsNotification)).isTrue();
    }

    @Test
    void aForgedUserIdCannotMarkAnotherUsersNotificationRead() {
        assertThat(status(forged(HttpMethod.PUT, "/api/v1/notifications/" + victimsNotification + "/read",
                userA, victim))).isEqualTo(404);
        assertThat(isRead(victimsNotification)).isFalse();
    }

    @Test
    void readAllWithAForgedUserIdMarksOnlyTheCallersOwn() {
        assertThat(status(forged(HttpMethod.PUT, "/api/v1/notifications/read-all", userA, victim))).isEqualTo(200);
        assertThat(isRead(victimsNotification)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND is_read = false",
                Long.class, userA.getId())).isZero();
    }

    @Test
    void theListAndCountAreTheCallersNotTheHeadersUsers() {
        @SuppressWarnings("rawtypes")
        List listed = forged(HttpMethod.GET, "/api/v1/notifications", userA, victim).retrieve().body(List.class);
        assertThat(listed).hasSize(1);
        assertThat(((Map<?, ?>) listed.get(0)).get("title")).isEqualTo("For A");

        @SuppressWarnings("rawtypes")
        Map count = forged(HttpMethod.GET, "/api/v1/notifications/unread-count", userA, victim)
                .retrieve().body(Map.class);
        assertThat(((Number) count.get("count")).longValue()).isEqualTo(1L);
    }

    @Test
    void aDeviceRegisteredWithAForgedUserIdIsTheCallers() {
        String token = "fcm-" + UUID.randomUUID();
        assertThat(status(forged(HttpMethod.POST, "/api/v1/notifications/devices/register", userA, victim)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("token", token, "platform", "android")))).isEqualTo(200);

        assertThat(jdbc.queryForList("SELECT user_id FROM device_tokens WHERE token = ?", UUID.class, token))
                .containsExactly(userA.getId());
    }
}
