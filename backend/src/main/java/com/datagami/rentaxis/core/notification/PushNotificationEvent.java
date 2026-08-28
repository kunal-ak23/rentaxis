package com.datagami.rentaxis.core.notification;

import java.util.UUID;

/**
 * An in-app notification that should also be delivered to the user's device.
 * Published inside the notification transaction and sent only after it commits.
 */
public record PushNotificationEvent(
        UUID userId,
        String type,
        String title,
        String message,
        String referenceType,
        UUID referenceId) {
}
