package com.datagami.rentaxis.core.event;

import java.util.UUID;

public record ListingPublishedEvent(UUID listingId, UUID tenantId) {
}
