package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record BookingCreateRequest(
        BookingResourceType resourceType,
        UUID resourceId,
        UUID unitId,
        LocalDate preferredDate,
        LocalDate preferredEndDate,
        LocalTime preferredStartTime,
        LocalTime preferredEndTime,
        @Size(max = 2000) String note) {

    /** Keeps existing API callers source-compatible while they migrate to a
     * requested time window. */
    public BookingCreateRequest(BookingResourceType resourceType, UUID resourceId,
                                UUID unitId, LocalDate preferredDate, String note) {
        this(resourceType, resourceId, unitId, preferredDate, null, null, null, note);
    }

    /** Compatibility constructor for amenity clients using the former time-window payload. */
    public BookingCreateRequest(BookingResourceType resourceType, UUID resourceId,
                                UUID unitId, LocalDate preferredDate,
                                LocalTime preferredStartTime, LocalTime preferredEndTime,
                                String note) {
        this(resourceType, resourceId, unitId, preferredDate, null,
                preferredStartTime, preferredEndTime, note);
    }
}
