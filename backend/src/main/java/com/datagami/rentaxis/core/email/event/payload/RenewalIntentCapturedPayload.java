package com.datagami.rentaxis.core.email.event.payload;

import java.util.UUID;

public record RenewalIntentCapturedPayload(
        UUID opportunityId,
        UUID leaseId,
        UUID tenantId,
        UUID propertyManagerUserId,
        String renterName,
        String unitNumber,
        String propertyNameEn,
        String intent
) {}
