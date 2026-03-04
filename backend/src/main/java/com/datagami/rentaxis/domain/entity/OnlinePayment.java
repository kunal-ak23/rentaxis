package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "online_payments")
@Getter
@Setter
public class OnlinePayment extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_schedule_id", nullable = false)
    private PaymentSchedule paymentSchedule;

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
