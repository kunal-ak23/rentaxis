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
 * <p><b>{@code cheque} is the row being paid.</b> Every payment created from
 * Task 10 onwards names a cheque and nothing else: the renter pays an instalment
 * on the register, the capture clears that row with a {@code CRT}, and a receipt
 * is the cleared row rendered as a PDF.</p>
 *
 * <p>{@code paymentSchedule} is the v1 shape, kept mapped and nullable only until
 * Task 12 drops {@code payment_schedules} with the column. Nothing writes it any
 * more; a row with a schedule and no cheque is a pre-v2 record.</p>
 */
@Entity
@Table(name = "online_payments")
@Getter
@Setter
public class OnlinePayment extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    /** The register row this session is paying. Null only on pre-v2 rows. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cheque_id")
    private Cheque cheque;

    /** v1 only; dropped with {@code payment_schedules} in Task 12. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_schedule_id")
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
