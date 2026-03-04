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
}
