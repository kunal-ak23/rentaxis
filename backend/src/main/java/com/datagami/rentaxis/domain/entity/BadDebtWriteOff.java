package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** F14-38: a renter's unrecoverable balance written off (proposed, approved by an admin, reversible). */
@Entity
@Table(name = "bad_debt_write_offs")
@Getter
@Setter
public class BadDebtWriteOff extends BaseTenantEntity {

    public enum Status { PROPOSED, WRITTEN_OFF, REJECTED, REVERSED }

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "lease_id", nullable = false) private UUID leaseId;
    @Column(name = "renter_id") private UUID renterId;
    @Column(name = "property_id") private UUID propertyId;
    @Column(nullable = false, precision = 14, scale = 2) private BigDecimal amount;
    @Column(name = "write_off_date", nullable = false) private LocalDate writeOffDate;
    @Column(nullable = false, length = 500) private String reason;
    /** Comma-separated ids of the register rows the write-off closes. */
    @Column(name = "item_ids", columnDefinition = "text") private String itemIds;
    /** The lease carries VAT: the VAT already declared stays declared (no automatic relief). */
    @Column(name = "vat_lease", nullable = false) private boolean vatLease;
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 12) private Status status = Status.PROPOSED;
    @Column(name = "proposed_by") private UUID proposedBy;
    @Column(name = "proposed_at", nullable = false) private Instant proposedAt = Instant.now();
    @Column(name = "decided_by") private UUID decidedBy;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "decision_note", length = 500) private String decisionNote;
    @Column(name = "journal_id") private UUID journalId;
    @Column(name = "reversal_journal_id") private UUID reversalJournalId;
}
