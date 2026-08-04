package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;

import java.time.LocalDate;
import java.util.UUID;

public record BookingCreateRequest(
        BookingResourceType resourceType,
        UUID resourceId,
        UUID unitId,
        LocalDate preferredDate,
        String note) {
}
