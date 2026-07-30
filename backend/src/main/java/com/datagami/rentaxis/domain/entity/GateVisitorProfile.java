package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.GateVisitorType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "gate_visitor_profiles")
@Getter
@Setter
public class GateVisitorProfile extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Column(name = "phone_normalized", nullable = false, length = 32)
    private String phoneNormalized;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "visitor_type", nullable = false, length = 32)
    private GateVisitorType visitorType;

    @Column(name = "photo_url", length = 1024)
    private String photoUrl;

    @Column(name = "photo_blob_path", length = 512)
    private String photoBlobPath;

    @Column(name = "last_vehicle_number", length = 32)
    private String lastVehicleNumber;

    @Column(name = "last_unit_id")
    private UUID lastUnitId;

    @Column(name = "last_visited_at")
    private Instant lastVisitedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onPreUpdate() {
        updatedAt = Instant.now();
    }
}
