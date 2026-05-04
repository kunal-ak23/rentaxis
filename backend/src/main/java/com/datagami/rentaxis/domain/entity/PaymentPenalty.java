package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payment_penalties")
@Getter
@Setter
public class PaymentPenalty extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "payment_schedule_id", nullable = false)
    private UUID paymentScheduleId;

    @Column(name = "lease_id", nullable = false)
    private UUID leaseId;

    @Column(name = "penalty_amount", nullable = false)
    private BigDecimal penaltyAmount;

    @Column(name = "days_overdue", nullable = false)
    private Integer daysOverdue;

    @Column(name = "penalty_type", length = 30)
    private String penaltyType;

    @Column(name = "penalty_rate")
    private BigDecimal penaltyRate;

    @Column(name = "grace_period_days")
    private Integer gracePeriodDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "cheque_failure_reason", length = 30)
    private ChequeFailureReason failureReason;

    @Column(name = "fine_grace_days")
    private Integer fineGraceDays;

    @Column(name = "fine_per_day_rate")
    private BigDecimal finePerDayRate;

    @Column(name = "cleared_at")
    private LocalDateTime clearedAt;

    @Column(name = "waived", nullable = false)
    private boolean waived = false;

    @Column(name = "waived_by")
    private UUID waivedBy;

    @Column(name = "waived_reason")
    private String waivedReason;

    @Column(name = "waived_at")
    private LocalDateTime waivedAt;

    @Column(name = "last_calculated_at")
    private LocalDateTime lastCalculatedAt;

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
