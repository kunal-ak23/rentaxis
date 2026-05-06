package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record LeasePayload(
        UUID leaseId,
        UUID renterUserId,
        UUID propertyManagerUserId,
        String unitLabel,
        String propertyName,
        String startDateIso,
        String endDateIso,
        String monthlyRentDisplay,
        String contractSignedUrl
) {}
