package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.VatTaxPointKind;
import com.datagami.rentaxis.domain.entity.enums.VatTaxPointStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One instalment's VAT tax point, or a termination's settling adjustment (spec
 * 2026-09-24 §1).
 *
 * <p>Mirrors {@link RecognitionEntry}: the schedule is written when the lease posts
 * and a nightly job turns each PLANNED row whose date has arrived into a {@code VTP}
 * journal ({@code Dr OUTPUT_VAT_DEFERRED / Cr OUTPUT_VAT}). A table rather than a
 * column on the cheque because a termination adjustment has no cheque, the VAT
 * return reads it directly, and cancelling or re-pointing a point leaves the
 * register row untouched.</p>
 *
 * <p>Ids rather than associations: every reader wants the numbers, and the job
 * resolves the lease once per point anyway. {@code ux_vtp_cheque_live} keeps one
 * live point per instalment.</p>
 */
@Entity
@Table(name = "vat_tax_points")
@Getter
@Setter
public class VatTaxPoint extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "lease_id", nullable = false)
    private UUID leaseId;

    /** The instalment; null for a termination adjustment. */
    @Column(name = "cheque_id")
    private UUID chequeId;

    @Column(name = "property_id")
    private UUID propertyId;

    @Column(name = "unit_id")
    private UUID unitId;

    @Column(name = "renter_id")
    private UUID renterId;

    @Column(length = 30)
    private String emirate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private VatTaxPointKind kind = VatTaxPointKind.INSTALMENT;

    /** min(cheque date, receipt date) for an instalment; T for a termination adjustment. */
    @Column(name = "tax_point_date", nullable = false)
    private LocalDate taxPointDate;

    /** Signed: a termination adjustment that credits VAT back is negative. */
    @Column(name = "taxable_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal taxableAmount = BigDecimal.ZERO;

    /** Signed, like {@link #taxableAmount}. */
    @Column(name = "vat_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private VatTaxPointStatus status = VatTaxPointStatus.PLANNED;

    /** The VTP (or, for a termination adjustment, the TCR) this point became. */
    @Column(name = "journal_id")
    private UUID journalId;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
