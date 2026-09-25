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
     * The line this segment recognises — <b>a plain id, not an association</b>
     * (changeset 86).
     *
     * <p>Amending a posted lease replaces its lines wholesale, and the segments cut
     * from them have to survive: a POSTED recognition entry explains a {@code CIL}
     * that is still in the ledger, and this segment is where that entry's
     * arithmetic came from. So {@code fk_rs_line} is gone and the column stays
     * required — a retired segment goes on recording which line it was cut from,
     * which is the audit link an amendment should leave behind, and no segment can
     * ever be line-less.</p>
     *
     * <p>The price is that the id may name a row that no longer exists. Nothing
     * dereferences it blindly: {@code RecognitionPoster} reads it back through
     * {@code LeaseLineRepository} and treats "gone" the same way it treats a line
     * with no credit account. Mapping it as a {@code @ManyToOne} would have made
     * every such read a {@code EntityNotFoundException} on first touch.</p>
     */
    @Column(name = "lease_line_id", nullable = false)
    private UUID leaseLineId;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    /**
     * What this segment is worth over the window it actually covers.
     *
     * <p>On a live segment that is the line's net — after discount, before VAT,
     * because VAT is never income. On a {@code TRUNCATED} one it is the rent
     * <em>earned</em> up to the termination date, and {@link #originalAmount}
     * holds the contract figure it was cut from. The four window fields
     * ({@code amount}, {@code fromDate}, {@code toDate}, {@code days}) therefore
     * always describe one consistent period, whatever the status — which is what
     * lets {@code ProrationEngine.earnedThrough(amount, fromDate, toDate, …)}
     * answer correctly for any row rather than only for a live one.</p>
     */
    @Column(nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    /** {@code (toDate − fromDate) + 1}; {@code ck_rs_dates} enforces it in the database too. */
    @Column(nullable = false)
    private int days;

    /**
     * The contract rate, and the authoritative one — <b>never re-derive a rate
     * from a truncated row</b>.
     *
     * <p>{@code amount / days} equals this on a live segment. On a
     * {@code TRUNCATED} one it does not, quite: {@code amount} is the earned total
     * rounded once to two places, so dividing it back out drifts in the sixth
     * decimal (20,260.27 / 145 = 139.726000 against the stored 139.726027).
     * <em>This</em> field is what the months before the cut were worth, it is what
     * {@code ProrationEngine.truncate} was given when the cut was computed, and
     * re-deriving one from the shortened window would restate them.</p>
     */
    @Column(name = "day_rate", nullable = false, precision = 18, scale = 6)
    private BigDecimal dayRate = BigDecimal.ZERO;

    /**
     * What the line charged before a termination cut this segment short — null on
     * every segment that was never truncated.
     *
     * <p>The pair with {@link #originalToDate} is the contract record: the
     * unearned rent a termination reverses is exactly
     * {@code originalAmount − amount}, and without them a truncated row could no
     * longer say what it was cut <em>from</em>. They are deliberately not used in
     * any arithmetic about the earned half; nothing should have to know the
     * difference between a truncated segment and a short one.</p>
     */
    @Column(name = "original_amount")
    private BigDecimal originalAmount;

    /** The line's own end date before truncation; null unless this segment was cut short. */
    @Column(name = "original_to_date")
    private LocalDate originalToDate;

    /**
     * F14-18: for a periodic fee's segment, the liability its {@code TCO} deferred
     * into ({@code UNEARNED_CHARGES}, resolved when the lease posted) and the income
     * account each month is released to (the fee line's own). Null on a RENT
     * segment, whose accounts come from the line and the lease as before.
     */
    @Column(name = "deferral_account_id")
    private UUID deferralAccountId;

    @Column(name = "income_account_id")
    private UUID incomeAccountId;

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
