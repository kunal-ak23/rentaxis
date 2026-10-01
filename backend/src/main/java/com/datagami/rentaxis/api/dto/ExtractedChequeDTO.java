package com.datagami.rentaxis.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ExtractedChequeDTO(
        String chequeNumber,
        String bankName,
        String payerName,
        /* The name on the "Pay" / "ادفعوا لأمر" line (owner ruling 2026-09-29); null if unreadable. */
        String payeeName,
        LocalDate chequeDate,
        BigDecimal amount,
        Confidence confidence
) {
    public enum Confidence { HIGH, MEDIUM, LOW }

    /** Without a payee: the shape before the payee was read. */
    public ExtractedChequeDTO(String chequeNumber, String bankName, String payerName,
                              LocalDate chequeDate, BigDecimal amount, Confidence confidence) {
        this(chequeNumber, bankName, payerName, null, chequeDate, amount, confidence);
    }
}
