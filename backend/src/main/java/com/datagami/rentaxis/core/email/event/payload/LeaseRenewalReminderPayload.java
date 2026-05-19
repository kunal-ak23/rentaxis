package com.datagami.rentaxis.core.email.event.payload;

import java.util.UUID;

public record LeaseRenewalReminderPayload(
        UUID opportunityId,
        UUID leaseId,
        UUID renterUserId,
        String leaseEndDateIso,
        int slot,
        String renewToken,
        String moveOutToken,
        String discussToken,
        String portalBaseUrl,
        String unitNumber,
        String propertyNameEn
) {}
