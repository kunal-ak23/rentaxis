package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.api.dto.lease.LeaseLineDTO;
import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentMethod;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
public class LeaseDTO {
    private UUID id;
    private UUID unitId;
    private UUID renterId;
    private String unitIdentifier;
    private String renterName;
    private LocalDate startDate;
    private LocalDate endDate;
    private LeaseStatus status;
    /** Derived from the RENT lines — see {@code Lease}. */
    private BigDecimal rentAmount;
    /** Derived from the DEPOSIT lines — see {@code Lease}. */
    private BigDecimal depositAmount;
    private String ejariNumber;
    private Integer paymentTerms;
    private InstallmentDistribution installmentDistribution;
    private PaymentMethod paymentMethod;
    private PaymentMethod depositPaymentMethod;
    private String paymentReferenceNumber;
    private UUID propertyId;
    private String propertyName;
    private String propertyCode;
    private boolean hasContract;
    private Long contractNumber;
    /** "GLA_B1/681" when the property has a code, else the bare number. */
    private String displayContractNumber;
    private LocalDate agreementDate;
    private Boolean rentVatApplicable;

    // ---- contract header ----
    private LocalDate contractDate;
    private Integer totalDays;
    private Integer gracePeriodDays;
    private LocalDate firstDueDate;

    // ---- renewal chain ----
    private UUID renewedFromLeaseId;
    private UUID chainId;

    // ---- posting ----
    private UUID receivableAccountId;
    private UUID incomeAccountId;
    private UUID postingJournalId;
    private Instant postedAt;

    /** Σ of every line's net — what the contract is worth in total. */
    private BigDecimal contractValue;

    private List<LeaseLineDTO> lines;
}
