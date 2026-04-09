package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.DeductionCategory;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class SaveSettlementDTO {
    private String notes;
    private List<DeductionItemDTO> deductions;

    @Getter
    @Setter
    public static class DeductionItemDTO {
        @NotNull
        private DeductionCategory category;
        private String description;
        @NotNull
        private BigDecimal amount;
        private boolean autoCalculated;
    }
}
