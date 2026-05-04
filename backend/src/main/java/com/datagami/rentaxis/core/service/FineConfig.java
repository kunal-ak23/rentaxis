package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;

import java.math.BigDecimal;

public record FineConfig(
        BigDecimal bounceAmount,
        BigDecimal signatureMismatchAmount,
        BigDecimal accountClosedAmount,
        Integer graceDays,
        BigDecimal perDayRate,
        Source source
) {
    public enum Source { ORG, PROPERTY }

    public BigDecimal amountFor(ChequeFailureReason reason) {
        return switch (reason) {
            case BOUNCE             -> bounceAmount;
            case SIGNATURE_MISMATCH -> signatureMismatchAmount;
            case ACCOUNT_CLOSED     -> accountClosedAmount;
        };
    }
}
