package com.datagami.rentaxis.api.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The stored settlement row — the draft as it was saved, or the record of what
 * finalise posted.
 *
 * <p>The live statement is {@code GET /{id}/settlement/preview}
 * ({@code SettlementStatementDTO}); this is the row. On a DRAFT the statement
 * fields carry the figures as of the last save and will move again; on a FINALIZED
 * one they are the figures the {@code STL} was posted against and never move.</p>
 */
@Getter
@Setter
public class SettlementResponseDTO {
    private UUID id;
    private UUID leaseId;
    /** Same figure as {@link #depositsHeld}; kept for the renter portal's existing field name. */
    private BigDecimal depositAmount;
    private BigDecimal totalDeductions;
    private BigDecimal totalAdditions;
    /** What the landlord pays out — {@code max(netRefund, 0)}. */
    private BigDecimal refundAmount;
    private String notes;
    private String status;
    private UUID settledBy;
    private String settledByName;
    private LocalDateTime settledAt;
    private LocalDateTime createdAt;

    // --- the statement (spec §9.2) --------------------------------------
    private LocalDate settlementDate;
    private BigDecimal earnedRent;
    private BigDecimal receivedTotal;
    /** Debit-positive: +ve the renter owes, −ve the landlord does. */
    private BigDecimal receivableBalance;
    private BigDecimal depositsHeld;
    /** APPROVED assessments not yet collected. They sit on the register, not in {@link #receivableBalance}. */
    private BigDecimal penaltiesOutstanding;
    /** What the renter still owes — {@code max(-netRefund, 0)}. */
    private BigDecimal balanceDue;
    private UUID refundBankAccountId;

    // --- what finalise wrote --------------------------------------------
    /** The {@code STL}, or null on a draft. */
    private UUID journalId;
    /** That entry's number, e.g. {@code STL/2027/0004}. */
    private String journalNumber;
    /** The CASH row raised to collect a balance the deposit could not cover. */
    private UUID collectionChequeId;

    private List<DeductionDTO> deductions;

    @Getter
    @Setter
    public static class DeductionDTO {
        private UUID id;
        private String category;
        private String description;
        private BigDecimal amount;
        private boolean autoCalculated;
        private String type;
        private String additionCategory;
        /** The leaf this line posts to: its own override, else the category's default. */
        private UUID accountId;
        private String accountName;
        private List<DeductionAttachmentDTO> attachments;
    }
}
