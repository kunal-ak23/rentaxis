package com.datagami.rentaxis.api.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class SettlementPreviewDTO {
    private BigDecimal depositAmount;
    private BigDecimal unpaidRentTotal;
    private BigDecimal penaltyTotal;
    private BigDecimal suggestedRefund; // deposit - unpaidRent - penalties
}
