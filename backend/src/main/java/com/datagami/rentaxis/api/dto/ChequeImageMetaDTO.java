package com.datagami.rentaxis.api.dto;

import java.time.OffsetDateTime;

public record ChequeImageMetaDTO(
        String url,
        String blobPath,
        OffsetDateTime uploadedAt
) {}
