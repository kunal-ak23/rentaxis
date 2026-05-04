package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record FineConfigDTO(
        @NotNull @DecimalMin("0.00") BigDecimal bounceAmount,
        @NotNull @DecimalMin("0.00") BigDecimal signatureMismatchAmount,
        @NotNull @DecimalMin("0.00") BigDecimal accountClosedAmount,
        @NotNull @Min(0)             Integer    graceDays,
        @NotNull @DecimalMin("0.00") BigDecimal perDayRate
) {}
