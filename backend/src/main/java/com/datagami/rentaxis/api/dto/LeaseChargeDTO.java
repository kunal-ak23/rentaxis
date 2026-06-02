package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.ChargeFrequency;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class LeaseChargeDTO {
    @NotBlank
    private String name;

    @NotNull
    @Min(0)
    private BigDecimal amount;

    private boolean vatApplicable;

    @NotNull
    private ChargeFrequency frequency;
}
