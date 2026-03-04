package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PaymentSummaryDTO {
    private int totalPayments;
    private int pendingCount;
    private int collectedCount;
    private int depositedCount;
    private int clearedCount;
    private int bouncedCount;
    private int overdueCount;
    private BigDecimal totalAmount;
    private BigDecimal pendingAmount;
    private BigDecimal collectedAmount;
    private BigDecimal clearedAmount;
    private BigDecimal overdueAmount;
}
