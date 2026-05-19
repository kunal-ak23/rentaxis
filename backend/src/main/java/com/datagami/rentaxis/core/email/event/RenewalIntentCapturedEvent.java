package com.datagami.rentaxis.core.email.event;

import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import java.util.UUID;

public record RenewalIntentCapturedEvent(
        UUID opportunityId,
        UUID leaseId,
        UUID tenantId,
        RenewalIntent intent
) {}
