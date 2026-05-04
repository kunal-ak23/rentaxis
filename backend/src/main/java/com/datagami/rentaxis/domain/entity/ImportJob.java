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
