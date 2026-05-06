package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record PenaltyPayload(
        UUID penaltyId,
        UUID leaseId,
        UUID renterUserId,
        UUID propertyManagerUserId,
        String penaltyAmountDisplay,
        String reason,
        int installmentNumber
) {}
