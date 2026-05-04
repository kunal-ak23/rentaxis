package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class RentCollectionSettingsDTO {
    private UUID id;
    private UUID propertyId;
    private Integer dueDayOfMonth;
    private Integer gracePeriodDays;
    private String penaltyType;
    private BigDecimal penaltyAmount;
    private Boolean onlinePaymentEnabled;

    // Per-property fine overrides (null = use org-level defaults)
    private java.math.BigDecimal fineBounceAmount;
    private java.math.BigDecimal fineSignatureMismatchAmount;
    private java.math.BigDecimal fineAccountClosedAmount;
    private Integer fineGraceDays;
    private java.math.BigDecimal finePerDayRate;
}
