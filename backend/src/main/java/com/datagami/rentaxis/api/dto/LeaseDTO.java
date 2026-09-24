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
    /**
     * The contract document the renter is being asked to accept — it changes every
     * time the contract is regenerated. Accept sends it back so a stale page cannot
     * accept a version the renter never opened (#79, PR #344 review M5).
     */
    private UUID contractDocumentId;
    private Long contractNumber;
    /** "GLA_B1/681" when the property has a code, else the bare number. */
    private String displayContractNumber;
    /**
     * The contract number this tenancy carried in the system it was migrated from
     * ("TLP7/681"), null for a lease created here. Read-only: it is written by the
     * cut-over contract import and there is no request body that sets it.
     */
    private String externalContractRef;
    private LocalDate agreementDate;
    private Boolean rentVatApplicable;

    // ---- contract header ----
    private LocalDate contractDate;
    private Integer totalDays;
    private Integer gracePeriodDays;
    private LocalDate firstDueDate;

    /**
     * When the renter accepted in the portal. Acceptance no longer activates the
     * lease, so this is how the portal knows not to offer Accept twice while the
     * accountant is still to post it.
     */
    private Instant renterAcceptedAt;

    // ---- renewal chain ----
    private UUID renewedFromLeaseId;
    private UUID chainId;

    // ---- posting ----
    private UUID receivableAccountId;
    private UUID incomeAccountId;
    private UUID postingJournalId;
    private Instant postedAt;

    // ---- termination (spec §9.1) ----
    /**
     * The date the contract was terminated at, not the day it was recorded — the
     * day rent stopped being earned and every termination journal is dated.
     */
    private LocalDate terminatedOn;
    /** The {@code TCR} that reversed the unearned rent, when there was any. */
    private UUID terminationJournalId;
    private String terminationNotes;
    /** #27: when notice was given, by whom, and the move-out date it names. */
    private LocalDate noticeDate;
    private com.datagami.rentaxis.domain.entity.enums.NoticeParty noticeGivenBy;
    private LocalDate intendedMoveOutDate;

    /** Σ of every line's net — what the contract is worth in total. */
    private BigDecimal contractValue;

    private List<LeaseLineDTO> lines;
}
