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
