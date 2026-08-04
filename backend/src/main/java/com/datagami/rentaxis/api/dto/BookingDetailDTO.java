package com.datagami.rentaxis.api.dto;

import java.util.List;

/** Admin detail: the request plus all other PENDING/APPROVED requests for the same resource. */
public record BookingDetailDTO(
        BookingRequestDTO request,
        List<BookingRequestDTO> otherRequests) {
}
