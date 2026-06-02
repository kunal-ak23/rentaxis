package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "payment_schedules")
@Getter
@Setter
public class PaymentSchedule extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id", nullable = false)
    private Unit unit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @Column(name = "installment_number", nullable = false)
    private Integer installmentNumber;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false)
    private BigDecimal amount;

    /**
     * Authoritative VAT amount baked into {@link #amount} at generation time, in
     * the same currency. Recorded per-component so the credit-leg VAT stamped at
     * clear time matches exactly what was billed (rent VAT is inclusive,
     * per-installment charge VAT is additive). Zero for rows that carry no VAT
     * (e.g. the refundable security deposit, booking deposits).
     */
    @Column(name = "vat_amount", nullable = false)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "cheque_number", length = 255)
    private String chequeNumber;

    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Column(name = "payer_name", length = 200)
    private String payerName;

    @Column(name = "cheque_date")
    private LocalDate chequeDate;

    @Column(name = "cheque_image_url", length = 500)
    private String chequeImageUrl;

    @Column(name = "cheque_image_blob_path", length = 500)
    private String chequeImageBlobPath;

    @Column(name = "cheque_image_uploaded_at")
    private OffsetDateTime chequeImageUploadedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cheque_failure_reason", length = 30)
    private ChequeFailureReason failureReason;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_by_id")
    private PaymentSchedule replacedBy;

    @Column(name = "payment_method", length = 20)
    private String paymentMethod = "CHEQUE";

    @Column(name = "purpose_label", length = 120)
    private String purposeLabel;

    @Column(name = "is_booking_deposit", nullable = false)
    private boolean isBookingDeposit = false;

    @Column(name = "is_security_deposit", nullable = false)
    private boolean isSecurityDeposit = false;

    @Column(name = "is_charge", nullable = false)
    private boolean isCharge = false;
}
