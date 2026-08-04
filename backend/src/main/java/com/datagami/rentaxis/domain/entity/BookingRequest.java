package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A renter's request for an amenity or a parking spot. Exactly one of
 * {@code amenityId}/{@code parkingSpotId} is non-null (DB CHECK enforces it).
 * {@code propertyId} is always derived server-side from the resource.
 */
@Entity
@Table(name = "booking_requests")
@Getter
@Setter
public class BookingRequest extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 20)
    private BookingResourceType resourceType;

    @Column(name = "amenity_id")
    private UUID amenityId;

    @Column(name = "parking_spot_id")
    private UUID parkingSpotId;

    @Column(name = "unit_id", nullable = false)
    private UUID unitId;

    @Column(name = "renter_user_id", nullable = false)
    private UUID renterUserId;

    @Column(columnDefinition = "text")
    private String note;

    @Column(name = "preferred_date")
    private LocalDate preferredDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingRequestStatus status = BookingRequestStatus.PENDING;

    @Column(name = "admin_note", columnDefinition = "text")
    private String adminNote;

    @Column(name = "decided_by_user_id")
    private UUID decidedByUserId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onPreUpdate() {
        this.updatedAt = Instant.now();
    }
}
