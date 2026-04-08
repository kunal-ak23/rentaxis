package com.datagami.rentaxis.core.event;

import java.util.UUID;

public record InterestReceivedEvent(UUID interestId, UUID listingId, UUID renterUserId, UUID tenantId) {
}
