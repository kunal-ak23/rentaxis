package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "landlord_org_fine_settings")
@Getter
@Setter
public class LandlordOrgFineSettings extends BaseTenantEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "landlord_org_id", nullable = false, unique = true)
    private UUID landlordOrgId;

    @Column(name = "fine_bounce_amount", nullable = false)
    private BigDecimal fineBounceAmount;

    @Column(name = "fine_signature_mismatch_amount", nullable = false)
    private BigDecimal fineSignatureMismatchAmount;

    @Column(name = "fine_account_closed_amount", nullable = false)
    private BigDecimal fineAccountClosedAmount;

    @Column(name = "fine_grace_days", nullable = false)
    private Integer fineGraceDays;

    @Column(name = "fine_per_day_rate", nullable = false)
    private BigDecimal finePerDayRate;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
