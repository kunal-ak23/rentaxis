package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.ChargeFrequency;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lease_charges")
@Getter
@Setter
public class LeaseCharge extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "vat_applicable", nullable = false)
    private boolean vatApplicable = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChargeFrequency frequency = ChargeFrequency.ONE_TIME;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
