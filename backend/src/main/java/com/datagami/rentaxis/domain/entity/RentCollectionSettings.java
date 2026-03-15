package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.PenaltyType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rent_collection_settings")
@Getter
@Setter
public class RentCollectionSettings extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false, unique = true)
    private Property property;

    @Column(name = "due_day_of_month")
    private Integer dueDayOfMonth;

    @Column(name = "grace_period_days")
    private Integer gracePeriodDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "penalty_type", length = 20)
    private PenaltyType penaltyType;

    @Column(name = "penalty_amount")
    private BigDecimal penaltyAmount;

    @Column(name = "online_payment_enabled")
    private Boolean onlinePaymentEnabled;

    @Column(name = "payment_reminder_days", length = 50)
    private String paymentReminderDays = "7,3,1";

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
