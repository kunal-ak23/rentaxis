package com.datagami.rentaxis.core.service.rating;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One scored fact. sourceType is CHEQUE|PENALTY|WRITE_OFF|LEASE|OPPORTUNITY|MEETING|ADJUSTMENT;
 * note carries the adjustment reason, else null.
 */
public record RatingEvent(RatingEventType type, LocalDate date, int basePoints, double weight,
                          double appliedPoints, String sourceType, UUID sourceId, UUID leaseId, String note) {

    RatingEvent withAppliedPoints(double applied) {
        return new RatingEvent(type, date, basePoints, weight, applied, sourceType, sourceId, leaseId, note);
    }
}
