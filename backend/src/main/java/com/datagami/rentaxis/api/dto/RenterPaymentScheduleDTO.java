package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class RenterPaymentScheduleDTO {
    private UUID id;
    private int installmentNumber;
    private String dueDate;
    private BigDecimal amount;
    private String status;
    private String propertyName;
    private String unitIdentifier;
    private String renterName;
    private UUID leaseId;
    private BigDecimal penaltyAmount;
    private BigDecimal totalPayable;
    private int daysOverdue;
    private int gracePeriodDays;
    private String paymentMethod;
}
