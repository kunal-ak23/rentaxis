package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "import_jobs")
@Getter
@Setter
public class ImportJob extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    private String status; // VALIDATING, VALIDATION_FAILED, PERSISTING, COMPLETED, FAILED

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "properties_created")
    private int propertiesCreated;

    @Column(name = "buildings_created")
    private int buildingsCreated;

    @Column(name = "units_created")
    private int unitsCreated;

    @Column(name = "renters_created")
    private int rentersCreated;

    @Column(name = "leases_created")
    private int leasesCreated;

    @Column(name = "schedules_created")
    private int schedulesCreated;

    /**
     * JSONB payload with two valid shapes — the controller's {@code mapToResult}
     * picks based on the first non-whitespace character:
     *
     * <ul>
     *   <li><b>JSON array</b> — {@code [{"sheet":...,"row":...,"field":...,"message":...}, ...]} —
     *       the historical shape. Used for {@code VALIDATION_FAILED} jobs and any
     *       {@code FAILED} job. Always a {@code List<ImportErrorDTO>}.</li>
     *   <li><b>JSON object</b> — {@code PortfolioImportJobDetailsDTO} — wrapper added
     *       2026-05-02 to carry the bulk-import counters
     *       ({@code chequesFromSheet}, {@code bookingDepositsCreated}) and warnings
     *       alongside any errors. Used for {@code COMPLETED} jobs that have anything
     *       beyond the legacy fields to report. {@code null} when there's nothing
     *       to surface.</li>
     * </ul>
     *
     * Future writers MUST preserve this discriminator (array-vs-object first char) or
     * the controller's parse path needs revisiting.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String errors;

    /**
     * The {@code ImportBatch} this job created, or null for a v1 portfolio import.
     *
     * <p>The column has been there since changeset 88; this is the mapping. It is
     * what takes the web from a finished import job to the batch screen that can
     * post or reverse what the job wrote — the polling response carries it, so the
     * client never has to guess which batch its own upload produced.</p>
     */
    @Column(name = "import_batch_id")
    private UUID importBatchId;

    /**
     * How far a long-running job has got, and how far it has to go.
     *
     * <p>Written by the cut-over bulk post (spec §10.3), which runs on the import
     * executor exactly as an upload does: six hundred contracts is not a request
     * anybody should hold a connection open for. Null on every job that has no
     * meaningful unit to count.</p>
     */
    @Column(name = "processed")
    private Integer processed;

    /** @see #processed */
    @Column(name = "total")
    private Integer total;

    /**
     * The bulk post's own outcome, as JSON.
     *
     * <p>A column of its own rather than a third shape in {@link #errors}: that one
     * carries either a JSON array of {@code ImportErrorDTO} or a
     * {@code PortfolioImportJobDetailsDTO} object, and the portfolio controller
     * picks between them on the first non-whitespace character. A bulk-post result
     * parked there would be read by whichever of those two parsers matched its
     * brace.</p>
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    private String result;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    @Override
    public void onPrePersist() {
        super.onPrePersist();
        if (createdAt == null) createdAt = Instant.now();
    }
}
