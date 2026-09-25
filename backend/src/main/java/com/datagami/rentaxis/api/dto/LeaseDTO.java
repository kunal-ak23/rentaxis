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
    /** The original contract's Ejari registration. Never overwritten by an addendum. */
    private String ejariNumber;
    /**
     * F14-33: the Ejari in force now — the latest registered addendum's number,
     * else {@link #ejariNumber}. Computed on read.
     */
    private String currentEjari;
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
    /** TRUE when the grace was set on this lease, FALSE when it came from the property (gap #65). */
    private Boolean gracePeriodOverridden;
    /**
     * When this lease's output VAT is declared (spec 2026-09-24 §1): INSTALMENT on
     * each instalment's tax point, CONTRACT on the contract date (leases posted
     * before the change, and cut-over imports whose VAT PACT already declared).
     */
    private com.datagami.rentaxis.domain.entity.enums.VatTiming vatTiming;
    private LocalDate firstDueDate;

    /**
     * When the renter accepted in the portal. Acceptance no longer activates the
     * lease, so this is how the portal knows not to offer Accept twice while the
     * accountant is still to post it.
     */
    private Instant renterAcceptedAt;

    // ---- renewal chain ----
    private UUID renewedFromLeaseId;
    /** Spec §2: B → A for a unit transfer, the move date, and (on A) the lease it moved to. */
    private UUID transferredFromLeaseId;
    private java.time.LocalDate transferMoveDate;
    private UUID transferredToLeaseId;
    private String transferredToUnit;
    private String transferredToStatus;
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

    /**
     * Spec §4c: on the response to a renewal only — the predecessor's one-off
     * lines (e.g. an admin fee) that were not copied, so nothing is dropped
     * silently. Null elsewhere.
     */
    private List<LeaseLineDTO> skippedOneOffLines;

    /** Spec §4b: the contract's rent-free windows and their concessions; empty when none. */
    private List<com.datagami.rentaxis.api.dto.lease.RentFreePeriodDTO> rentFreePeriods;

    /** Spec §4a: on a renewal, the headline rent it revised and the change in percent; null otherwise. */
    private BigDecimal renewalPreviousRent;
    private BigDecimal renewalChangePercent;
}
