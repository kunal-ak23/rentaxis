package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.DeductionCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "lease_settlement_deductions")
@Getter
@Setter
public class LeaseSettlementDeduction extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "settlement_id", nullable = false)
    private UUID settlementId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private DeductionCategory category;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "auto_calculated", nullable = false)
    private boolean autoCalculated;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    @Override
    public void onPrePersist() {
        super.onPrePersist();
        this.createdAt = LocalDateTime.now();
    }
}
