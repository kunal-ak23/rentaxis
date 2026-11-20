package com.datagami.rentaxis.api.dto.rating;

import com.datagami.rentaxis.core.service.rating.RatingEvent;
import com.datagami.rentaxis.core.service.rating.RatingEventType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One scored fact behind a rating. adjustmentId is set only for ADJUSTMENT events; createdByName is the
 * display name of the user who added an adjustment (null for other events, or when the user is gone).
 */
public record RatingEventDTO(RatingEventType type, LocalDate date, int basePoints, double weight,
                             double appliedPoints, String sourceType, UUID sourceId, UUID leaseId,
                             UUID adjustmentId, String note, String createdByName) {

    public static RatingEventDTO of(RatingEvent e) {
        return of(e, null);
    }

    public static RatingEventDTO of(RatingEvent e, String createdByName) {
        UUID adjustmentId = e.type() == RatingEventType.ADJUSTMENT ? e.sourceId() : null;
        return new RatingEventDTO(e.type(), e.date(), e.basePoints(), e.weight(), e.appliedPoints(),
                e.sourceType(), e.sourceId(), e.leaseId(), adjustmentId, e.note(),
                adjustmentId != null ? createdByName : null);
    }
}
