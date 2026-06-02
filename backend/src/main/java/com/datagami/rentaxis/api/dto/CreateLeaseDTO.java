package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Data
public class CreateLeaseDTO {
    @NotNull
    private UUID unitId;

    @NotNull
    private UUID renterId;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    @NotNull
    @Min(0)
    private BigDecimal rentAmount;

    @Min(0)
    private BigDecimal monthlyRent;

    @NotNull
    @Min(0)
    private BigDecimal depositAmount;

    private String ejariNumber;

    @Min(1)
    private Integer paymentTerms;

    private InstallmentDistribution installmentDistribution;

    private String paymentMethod; // CHEQUE or ONLINE

    private String depositPaymentMethod; // CHEQUE or ONLINE

    private String paymentReferenceNumber;

    private LocalDate agreementDate;

    private Boolean rentVatApplicable;

    @jakarta.validation.Valid
    private java.util.List<LeaseChargeDTO> charges;

    private BookingDepositDTO bookingDeposit;

    @lombok.Data
    public static class BookingDepositDTO {
        @NotNull
        @Min(0)
        private BigDecimal amount;
        private String chequeNumber;
        private LocalDate chequeDate;
        private String bankName;
    }
}
