package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lease_events")
@Getter
@Setter
public class LeaseEvent extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_state", length = 30)
    private LeaseStatus previousState;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_state", nullable = false, length = 30)
    private LeaseStatus newState;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private UUID createdBy;
}
