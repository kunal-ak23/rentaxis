package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.NotificationDTO;
import com.datagami.rentaxis.domain.entity.Notification;
import com.datagami.rentaxis.domain.repository.DeviceTokenRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NotificationService#getNotifications(UUID, int, int, boolean)},
 * in particular the {@code unreadOnly} flag the web 'Unread' tab sends
 * (GET /api/v1/notifications?unreadOnly=true), which used to be silently ignored.
 *
 * <p>Tests drive the real service, stubbing the repository, and assert that
 * filtering happens BEFORE pagination so unread pages are pages of the unread list.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceGetNotificationsTest {

    @Mock NotificationRepository notificationRepository;
    @Mock DeviceTokenRepository deviceTokenRepository;
    @Mock LeaseRepository leaseRepository;
    @Mock org.springframework.context.ApplicationEventPublisher events;

    private NotificationService service;

    private UUID userId;
    private Notification unread1;
    private Notification read1;
    private Notification unread2;
    private Notification read2;

    @BeforeEach
    void setUp() {
        service = new NotificationService(
                notificationRepository, deviceTokenRepository, leaseRepository, events);
        userId = UUID.randomUUID();
        // Repository order is created_at DESC; interleave read/unread on purpose.
        unread1 = notification(false);
        read1 = notification(true);
        unread2 = notification(false);
        read2 = notification(true);
        when(notificationRepository.findAllByUserIdUnfiltered(userId))
                .thenReturn(List.of(unread1, read1, unread2, read2));
    }

    private Notification notification(boolean isRead) {
        Notification n = new Notification();
        n.setId(UUID.randomUUID());
        n.setUserId(userId);
        n.setType("TICKET_REPLY");
        n.setTitle("t");
        n.setMessage("m");
        n.setIsRead(isRead);
        return n;
    }

    @Test
    void unreadOnlyFalse_returnsAllInRepositoryOrder() {
        List<NotificationDTO> result = service.getNotifications(userId, 0, 20, false);

        assertThat(result).extracting(NotificationDTO::getId)
                .containsExactly(unread1.getId(), read1.getId(), unread2.getId(), read2.getId());
    }

    @Test
    void unreadOnlyTrue_filtersOutReadNotifications() {
        List<NotificationDTO> result = service.getNotifications(userId, 0, 20, true);

        assertThat(result).extracting(NotificationDTO::getId)
                .containsExactly(unread1.getId(), unread2.getId());
        assertThat(result).allMatch(dto -> !Boolean.TRUE.equals(dto.getIsRead()));
    }

    @Test
    void unreadOnlyTrue_paginatesTheFilteredList() {
        // Page 1 of size 1 over the unread list must be the SECOND unread
        // notification — not whatever sits at raw offset 1 (a read one).
        List<NotificationDTO> result = service.getNotifications(userId, 1, 1, true);

        assertThat(result).extracting(NotificationDTO::getId)
                .containsExactly(unread2.getId());
    }

    @Test
    void unreadOnlyFalse_paginationUnchanged() {
        List<NotificationDTO> result = service.getNotifications(userId, 1, 2, false);

        assertThat(result).extracting(NotificationDTO::getId)
                .containsExactly(unread2.getId(), read2.getId());
    }
}
