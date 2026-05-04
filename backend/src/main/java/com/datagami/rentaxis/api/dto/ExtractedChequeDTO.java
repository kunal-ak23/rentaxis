package com.datagami.rentaxis.api.dto;

import java.time.LocalDate;

public record ExtractedChequeDTO(
        String chequeNumber,
        String bankName,
        String payerName,
        LocalDate chequeDate,
        Confidence confidence
) {
    public enum Confidence { HIGH, MEDIUM, LOW }
}
