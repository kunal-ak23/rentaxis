package com.datagami.rentaxis.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ExtractedChequeDTO(
        String chequeNumber,
        String bankName,
        String payerName,
        LocalDate chequeDate,
        BigDecimal amount,
        Confidence confidence
) {
    public enum Confidence { HIGH, MEDIUM, LOW }
}
