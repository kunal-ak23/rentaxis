package com.datagami.rentaxis.core.event;

import java.util.UUID;

/**
 * A promoter's write put ads in front of the organisation's admins for approval:
 * one ad for a create or edit; for an edit of a business, {@code adCount} ads of it,
 * {@code adId} being one of them (review M3: one notification per edit, not one per
 * ad). Published inside the write's transaction; {@code PromoterNotificationService}
 * tells the admins after it commits.
 */
public record PromoAdSubmittedEvent(UUID tenantId, UUID adId, UUID promoterUserId, int adCount) {
}
