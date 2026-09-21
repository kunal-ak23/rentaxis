package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.ImportBatchKind;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * The unit of undo for the cut-over (spec §10.3, changeset 88). Every journal
 * written while importing carries this row's id in
 * {@code journal_entries.import_batch_id}, which is also what exempts those
 * journals — and their reversals — from the period lock
 * ({@code PostingService.post} and {@code .reverse} both test for it), because a
 * cut-over posts into the months the books are closed over by definition.
 */
@Entity
@Table(name = "import_batches")
@Getter
@Setter
public class ImportBatch extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ImportBatchKind kind = ImportBatchKind.CONTRACT_IMPORT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ImportBatchStatus status = ImportBatchStatus.DRAFT;

    /** What an accountant calls this run on the batches list — "September cut-over". */
    @Column(length = 120) private String label;

    /** The {@code import_jobs} row the spreadsheet was uploaded as, when there was one. */
    @Column(name = "import_job_id") private UUID importJobId;

    @Column(name = "leases_imported", nullable = false) private int leasesImported;
    @Column(name = "journals_posted", nullable = false) private int journalsPosted;
    @Column(name = "posted_at") private Instant postedAt;
    @Column(name = "posted_by") private UUID postedBy;
    @Column(name = "reversed_at") private Instant reversedAt;
    @Column(name = "reversed_by") private UUID reversedBy;

    /**
     * When the batch and everything it created were deleted, and by whom. The batch
     * row outlives its own contents on purpose: after a discard it is the only
     * record that the import happened at all.
     */
    @Column(name = "discarded_at") private Instant discardedAt;
    @Column(name = "discarded_by") private UUID discardedBy;

    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
}
