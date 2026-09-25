package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One charged particular on a lease (spec §6.2) — the lease's own invoice line.
 *
 * <p>A lease is a posting document: what it charges for is these rows, not a
 * {@code rent_amount} column plus an ad-hoc bag of {@code lease_charges}.
 * {@code rent_amount} and {@code deposit_amount} survive on {@link Lease} only as
 * read-only mirrors of the RENT / DEPOSIT lines, kept in step by
 * {@code LeaseService.syncDerivedTotals}.</p>
 *
 * <p>{@code creditAccount} is the leaf the line's net amount credits when the
 * lease posts. It is nullable on purpose: a draft may name a charge type whose
 * role the property has no mapping for yet, and the posting guard is what reports
 * that — refusing the draft at line-entry time would make a lease unbuildable
 * because of a chart-of-accounts gap nobody has noticed yet.</p>
 *
 * <p>{@code netAmount = grossAmount − discountAmount} is enforced twice: by
 * {@code LeaseService.applyLines} so the error is a 400 with a sentence, and by
 * {@code ck_lease_lines_net} (changeset 83) so no other writer can bypass it.</p>
 */
@Entity
@Table(name = "lease_lines")
@Getter
@Setter
public class LeaseLine extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    /** 1-based position, the order the lines were entered and are rendered in. */
    @Column(name = "seq_no", nullable = false)
    private int seqNo;

    /**
     * EAGER because every read of a line needs its code, name, role and
     * behaviour — the DTO mapper, the derived totals and the posting rules all
     * dereference it, so a LAZY proxy would only buy an N+1 and a
     * LazyInitializationException outside a transaction.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "charge_type_id", nullable = false)
    private ChargeType chargeType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credit_account_id")
    private Account creditAccount;

    @Column(name = "gross_amount", nullable = false)
    private BigDecimal grossAmount = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    /**
     * Spec §4b: the rent-free concession on the contract's RENT line (0 elsewhere).
     * {@code net = gross − discount − rentFree}; derived from the lease's
     * rent-free periods by {@code RentFreeService}.
     */
    @Column(name = "rent_free_amount", nullable = false)
    private BigDecimal rentFreeAmount = BigDecimal.ZERO;

    @Column(name = "net_amount", nullable = false)
    private BigDecimal netAmount = BigDecimal.ZERO;

    @Column(length = 255)
    private String narration;

    @Column(name = "vat_applicable", nullable = false)
    private boolean vatApplicable = false;

    /** Rent lines carry the period they cover; a one-off fee normally does not. */
    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    /** The addendum that charged this line; null for the contract's own lines and an extension's. */
    @Column(name = "addendum_id")
    private UUID addendumId;

    /**
     * #99 / F15-06: how the books actually treated this line when it was posted —
     * not the charge type's rule today. RENT_LIKE (deferred and earned over the
     * term), ONE_OFF (income when charged; also a periodic fee on a lease posted
     * under the old at-posting rule), PASS_THROUGH (recovered at cost) or NONE (a
     * deposit: a liability, never income). Null while the lease is a draft; stamped
     * when the lease posts, and on any line written to a posted lease afterwards.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "posted_recognition", length = 16)
    private com.datagami.rentaxis.domain.entity.enums.ChargeRecognition postedRecognition;

    /** What posting this line on {@code lease} does with it (F15-06); null without a charge type. */
    public static com.datagami.rentaxis.domain.entity.enums.ChargeRecognition postingRecognition(
            Lease lease, ChargeType type) {
        if (type == null || type.getBehaviour() == null) return null;
        switch (type.getBehaviour()) {
            case DEPOSIT: return com.datagami.rentaxis.domain.entity.enums.ChargeRecognition.NONE;
            case RENT: return com.datagami.rentaxis.domain.entity.enums.ChargeRecognition.RENT_LIKE;
            default: break;
        }
        var r = type.getRecognition();
        boolean overTerm = lease == null
                || lease.getFeeTiming() == com.datagami.rentaxis.domain.entity.enums.FeeTiming.OVER_TERM;
        if (r == com.datagami.rentaxis.domain.entity.enums.ChargeRecognition.RENT_LIKE && !overTerm) {
            // Posted under the at-posting rule: the TCO took it to income.
            return com.datagami.rentaxis.domain.entity.enums.ChargeRecognition.ONE_OFF;
        }
        return r;
    }

    /** Stamps {@link #postedRecognition} with what posting does with this line. */
    public void snapshotRecognition() {
        postedRecognition = postingRecognition(lease, chargeType);
    }

    @PrePersist
    @PreUpdate
    void snapshotWhenPosted() {
        if (postedRecognition == null && lease != null && lease.getPostedAt() != null) snapshotRecognition();
    }
}
