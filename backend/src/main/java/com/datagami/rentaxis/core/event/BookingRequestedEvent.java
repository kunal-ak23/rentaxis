package com.datagami.rentaxis.core.event;

import java.util.UUID;

public record BookingRequestedEvent(UUID bookingId, UUID tenantId) {
}
