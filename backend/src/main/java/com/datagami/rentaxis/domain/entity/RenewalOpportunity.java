package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import com.datagami.rentaxis.domain.entity.enums.RenewalOutcome;
import com.datagami.rentaxis.domain.entity.enums.RenewalStage;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "renewal_opportunities")
@Getter
@Setter
public class RenewalOpportunity extends BaseTenantEntity {

    @Id @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RenewalStage stage = RenewalStage.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private RenewalIntent intent;

    @Column(name = "intent_captured_at")
    private Instant intentCapturedAt;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt = Instant.now();

    @Column(name = "closed_at")
    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private RenewalOutcome outcome;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onUpdate() { this.updatedAt = Instant.now(); }
}
