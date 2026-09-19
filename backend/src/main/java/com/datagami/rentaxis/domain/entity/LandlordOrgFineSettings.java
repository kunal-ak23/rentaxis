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

    /**
     * Returned cheques on one lease before a penalty is proposed (spec §7.3).
     * The client's accountant charges after two or three, not after the first.
     */
    @Column(name = "bounces_before_penalty", nullable = false)
    private Integer bouncesBeforePenalty;

    /** Whether a returned cheque past the threshold raises a proposal by itself. */
    @Column(name = "auto_propose_cheque_return", nullable = false)
    private Boolean autoProposeChequeReturn;

    /** Whether rent cleared after its grace period raises a proposal by itself. */
    @Column(name = "auto_propose_late_payment", nullable = false)
    private Boolean autoProposeLatePayment;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
