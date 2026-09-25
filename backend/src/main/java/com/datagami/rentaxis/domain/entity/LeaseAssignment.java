package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * F14-39: a lease handed to another renter on the same unit — a death, a company
 * novation. DRAFT until posted; the post moves the outgoing renter's sub-ledger
 * balances on the lease to the incoming renter in one journal.
 */
@Entity
@Table(name = "lease_assignments")
@Getter
@Setter
public class LeaseAssignment extends BaseTenantEntity {

    public static final String DRAFT = "DRAFT";
    public static final String POSTED = "POSTED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "lease_id", nullable = false)
    private UUID leaseId;

    @Column(name = "from_renter_id", nullable = false)
    private UUID fromRenterId;

    @Column(name = "to_renter_id", nullable = false)
    private UUID toRenterId;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "take_over_overdue", nullable = false)
    private boolean takeOverOverdue;

    @Column(name = "status", nullable = false, length = 12)
    private String status = DRAFT;

    @Column(name = "journal_id")
    private UUID journalId;

    @Column(name = "journal_number", length = 40)
    private String journalNumber;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "posted_by")
    private UUID postedBy;

    @Column(name = "posted_at")
    private Instant postedAt;
}
