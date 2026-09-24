package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A cheque we wrote to a supplier (finance-ops spec §2, changeset 112).
 *
 * <p>A post-dated one — a BPV paid by CHEQUE with a cheque date after the
 * voucher's date — is held in {@code PDC_PAYABLE} while ISSUED. PRESENTED: a
 * {@code BPC} ({@code bpcJournalId}) moved it to the bank on {@code presentedOn}.
 * CANCELLED: its BPV was reversed (or, for a cut-over cheque with no BPV, a
 * mirror journal {@code cancelJournalId} moved it back to the vendor).</p>
 */
@Entity
@Table(name = "issued_cheques")
@Getter
@Setter
public class IssuedCheque extends BaseTenantEntity {

    public enum Status { ISSUED, PRESENTED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "voucher_id") private UUID voucherId;
    @Column(name = "vendor_id", nullable = false) private UUID vendorId;
    /** The bank GL leaf the cheque is drawn on. */
    @Column(name = "bank_account_id", nullable = false) private UUID bankAccountId;
    @Column(name = "cheque_number", nullable = false, length = 50) private String chequeNumber;
    @Column(name = "cheque_date", nullable = false) private LocalDate chequeDate;
    @Column(nullable = false, precision = 14, scale = 2) private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.ISSUED;

    @Column(name = "presented_on") private LocalDate presentedOn;
    @Column(name = "bpc_journal_id") private UUID bpcJournalId;
    @Column(name = "cancelled_on") private LocalDate cancelledOn;
    @Column(name = "cancel_reason", columnDefinition = "text") private String cancelReason;
    @Column(name = "cancel_journal_id") private UUID cancelJournalId;
    @Column(nullable = false) private boolean opening;

    @Column(name = "created_by") private UUID createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at") private Instant updatedAt;
}
