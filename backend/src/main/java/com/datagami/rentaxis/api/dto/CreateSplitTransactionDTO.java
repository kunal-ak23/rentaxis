package com.datagami.rentaxis.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
public class CreateSplitTransactionDTO {

    @NotNull
    private LocalDate date;

    @NotBlank
    private String description;

    @NotNull
    private UUID accountId;

    private BigDecimal debit;

    private BigDecimal credit;

    private boolean vatApplicable;

    private BigDecimal vatRate;

    private String notes;

    private UUID vendorId;

    private UUID staffId;

    @NotNull
    @Size(min = 2, message = "At least 2 split allocations are required")
    private List<@Valid SplitAllocation> splits;

    @Data
    public static class SplitAllocation {
        private UUID propertyId;
        private UUID unitId;

        @NotNull
        @DecimalMin(value = "0.01", message = "Split amount must be greater than zero")
        private BigDecimal amount;
    }
}
