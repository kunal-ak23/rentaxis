package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.Notification;
import com.datagami.rentaxis.domain.repository.DeviceTokenRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceMarkAsReadTest {

    @Mock NotificationRepository notificationRepository;
    @Mock DeviceTokenRepository deviceTokenRepository;
    @Mock LeaseRepository leaseRepository;
    @Mock ApplicationEventPublisher events;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(
                notificationRepository, deviceTokenRepository, leaseRepository, events);
    }

    @Test
    void markAsReadUpdatesNotificationOwnedByAuthenticatedUser() {
        UUID notificationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Notification notification = new Notification();
        notification.setId(notificationId);
        notification.setUserId(userId);
        notification.setIsRead(false);
        when(notificationRepository.findByIdAndUserIdUnfiltered(notificationId, userId))
                .thenReturn(Optional.of(notification));

        service.markAsRead(notificationId, userId);

        assertThat(notification.getIsRead()).isTrue();
        verify(notificationRepository).save(notification);
    }

    @Test
    void markAsReadDoesNotUpdateAnotherUsersNotification() {
        UUID notificationId = UUID.randomUUID();
        UUID authenticatedUserId = UUID.randomUUID();
        when(notificationRepository.findByIdAndUserIdUnfiltered(
                notificationId, authenticatedUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markAsRead(notificationId, authenticatedUserId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Notification not found");

        verify(notificationRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
