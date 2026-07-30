package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "gate_access_policies")
@Getter
@Setter
public class GateAccessPolicy extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    /** Null means the property-wide default; a value is a tower override. */
    @Column(name = "building_id")
    private UUID buildingId;

    @Column(name = "require_unregistered_approval", nullable = false)
    private boolean requireUnregisteredApproval = true;

    @Column(name = "require_registered_approval", nullable = false)
    private boolean requireRegisteredApproval;

    @Column(name = "notify_registered_entry", nullable = false)
    private boolean notifyRegisteredEntry = true;

    @Column(name = "require_fresh_photo", nullable = false)
    private boolean requireFreshPhoto = true;

    @Column(name = "approval_timeout_minutes", nullable = false)
    private int approvalTimeoutMinutes = 15;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onPreUpdate() {
        updatedAt = Instant.now();
    }
}
