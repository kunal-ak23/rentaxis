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
import jakarta.persistence.Version;
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

    /**
     * The output VAT this instalment collects — part of {@link #amount}, not on top
     * of it (spec 2026-09-24 §1). Σ over a lease's rows equals the contract's VAT
     * exactly; the row's tax point moves this much from deferred to output VAT.
     */
    @Column(name = "vat_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    /** The net value {@link #vatAmount} is charged on — VAT201 box 1 without a ÷ 0.05. */
    @Column(name = "vat_taxable_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal vatTaxableAmount = BigDecimal.ZERO;

    @Column(length = 255)
    private String narration;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChequeMode mode = ChequeMode.PDC;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChequeStatus status = ChequeStatus.DRAFT;

    /**
     * What this row should become when its import batch is bulk-posted (spec §10.3,
     * changeset 88). The cut-over contract import writes every cheque as
     * {@code DRAFT} and parks the spreadsheet's status here; bulk post then
     * <em>replays</em> the transitions through {@code ChequeService} so each one
     * writes its own PDR/CRT/CBR, instead of stamping {@link #status} and leaving
     * the ledger with no journal to explain it.
     *
     * <p>Null on every cheque that was not imported. The database narrows it to
     * REGISTERED / DEPOSITED / CLEARED / BOUNCED ({@code ck_cheques_imported_status})
     * — the states the replay knows how to reach from DRAFT; a REPLACED or
     * CANCELLED parked here would be a row bulk post silently skipped. It is typed
     * {@link ChequeStatus} rather than given an enum of its own because it holds
     * exactly that vocabulary and is compared against {@link #status}.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "imported_status", length = 16)
    private ChequeStatus importedStatus;

    /**
     * The dates the spreadsheet says the imported status happened on, kept apart
     * from {@link #depositedAt} / {@link #clearedAt} / {@link #bouncedAt} on purpose
     * (changeset 88).
     *
     * <p>These are an <em>instruction</em> to the bulk post — "bank this on the 24th,
     * clear it on the 25th" — while the lifecycle columns are the <em>record</em> of
     * what the register did, written by {@code ChequeService} as each transition
     * posts its journal. Keeping them in one pair of columns would mean a DRAFT row
     * claiming a deposit that has no journal behind it, and would lose the
     * spreadsheet's dates the first time the batch was reversed: reverting a lease
     * to a clean DRAFT clears the lifecycle columns by design, which is why
     * {@code importedStatus} is excluded from that erasure and why its dates have to
     * be excluded with it. A reversed batch can then be re-posted, and the second
     * post files the same journals on the same days as the first.</p>
     *
     * <p>{@code importedDepositedOn} is set for a post-dated cheque whose imported
     * status is DEPOSITED, CLEARED or BOUNCED — all three pass through the bank —
     * and left null for a cash or transfer receipt, which is received straight to
     * CLEARED and never banked.</p>
     */
    @Column(name = "imported_deposited_on")
    private LocalDate importedDepositedOn;

    /** @see #importedDepositedOn */
    @Column(name = "imported_cleared_on")
    private LocalDate importedClearedOn;

    /** @see #importedDepositedOn */
    @Column(name = "imported_bounced_on")
    private LocalDate importedBouncedOn;

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

    /**
     * The belt under the lease row lock.
     *
     * <p>Every writer of these rows takes that lock — {@code LeasePostingService.post},
     * {@code ChequeService}'s transitions, and (since the final fix wave)
     * {@code ChequeGenerationService}'s draft-grid writers. This is what stops a
     * future writer that forgets it from flushing a stale read over a registered
     * instrument: the grid save that lost the race is refused at commit instead of
     * quietly resetting {@code status} and {@code pdr_journal_id} on a row the
     * ledger already points at.</p>
     */
    @Version
    private Long version;
}
