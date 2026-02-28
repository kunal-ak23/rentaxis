package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Data
public class CreateLeaseDTO {
    @NotNull
    private UUID unitId;

    @NotNull
    private UUID renterId;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    @NotNull
    @Min(0)
    private BigDecimal rentAmount;

    @NotNull
    @Min(0)
    private BigDecimal depositAmount;

    private String ejariNumber;

    @Min(1)
    private Integer paymentTerms;
}
