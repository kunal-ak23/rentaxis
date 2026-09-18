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
}
