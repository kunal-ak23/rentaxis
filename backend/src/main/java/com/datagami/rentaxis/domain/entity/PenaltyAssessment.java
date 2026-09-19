package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.PenaltyAssessmentStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One penalty the system or a finance user thinks should be charged, and the
 * record of what finance decided about it (spec §7.3).
 *
 * <p>This row exists because a fine must not post itself. The rule engine
 * proposes; the accountant approves or waives. Only an approval writes the
 * {@code PEN} journal and puts a collection row on the register, which is why
 * {@code journalId} and {@code collectionCheque} are null for every status other
 * than {@code APPROVED} and {@code REVERSED}.</p>
 *
 * <p>{@code proposedBy} null means SYSTEM — a rule fired with no user behind it.
 * It is a plain nullable column rather than a sentinel user, because there is no
 * such user and inventing one would show up in every audit list as a person.</p>
 *
 * <p>{@code journalId} is an unmapped UUID for the same reason the cheque's three
 * journal ids are: a list of assessments must not drag a journal and its lines
 * into memory per row. The collection cheque <em>is</em> mapped, because the list
 * screen shows whether the penalty has actually been collected and that is one
 * join, not a ledger walk.</p>
 */
@Entity
@Table(name = "penalty_assessments")
@Getter
@Setter
public class PenaltyAssessment extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    /** The returned instrument this was raised over, where there was one. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cheque_id")
    private Cheque cheque;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "renter_id", nullable = false)
    private Renter renter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PenaltyReason reason;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private PenaltyAssessmentStatus status = PenaltyAssessmentStatus.PROPOSED;

    /** Null means the rule engine raised it with no user behind the decision. */
    @Column(name = "proposed_by")
    private UUID proposedBy;

    @Column(name = "proposed_at", nullable = false)
    private Instant proposedAt = Instant.now();

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    /** The {@code PEN} entry, or after a reversal still the entry that was reversed. */
    @Column(name = "journal_id")
    private UUID journalId;

    /** The register row approval created so the money follows the normal receipt path. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collection_cheque_id")
    private Cheque collectionCheque;

    /** Why it was waived, or why an approval was reversed. */
    @Column(name = "resolution_note", columnDefinition = "text")
    private String resolutionNote;
}
