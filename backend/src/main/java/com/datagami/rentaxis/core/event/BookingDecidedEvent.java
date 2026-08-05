package com.datagami.rentaxis.core.event;

import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;

import java.util.UUID;

public record BookingDecidedEvent(UUID bookingId, UUID tenantId, UUID renterUserId,
                                  BookingRequestStatus status) {
}
