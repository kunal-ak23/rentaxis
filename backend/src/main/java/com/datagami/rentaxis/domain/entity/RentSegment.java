package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.SegmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A stretch of days one {@code RENT} lease line is earned over (spec §8.1).
 *
 * <p>Rent is not earned in twelfths. The client's own example runs 24 Sep 2026 →
 * 23 Sep 2027 — 365 days at 139.726027 a day — and a twelfth of 51,000 is
 * neither what September is worth nor what February is. The segment carries the
 * three numbers everything else is derived from: the amount, the actual
 * inclusive day count, and the rate that divides one by the other, stored at six
 * decimals so a year's worth of slices still sums back to the amount.</p>
 *
 * <p><b>The day rate is stored, not recomputed.</b> Truncating a segment at a
 * termination date must not change what the earlier months were worth, and
 * dividing the truncated amount by the truncated day count would do exactly
 * that. {@code ProrationEngine.truncate} takes this stored rate for that
 * reason.</p>
 *
 * <p>One segment per RENT line: the base term is one, an extension appends a
 * second for its own window, and an amendment cancels the lot and cuts fresh
 * ones. A line with several segments over time is a line whose history the
 * ledger can still be read against.</p>
 */
@Entity
@Table(name = "rent_segments")
@Getter
@Setter
public class RentSegment extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    /**
     * The line this segment recognises. LAZY: the nightly run needs it only to
     * find the account the deferral was credited to, and only for the handful of
     * entries whose period has just ended.
     *
     * <p><b>Nullable, and only ever null on a retired segment</b> (changeset 86).
     * Amending a posted lease replaces its lines wholesale, so a segment that has
     * just been CANCELLED loses the row it was cut from; the database nulls this
     * on delete rather than taking the segment with it, because the segment is
     * what explains the {@code CIL} journals already in the ledger.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_line_id")
    private LeaseLine leaseLine;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    /** The line's net — after discount, before VAT. VAT is never income. */
    @Column(nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    /** {@code (toDate − fromDate) + 1}; {@code ck_rs_dates} enforces it in the database too. */
    @Column(nullable = false)
    private int days;

    @Column(name = "day_rate", nullable = false, precision = 18, scale = 6)
    private BigDecimal dayRate = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private SegmentStatus status = SegmentStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
