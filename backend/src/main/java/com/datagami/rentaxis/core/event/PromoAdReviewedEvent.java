package com.datagami.rentaxis.core.event;

import com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus;

import java.util.UUID;

/**
 * An admin approved or rejected a promoter's ad ({@code status} APPROVED or
 * REJECTED; {@code reason} only for a rejection). Published inside the review's
 * transaction; the promoter is told after it commits.
 */
public record PromoAdReviewedEvent(UUID tenantId, UUID adId, UUID promoterUserId,
                                   PromoApprovalStatus status, String reason) {
}
