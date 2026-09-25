package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.InterestStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "unit_listing_interests")
@Getter
@Setter
public class UnitListingInterest extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Column(name = "renter_user_id", nullable = false)
    private UUID renterUserId;

    @Column(columnDefinition = "text")
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InterestStatus status = InterestStatus.ACTIVE;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    @PrePersist
    @Override
    public void onPrePersist() {
        super.onPrePersist();
        if (createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    /** F14-51: the draft lease this interest became. */
    @Column(name = "lease_id")
    private UUID leaseId;
}
