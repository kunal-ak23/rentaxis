package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "penalty_payments")
@Getter
@Setter
public class PenaltyPayment extends BaseTenantEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "payment_penalty_id", nullable = false)
    private UUID paymentPenaltyId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "payment_method", length = 20, nullable = false)
    private String paymentMethod;       // BANK_TRANSFER / CHEQUE / CASH

    @Column(name = "payment_reference", length = 255)
    private String paymentReference;

    @Column(name = "received_at", nullable = false)
    private LocalDate receivedAt;

    @Column(name = "received_by", nullable = false)
    private UUID receivedBy;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
