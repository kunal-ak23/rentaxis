package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
public class PaymentScheduleDTO {
    private UUID id;
    private UUID leaseId;
    private UUID unitId;
    private UUID propertyId;
    private String unitIdentifier;
    private String renterName;
    private String propertyName;
    private Integer installmentNumber;
    private LocalDate dueDate;
    private BigDecimal amount;
    private PaymentStatus status;
    private String chequeNumber;
    private String bankName;
    private String payerName;
    private LocalDate chequeDate;
    private Instant statusChangedAt;
    private String notes;
    private UUID replacedById;
    private String purposeLabel;
    private Boolean isBookingDeposit;
    /** Per-row payment method — CHEQUE / BANK_TRANSFER / ONLINE / CASH. */
    private String paymentMethod;
}
