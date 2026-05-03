package com.datagami.rentaxis.api.dto;

import java.math.BigDecimal;

public record FineConfigDTO(
        BigDecimal bounceAmount,
        BigDecimal signatureMismatchAmount,
        BigDecimal accountClosedAmount,
        Integer    graceDays,
        BigDecimal perDayRate
) {}
