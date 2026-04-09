package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.MeetingType;
import com.datagami.rentaxis.domain.entity.enums.MeetingStatus;
import com.datagami.rentaxis.domain.entity.enums.MeetingPurpose;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "meetings")
@Getter
@Setter
public class Meeting extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private MeetingType type;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private MeetingStatus status = MeetingStatus.REQUESTED;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private MeetingPurpose purpose;

    @Column(length = 255)
    private String title;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "slot_start", nullable = false)
    private Instant slotStart;

    @Column(name = "slot_end", nullable = false)
    private Instant slotEnd;

    @Column(name = "host_user_id", nullable = false)
    private UUID hostUserId;

    @Column(name = "requester_user_id", nullable = false)
    private UUID requesterUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id")
    private Lease lease;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id")
    private Property property;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id")
    private Unit unit;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onPreUpdate() {
        this.updatedAt = Instant.now();
    }
}
