package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.VoucherPaymentMethod;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A supplier payment run (finance-ops spec §2): many vendors paid on one date
 * from one payment account. DRAFT holds no reservation; posting writes one BPV
 * per vendor, all or nothing, and makes it POSTED. A DRAFT can be CANCELLED.
 */
@Entity
@Table(name = "payment_runs")
@Getter
@Setter
public class PaymentRun extends BaseTenantEntity {

    public enum Status { DRAFT, POSTED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "run_number", nullable = false, length = 40) private String runNumber;
    @Column(name = "payment_date", nullable = false) private LocalDate paymentDate;
    @Column(name = "payment_account_id", nullable = false) private UUID paymentAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private VoucherPaymentMethod method;

    @Column(name = "cheque_date") private LocalDate chequeDate;
    @Column(name = "first_cheque_number", length = 50) private String firstChequeNumber;
    @Column(columnDefinition = "text") private String narration;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.DRAFT;

    @Column(name = "created_by") private UUID createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at") private Instant updatedAt;
    @Column(name = "posted_by") private UUID postedBy;
    @Column(name = "posted_at") private Instant postedAt;
    @Column(name = "cancelled_at") private Instant cancelledAt;
}
