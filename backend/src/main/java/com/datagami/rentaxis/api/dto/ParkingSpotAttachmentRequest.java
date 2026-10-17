package com.datagami.rentaxis.api.dto;

import java.util.UUID;

/** Attached parking: the unit to allocate a bay to, or null to detach it (bookable again). */
public record ParkingSpotAttachmentRequest(UUID unitId) {
}
