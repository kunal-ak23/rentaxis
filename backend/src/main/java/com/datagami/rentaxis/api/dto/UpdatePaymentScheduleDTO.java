package com.datagami.rentaxis.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Bulk update for the editable fields on the payment schedule of a DRAFT or
 * PENDING_SIGNATURE lease. Each row carries the schedule row id plus the
 * fields the admin can adjust before the lease is finalized: due date, amount,
 * payment method, and the cheque/transfer details that depend on the method.
 */
@Data
public class UpdatePaymentScheduleDTO {

    @NotNull
    @Valid
    private List<Row> rows;

    @Data
    public static class Row {
        @NotNull
        private UUID scheduleId;

        @NotNull
        private LocalDate dueDate;

        @NotNull
        @DecimalMin(value = "0.00", inclusive = true)
        private BigDecimal amount;

        /**
         * One of CHEQUE, BANK_TRANSFER, CASH, ONLINE.
         */
        @NotNull
        private String paymentMethod;

        // Required when paymentMethod == CHEQUE; otherwise optional/null.
        private String chequeNumber;

        // For CHEQUE: the cheque date. For BANK_TRANSFER: the transfer date.
        // For CASH: the receipt/payment date. Null is allowed; falls back to dueDate.
        private LocalDate chequeDate;

        // Required when paymentMethod is CHEQUE or BANK_TRANSFER; ignored for CASH.
        private String bankName;

        // Optional cheque image metadata captured by cheque scanner flows.
        private String chequeImageUrl;
        private String chequeImageBlobPath;
        private OffsetDateTime chequeImageUploadedAt;
    }
}
