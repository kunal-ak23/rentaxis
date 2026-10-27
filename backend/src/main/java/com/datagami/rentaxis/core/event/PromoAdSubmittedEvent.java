package com.datagami.rentaxis.core.event;

import java.util.UUID;

/**
 * A promoter's write put this ad in front of the organisation's admins for
 * approval (create, edit, or an edit of its business). Published inside the
 * write's transaction; {@code PromoterNotificationService} tells the admins
 * after it commits.
 */
public record PromoAdSubmittedEvent(UUID tenantId, UUID adId, UUID promoterUserId) {
}
