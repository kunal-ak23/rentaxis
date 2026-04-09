package com.datagami.rentaxis.api.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class SettlementResponseDTO {
    private UUID id;
    private UUID leaseId;
    private BigDecimal depositAmount;
    private BigDecimal totalDeductions;
    private BigDecimal totalAdditions;
    private BigDecimal refundAmount;
    private String notes;
    private String status;
    private UUID settledBy;
    private String settledByName;
    private LocalDateTime settledAt;
    private LocalDateTime createdAt;
    private List<DeductionDTO> deductions;

    @Getter
    @Setter
    public static class DeductionDTO {
        private UUID id;
        private String category;
        private String description;
        private BigDecimal amount;
        private boolean autoCalculated;
        private String type;
        private String additionCategory;
        private List<DeductionAttachmentDTO> attachments;
    }
}
