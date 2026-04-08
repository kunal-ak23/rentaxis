package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.ListingMediaType;

import java.util.UUID;

public record UnitListingMediaDTO(
        UUID id,
        ListingMediaType mediaType,
        String url,
        String caption,
        Integer sortOrder,
        Boolean isCover
) {
}
