package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.SettlementStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "lease_settlements")
@Getter
@Setter
public class LeaseSettlement extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "lease_id", nullable = false)
    private UUID leaseId;

    /**
     * Legacy name for {@link #depositsHeld}: what the landlord is holding, read
     * off the ledger. Kept because it is {@code NOT NULL} and the renter portal
     * still reads it; written with the same figure.
     */
    @Column(name = "deposit_amount", nullable = false)
    private BigDecimal depositAmount;

    // ------------------------------------------------------------------
    // the statement, snapshotted by finalise (changeset 85, spec §9.2)
    //
    // A draft's statement is recomputed from the ledger on every read, so these
    // are the figures the STL was actually posted against rather than a cache.
    // They stop moving the moment the settlement is FINALIZED, which is the whole
    // point: the journal is immutable and the statement that explains it has to be
    // too, however many cheques clear afterwards.
    // ------------------------------------------------------------------

    /** The date the {@code STL} carries. Null while the settlement is a draft. */
    @Column(name = "settlement_date")
    private LocalDate settlementDate;

    @Column(name = "earned_rent", nullable = false)
    private BigDecimal earnedRent = BigDecimal.ZERO;

    @Column(name = "received_total", nullable = false)
    private BigDecimal receivedTotal = BigDecimal.ZERO;

    /** Debit-positive: +ve the renter owes, −ve the landlord does. */
    @Column(name = "receivable_balance", nullable = false)
    private BigDecimal receivableBalance = BigDecimal.ZERO;

    @Column(name = "deposits_held", nullable = false)
    private BigDecimal depositsHeld = BigDecimal.ZERO;

    @Column(name = "penalties_outstanding", nullable = false)
    private BigDecimal penaltiesOutstanding = BigDecimal.ZERO;

    /**
     * What the renter still owes after the deposit was applied, as a positive
     * number; zero when the settlement refunds. {@link #refundAmount} is the other
     * half of the same figure and exactly one of the two is ever non-zero.
     */
    @Column(name = "balance_due", nullable = false)
    private BigDecimal balanceDue = BigDecimal.ZERO;

    /** The account the refund was paid from; null when nothing was refunded. */
    @Column(name = "refund_bank_account_id")
    private UUID refundBankAccountId;

    /** The {@code STL}. Null on a draft, and null on a finalised settlement that had nothing to post. */
    @Column(name = "journal_id")
    private UUID journalId;

    /** The CASH row raised to collect a balance the deposit could not cover. */
    @Column(name = "collection_cheque_id")
    private UUID collectionChequeId;

    @Column(name = "total_deductions", nullable = false)
    private BigDecimal totalDeductions;

    @Column(name = "total_additions", nullable = false)
    private BigDecimal totalAdditions = BigDecimal.ZERO;

    @Column(name = "refund_amount", nullable = false)
    private BigDecimal refundAmount;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "settled_by")
    private UUID settledBy;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementStatus status = SettlementStatus.DRAFT;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Optimistic lock behind {@code SettlementService}'s row lock (review I-4).
     *
     * <p>The lease-row lock is what serialises save against finalise; this is the
     * backstop for any future path that writes a settlement without taking it. The
     * failure it prevents is silent and expensive — a stale save putting a
     * FINALIZED row back to DRAFT with {@code journal_id} blanked, over an
     * {@code STL} that is already posted and a refund that has already been paid.
     * {@code Lease} and {@code Cheque} carry the same guard for the same reason.</p>
     */
    @Version
    private Long version;

    @PrePersist
    @Override
    public void onPrePersist() {
        super.onPrePersist();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void onPreUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
