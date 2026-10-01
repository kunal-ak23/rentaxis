package com.datagami.rentaxis.api.dto;

import java.util.List;

public record ChequeExtractionResponseDTO(
        ChequeImageMetaDTO image,
        ExtractedChequeDTO extracted,
        List<String> warnings,
        /*
         * The organisation's payee check on the read payee: MATCH, MISMATCH or
         * UNREADABLE; null when the check is off or has no valid names.
         */
        com.datagami.rentaxis.domain.entity.enums.PayeeCheck payeeCheck
) {
    public ChequeExtractionResponseDTO(ChequeImageMetaDTO image, ExtractedChequeDTO extracted, List<String> warnings) {
        this(image, extracted, warnings, null);
    }
}
