package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One gateway session against one register row (spec §9.3).
 *
 * <p><b>{@code cheque} is the row being paid</b>, and the only thing a payment
 * can be about: the renter pays an instalment on the register, the capture clears
 * that row with a {@code CRT}, and a receipt is the cleared row rendered as a PDF.
 * Not nullable — changeset 84 deleted the pre-v2 rows that had a schedule instead
 * (spec D4) and made the column NOT NULL, because a captured payment that settles
 * nothing on the register is one no screen, receipt or journal can read.</p>
 */
@Entity
@Table(name = "online_payments")
@Getter
@Setter
public class OnlinePayment extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    /** The register row this session is paying. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cheque_id", nullable = false)
    private Cheque cheque;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gateway_id", nullable = false)
    private PaymentGateway gateway;

    @Column(name = "gateway_order_id", nullable = false, length = 200)
    private String gatewayOrderId;

    @Column(name = "gateway_payment_id", length = 200)
    private String gatewayPaymentId;

    @Column(name = "gateway_signature", length = 500)
    private String gatewaySignature;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 10)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OnlinePaymentStatus status;

    @Column(name = "penalty_amount")
    private BigDecimal penaltyAmount;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "gateway_response_json", columnDefinition = "TEXT")
    private String gatewayResponseJson;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
