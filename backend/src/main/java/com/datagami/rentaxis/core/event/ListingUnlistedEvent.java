package com.datagami.rentaxis.core.event;

import java.util.UUID;

public record ListingUnlistedEvent(UUID listingId, UUID tenantId) {
}
