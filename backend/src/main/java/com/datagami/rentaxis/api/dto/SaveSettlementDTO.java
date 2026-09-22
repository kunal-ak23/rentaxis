package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.DeductionCategory;
import com.datagami.rentaxis.domain.entity.enums.LineItemType;
import jakarta.validation.constraints.DecimalMin;
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
        @DecimalMin(value = "0.00", inclusive = true)
        private BigDecimal amount;
        private boolean autoCalculated;
        private LineItemType type; // enum: DEDUCTION (default) or ADDITION
        private String additionCategory; // required when type = ADDITION

        /**
         * Override the leaf this line posts to. Omit to let the category resolve
         * it (spec §9.2). A deduction must name an active INCOME leaf of this
         * tenant, an addition an INCOME or EXPENSE one; anything else is a 400.
         */
        private UUID accountId;
    }
}
