package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.DeductionCategory;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class SaveSettlementDTO {
    private String notes;
    private List<DeductionItemDTO> deductions;

    @Getter
    @Setter
    public static class DeductionItemDTO {
        private UUID id; // optional — if set, updates existing deduction (preserving attachments)
        private DeductionCategory category; // nullable for additions
        private String description;
        @NotNull
        private BigDecimal amount;
        private boolean autoCalculated;
        private String type; // "DEDUCTION" (default) or "ADDITION"
        private String additionCategory; // required when type = ADDITION
    }
}
