package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
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
import java.time.LocalDate;
import java.util.UUID;

/**
 * One receipt instrument on a lease — a post-dated cheque, or the cash/transfer/
 * online receipt that stands in its place (spec §7). The cheque register is the
 * landlord's collection worklist: what to bank today, what cleared, what bounced.
 *
 * <p>The row carries its own {@code property}, {@code unit} and {@code renter}
 * rather than walking the lease for them, because the register is filtered and
 * grouped by all three and a bounce still has to be attributable after a lease is
 * renewed onto a different unit.</p>
 *
 * <p>The three journal ids are plain UUIDs, not relations: they point at the
 * ledger entries this cheque produced — {@code pdrJournalId} when it was
 * registered (Dr PDC receivable / Cr rent receivable), {@code crtJournalId} when
 * it cleared, {@code cbrJournalId} when it bounced. Keeping them unmapped stops a
 * lazy-loading register query from dragging three journals and their lines per
 * row into memory; a caller that wants the entry asks the ledger for it by id.
 * {@code penaltyAssessmentId} is the same shape for the same reason.</p>
 *
 * <p>{@code replaces}/{@code replacedBy} form the replacement chain: a bounced
 * cheque is never edited in place, it is superseded, so the history of what the
 * renter actually handed over survives.</p>
 *
 * <p>Uniqueness of {@code (lease_id, cheque_number)} for PDCs lives in the
 * database ({@code ux_cheques_lease_number}, changeset 83) rather than in a
 * read-then-write check, so two concurrent registrations cannot both win.</p>
 */
@Entity
@Table(name = "cheques")
@Getter
@Setter
public class Cheque extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id")
    private Unit unit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "renter_id", nullable = false)
    private Renter renter;

    /** Position in the lease's instalment schedule, 1-based. */
    @Column(name = "seq_no", nullable = false)
    private int seqNo;

    /** The date the registering journal is posted on — not the date on the cheque. */
    @Column(name = "posting_date", nullable = false)
    private LocalDate postingDate;

    @Column(name = "cheque_number")
    private String chequeNumber;

    /** The date written on the instrument: when it matures and may be banked. */
    @Column(name = "cheque_date", nullable = false)
    private LocalDate chequeDate;

    @Column(name = "payee_bank", length = 100)
    private String payeeBank;

    @Column(name = "payer_name", length = 200)
    private String payerName;

    /** The account cleared funds land in — the bank or cash account for this receipt. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "debit_account_id")
    private Account debitAccount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(length = 255)
    private String narration;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChequeMode mode = ChequeMode.PDC;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChequeStatus status = ChequeStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 30)
    private ChequeFailureReason failureReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaces_id")
    private Cheque replaces;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_by_id")
    private Cheque replacedBy;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /** Blob path of the scanned image, cleared by the retention purge job. */
    @Column(name = "image_blob_path", length = 500)
    private String imageBlobPath;

    @Column(name = "image_uploaded_at")
    private Instant imageUploadedAt;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "deposited_at")
    private LocalDate depositedAt;

    @Column(name = "cleared_at")
    private LocalDate clearedAt;

    @Column(name = "bounced_at")
    private LocalDate bouncedAt;

    @Column(name = "returned_at")
    private LocalDate returnedAt;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    @Column(name = "pdr_journal_id")
    private UUID pdrJournalId;

    @Column(name = "crt_journal_id")
    private UUID crtJournalId;

    @Column(name = "cbr_journal_id")
    private UUID cbrJournalId;

    @Column(name = "penalty_assessment_id")
    private UUID penaltyAssessmentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
