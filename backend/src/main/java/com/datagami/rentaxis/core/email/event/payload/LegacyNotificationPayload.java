package com.datagami.rentaxis.core.email.event.payload;

import java.util.UUID;

/**
 * Generic carrier for legacy {@code NotificationService.notify(...)} events
 * during the migration to typed email payloads. Maps the existing
 * (title, message, referenceType, referenceId) tuple into a record so the
 * email pipeline can render a fallback "legacy_notification" template
 * without per-event payload classes for every legacy code path.
 */
public record LegacyNotificationPayload(
        UUID userId,
        String title,
        String body,
        String referenceType,
        UUID referenceId
) {}
