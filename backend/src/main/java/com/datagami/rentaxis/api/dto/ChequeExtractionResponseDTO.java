package com.datagami.rentaxis.api.dto;

import java.util.List;

public record ChequeExtractionResponseDTO(
        ChequeImageMetaDTO image,
        ExtractedChequeDTO extracted,
        List<String> warnings
) {}
