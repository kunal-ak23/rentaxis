package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.SettlementStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "lease_settlements")
@Getter
@Setter
public class LeaseSettlement extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "lease_id", nullable = false)
    private UUID leaseId;

    @Column(name = "deposit_amount", nullable = false)
    private BigDecimal depositAmount;

    @Column(name = "total_deductions", nullable = false)
    private BigDecimal totalDeductions;

    @Column(name = "refund_amount", nullable = false)
    private BigDecimal refundAmount;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "settled_by")
    private UUID settledBy;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementStatus status = SettlementStatus.DRAFT;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @Override
    public void onPrePersist() {
        super.onPrePersist();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void onPreUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
