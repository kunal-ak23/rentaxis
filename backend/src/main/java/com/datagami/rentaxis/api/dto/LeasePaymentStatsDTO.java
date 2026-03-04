package com.datagami.rentaxis.api.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.UUID;

@Data
public class LeasePaymentStatsDTO {
    private UUID leaseId;
    private int totalPayments;
    private int clearedPayments;
    private int pendingPayments;
    private int overduePayments;
    private BigDecimal totalAmount;
    private BigDecimal clearedAmount;
    private BigDecimal overdueAmount;
}
