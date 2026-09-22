package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;
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
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One calendar-month slice of a {@link RentSegment} (spec §8.2) — a month's rent
 * waiting to become income.
 *
 * <p>The whole schedule is written when the lease posts, not month by month as
 * the year goes on. An accountant closing September wants to see what October
 * will recognise, a client signing a contract wants the table on the lease page,
 * and a backdated lease has to catch up in one run rather than needing twelve.
 * The rows exist from day one; the run only turns them into journals.</p>
 *
 * <p>{@code amount} is {@code round(dayRate × days, 2)} for every row but the
 * segment's last, which absorbs the remainder so the rows sum to the segment's
 * amount exactly. {@code uq_re_segment_period} stops the same month being cut
 * twice from one segment.</p>
 */
@Entity
@Table(name = "recognition_entries")
@Getter
@Setter
public class RecognitionEntry extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    /**
     * Denormalised off the segment so the lease's schedule is one query, and so
     * the nightly run can file a journal against the lease without loading the
     * segment and its line.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "segment_id", nullable = false)
    private RentSegment segment;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    /** The row's own period end — and the date its {@code CIL} journal carries (D13). */
    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(nullable = false)
    private int days;

    @Column(nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private RecognitionStatus status = RecognitionStatus.PLANNED;

    /** The {@code CIL} this row became. Null until it is posted. */
    @Column(name = "journal_id")
    private UUID journalId;

    @Column(name = "posted_at")
    private Instant postedAt;
}
