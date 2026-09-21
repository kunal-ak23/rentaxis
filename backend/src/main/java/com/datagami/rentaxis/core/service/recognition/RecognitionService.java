package com.datagami.rentaxis.core.service.recognition;

import com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.RecognitionEntry;
import com.datagami.rentaxis.domain.entity.RentSegment;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;
import com.datagami.rentaxis.domain.entity.enums.SegmentStatus;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RecognitionEntryRepository;
import com.datagami.rentaxis.domain.repository.RentSegmentRepository;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Per-day rent recognition (spec §8): the schedule a lease's rent is earned on,
 * and the journals that earn it.
 *
 * <p>A tenancy contract is not thirteen invoices. The renter pays in four
 * cheques, the landlord's books have to show income in the month it was earned,
 * and "a twelfth a month" is wrong on both counts for a term that starts on the
 * 24th. So the {@code TCO} parks the whole contract value in <em>advance
 * rent</em> — a liability — and this module releases it to income day by day, a
 * calendar month at a time.</p>
 *
 * <p><b>The schedule is written when the lease posts, not as the year goes
 * on.</b> Every row exists as {@code PLANNED} from the first minute, which is
 * what lets the lease page print next September's figure, lets a month-end close
 * preview what it is about to do, and lets a lease backdated to last year catch
 * up in one run.</p>
 *
 * <p><b>Every number here comes from {@link ProrationEngine}.</b> There is no
 * second place in the codebase that divides an amount by a day count; a segment
 * stores the rate the engine gave it, and truncation later reads that stored
 * rate rather than recomputing one.</p>
 *
 * <p><b>Transactions.</b> Every repository touch happens inside one, because
 * {@code TenantAspect} enables the Hibernate tenant filter only inside one and a
 * call made outside would see every tenant's rows. {@link #runTo} is the
 * exception that proves it: the method itself is deliberately <em>not</em>
 * transactional, and instead reads its candidates in one short read-only
 * transaction and posts each entry through {@link RecognitionPoster} in a
 * transaction of that entry's own — so at no point is a long-lived transaction
 * holding a connection while a second one is taken out beside it.</p>
 */
@Service
public class RecognitionService {

    private static final Logger log = LoggerFactory.getLogger(RecognitionService.class);

    /** Segments that still describe the contract, so a line that has one needs no new one. */
    private static final Set<SegmentStatus> LIVE_SEGMENTS = EnumSet.of(SegmentStatus.ACTIVE, SegmentStatus.TRUNCATED);

    private final RentSegmentRepository segments;
    private final RecognitionEntryRepository entries;
    private final LeaseRepository leases;
    private final LeaseLineRepository leaseLines;
    private final JournalEntryRepository journals;
    private final TenantFiscalSettingsRepository fiscalSettings;
    private final PostingService postingService;
    private final RecognitionPoster poster;

    /**
     * Short read-only transactions for the one method that must not hold a long
     * one. Built here rather than injected so its read-only flag is this class's
     * own choice and not whatever the shared bean happens to carry.
     */
    private final TransactionTemplate readTx;

    public RecognitionService(RentSegmentRepository segments,
                              RecognitionEntryRepository entries,
                              LeaseRepository leases,
                              LeaseLineRepository leaseLines,
                              JournalEntryRepository journals,
                              TenantFiscalSettingsRepository fiscalSettings,
                              PostingService postingService,
                              RecognitionPoster poster,
                              PlatformTransactionManager transactionManager) {
        this.segments = segments;
        this.entries = entries;
        this.leases = leases;
        this.leaseLines = leaseLines;
        this.journals = journals;
        this.fiscalSettings = fiscalSettings;
        this.postingService = postingService;
        this.poster = poster;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    /**
     * What a run would do, or did.
     *
     * <p><b>A preview does not claim to have posted anything.</b> {@code posted} is
     * the number of rows this call wrote to the ledger, so it is {@code 0} for a
     * preview however many rows the preview found; {@code wouldPost} is the count
     * the month-end screen shows next to the button, and the two are equal on a
     * real run. The earlier shape reported a preview as "3 posted", which is the
     * one sentence an accountant must not be told twice.</p>
     *
     * <p><b>Skipped is not failed.</b> A row inside a closed period was never
     * attempted: it is not a mapping gap for somebody to chase, it is the period
     * lock doing its job, and it will post itself the moment the month is
     * reopened. It travels in {@link #skippedLockedEntries} with the lock date
     * that explains it, and {@link #errors} is left to mean exactly one thing —
     * the ledger refused this row and a human has to look at it.</p>
     *
     * @param preview               what the caller asked for, echoed so a response read
     *                              on its own is unambiguous
     * @param posted                rows written to the ledger; always 0 on a preview
     * @param wouldPost             rows that would post; equal to {@code posted} on a real run
     * @param amount                Σ of {@link #entries}, at 2 dp
     * @param entries               the rows posted, or on a preview the rows that would be
     * @param skippedLocked         size of {@link #skippedLockedEntries}, for a caller that
     *                              only wants the headline
     * @param skippedLockedEntries  rows left PLANNED because their period is closed
     * @param booksLockedThrough    the lock that skipped them, or null when nothing is locked
     * @param errors                rows the ledger refused, one message each
     */
    public record RecognitionRunResult(boolean preview, int posted, int wouldPost, BigDecimal amount,
                                       List<RecognitionEntryDTO> entries,
                                       int skippedLocked, List<RecognitionEntryDTO> skippedLockedEntries,
                                       LocalDate booksLockedThrough,
                                       List<String> errors) {
    }

    // ------------------------------------------------------------------
    // reading
    // ------------------------------------------------------------------

    /** The lease's whole schedule, every status, oldest period first. */
    @Transactional(readOnly = true)
    public List<RecognitionEntryDTO> scheduleFor(UUID leaseId) {
        return toDtos(entries.findByLease_IdOrderByPeriodStartAsc(leaseId));
    }

    /** Everything still waiting to be recognised as of {@code to}, oldest first. */
    @Transactional(readOnly = true)
    public List<RecognitionEntryDTO> pending(LocalDate to) {
        return toDtos(plannedThrough(to));
    }

    // ------------------------------------------------------------------
    // building
    // ------------------------------------------------------------------

    /**
     * Cut a segment and its schedule for every {@code RENT} line of the lease
     * that does not already have one (spec §8.1).
     *
     * <p>Only RENT-<em>behaviour</em> lines: an admin fee is earned when it is
     * charged and a deposit is never earned at all, so neither has anything to
     * recognise. A line with nothing left after its discount is skipped rather
     * than given a zero segment — the day rate of nothing is nothing, and a row
     * of zeroes on the schedule tab is noise a reader has to dismiss.</p>
     *
     * <p>The "does not already have one" test is what makes this safe to call
     * twice, and is what {@link #rebuildAfterAmend} relies on: it cancels the old
     * segments first, which is precisely what makes them no longer count.</p>
     */
    @Transactional
    public void buildForLease(UUID leaseId) {
        Lease lease = lease(leaseId);
        build(lease, leaseLines.findByLease_IdOrderBySeqNoAsc(leaseId));
    }

    /**
     * The same, for the lines an extension just appended (spec §8.5): a new
     * segment for the extension's window, and not a finger laid on the original
     * term's rows.
     *
     * <p>Driven by ids rather than by "the lines added since", because after a
     * second extension there is no ordering that would tell them apart — which is
     * why {@code LeaseExtendedEvent} carries them.</p>
     */
    @Transactional
    public void appendForExtension(UUID leaseId, List<UUID> lineIds) {
        if (lineIds == null || lineIds.isEmpty()) return;
        Lease lease = lease(leaseId);
        List<LeaseLine> lines = leaseLines.findByLease_IdOrderBySeqNoAsc(leaseId).stream()
                .filter(l -> lineIds.contains(l.getId()))
                .toList();
        build(lease, lines);
    }

    /**
     * The lease's lines were restated, so its schedule is too (spec §8.5).
     *
     * <p>What the ledger has already seen is reversed, not deleted: a posted
     * month is income somebody has closed a period on, and the correction for it
     * is a mirror entry dated the day the amendment happened. What was merely
     * planned is cancelled. The segments are then retired wholesale and cut
     * again from the new lines.</p>
     *
     * <p><b>Every RENT line, not just the original term's.</b> An amendment
     * reposts the whole lease under one fresh {@code TCO} — including an
     * extension's lines, whose own TCO it also reverses — so rebuilding only the
     * base term would leave the extension's rent charged in the ledger and never
     * recognised.</p>
     */
    @Transactional
    public void rebuildAfterAmend(UUID leaseId, LocalDate reversalDate) {
        Lease lease = lease(leaseId);
        LocalDate on = reversalDate == null ? LocalDate.now() : reversalDate;

        for (RecognitionEntry entry : entries.findByLease_IdOrderByPeriodStartAsc(leaseId)) {
            if (entry.getStatus() == RecognitionStatus.POSTED && entry.getJournalId() != null) {
                postingService.reverse(entry.getJournalId(), on, "Lease amended");
                entry.setStatus(RecognitionStatus.REVERSED);
                entries.save(entry);
            } else if (entry.getStatus() == RecognitionStatus.PLANNED) {
                entry.setStatus(RecognitionStatus.CANCELLED);
                entries.save(entry);
            }
        }
        for (RentSegment segment : segments.findByLease_IdOrderByFromDateAsc(leaseId)) {
            if (LIVE_SEGMENTS.contains(segment.getStatus())) {
                segment.setStatus(SegmentStatus.CANCELLED);
                segments.save(segment);
            }
        }
        build(lease, leaseLines.findByLease_IdOrderBySeqNoAsc(leaseId));
    }

    // ------------------------------------------------------------------
    // termination (spec §8.5, §9.1)
    // ------------------------------------------------------------------

    /**
     * What a termination at {@code t} does — or would do — to a lease's rent
     * recognition.
     *
     * @param earnedThrough   Σ over the lease's live RENT segments of
     *                        {@code ProrationEngine.earnedThrough(t)} — the single
     *                        source of truth for what the tenancy is worth up to
     *                        and including {@code t}
     * @param recognisedSoFar Σ of the lease's {@code POSTED} entries as they stand.
     *                        Before a termination it is what the ledger has already
     *                        taken to income; after one it equals
     *                        {@code earnedThrough}, which is the whole point
     * @param unearned        Σ {@code (segment.amount − earnedThrough)} — the
     *                        liability the {@code TCR} hands back
     * @param deferrals       the unearned amount split by the account the
     *                        {@code TCO} actually deferred into, one entry per
     *                        segment that has anything left. The debit has to face
     *                        the same leaf the credit went to; see
     *                        {@code RecognitionPoster}'s class note
     */
    public record TerminationRecognition(BigDecimal earnedThrough, BigDecimal recognisedSoFar,
                                         BigDecimal unearned, List<UnearnedDeferral> deferrals) {
    }

    /** One segment's worth of unearned rent, and the liability leaf it sits in. */
    public record UnearnedDeferral(PostingRequest.AccountRef account, BigDecimal amount) {
    }

    /**
     * The same arithmetic {@link #truncateForTermination} performs, with nothing
     * written — what the termination screen shows before the accountant commits.
     */
    @Transactional(readOnly = true)
    public TerminationRecognition previewTermination(UUID leaseId, LocalDate t) {
        Lease lease = lease(leaseId);
        return summarise(lease, liveSegments(leaseId), t);
    }

    /**
     * Cut the lease's recognition off at {@code t} (spec §8.5, last bullet).
     *
     * <p>Per ACTIVE segment, and in this order:</p>
     * <ul>
     *   <li>a segment that starts after {@code t} never began: {@code CANCELLED},
     *       with every planned row cancelled and every posted row reversed;</li>
     *   <li>a segment that already ended by {@code t} is untouched — there is
     *       nothing to truncate and nothing unearned;</li>
     *   <li>otherwise rows after {@code t} are cancelled or reversed, the row
     *       <em>containing</em> {@code t} is re-cut to end on it, and the segment
     *       becomes {@code TRUNCATED} with {@code to_date = t}.</li>
     * </ul>
     *
     * <p><b>The row containing {@code t} is compared on amount, not only on
     * date.</b> A termination effective on a calendar month-end looks like it
     * should leave that month alone — and usually it does — but the cut amount is
     * {@code earnedThrough(t) − Σ earlier rows}, and accumulated rounding across
     * the earlier rows can put it one fil away from the row that was posted (the
     * client's own fixture does exactly this at 2027-01-31: 4,331.50 against a
     * posted 4,331.51). Leaving the row because its dates match would leave the
     * lease's recognised total one fil away from what it earned, permanently. So a
     * POSTED row is reversed and replaced whenever <em>either</em> its period or
     * its amount differs, and left alone only when both are identical.</p>
     *
     * <p><b>A replacement is a new row, not a re-used one.</b> The original stays
     * {@code REVERSED} pointing at the {@code CIL} an auditor can still see, and
     * the replacement gets a {@code CIL} of its own — which is what lets changeset
     * 86 make "at most one CIL per recognition entry" a unique index rather than
     * only a row lock. The two share {@code (segment_id, period_start)}, so the old
     * row's {@code REVERSED} update is flushed before the new row is inserted:
     * Hibernate orders all inserts before all updates within one flush, and the
     * partial unique index would see two live rows for the period.</p>
     *
     * <p><b>The replacement posts in this transaction</b>
     * ({@code RecognitionPoster.postJoining}, not {@code post}). A termination is
     * all-or-nothing: a {@code REQUIRES_NEW} repost would commit a {@code CIL} that
     * survives the caller rolling the rest of the termination back.</p>
     *
     * @return the same summary {@link #previewTermination} gives, computed
     *         <em>before</em> anything is mutated — the unearned figure is
     *         {@code segment.amount − earnedThrough(t)} over the segment's original
     *         window, which truncating it would erase.
     */
    @Transactional
    public TerminationRecognition truncateForTermination(UUID leaseId, LocalDate t) {
        Lease lease = lease(leaseId);
        List<RentSegment> live = liveSegments(leaseId);
        TerminationRecognition summary = summarise(lease, live, t);

        for (RentSegment segment : live) {
            if (segment.getFromDate().isAfter(t)) {
                cancelWholeSegment(segment, t);
            } else if (!segment.getToDate().isAfter(t)) {
                // The segment ran its course before the termination took effect.
                // Nothing to re-slice, nothing unearned, no status change: it is not
                // "truncated", it simply finished.
                log.debug("Segment {} ended {} on or before termination date {}; left as is",
                        segment.getId(), segment.getToDate(), t);
            } else {
                truncateSegment(segment, t);
            }
        }
        return summary;
    }

    /** Only ACTIVE: a TRUNCATED segment has already been cut, and a CANCELLED one is history. */
    private List<RentSegment> liveSegments(UUID leaseId) {
        return segments.findByLease_IdAndStatusInOrderByFromDateAsc(leaseId, EnumSet.of(SegmentStatus.ACTIVE));
    }

    private TerminationRecognition summarise(Lease lease, List<RentSegment> live, LocalDate t) {
        BigDecimal earned = BigDecimal.ZERO;
        BigDecimal unearned = BigDecimal.ZERO;
        List<UnearnedDeferral> deferrals = new ArrayList<>();
        for (RentSegment segment : live) {
            BigDecimal segmentEarned = ProrationEngine.earnedThrough(
                    segment.getAmount(), segment.getFromDate(), segment.getToDate(), t);
            BigDecimal segmentUnearned = segment.getAmount().subtract(segmentEarned);
            earned = earned.add(segmentEarned);
            unearned = unearned.add(segmentUnearned);
            if (segmentUnearned.signum() > 0) {
                deferrals.add(new UnearnedDeferral(poster.deferralOf(segment, lease), segmentUnearned));
            }
        }
        BigDecimal recognised = entries
                .findByLease_IdAndStatusInOrderByPeriodStartAsc(lease.getId(), EnumSet.of(RecognitionStatus.POSTED))
                .stream().map(RecognitionEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TerminationRecognition(
                earned.setScale(2, RoundingMode.HALF_UP),
                recognised.setScale(2, RoundingMode.HALF_UP),
                unearned.setScale(2, RoundingMode.HALF_UP),
                List.copyOf(deferrals));
    }

    private void cancelWholeSegment(RentSegment segment, LocalDate t) {
        for (RecognitionEntry entry : entries.findBySegment_IdOrderByPeriodStartAsc(segment.getId())) {
            retire(entry, t);
        }
        segment.setStatus(SegmentStatus.CANCELLED);
        segments.save(segment);
    }

    private void truncateSegment(RentSegment segment, LocalDate t) {
        // Built from the rows that are actually on the schedule rather than
        // re-sliced from the segment: these are the amounts the ledger has seen, and
        // the cut amount is defined against them.
        List<RecognitionEntry> live = entries.findBySegment_IdOrderByPeriodStartAsc(segment.getId()).stream()
                .filter(e -> e.getStatus() == RecognitionStatus.PLANNED || e.getStatus() == RecognitionStatus.POSTED)
                .toList();
        if (live.isEmpty()) return;

        List<ProrationEngine.Slice> slices = live.stream()
                .map(e -> new ProrationEngine.Slice(e.getPeriodStart(), e.getPeriodEnd(), e.getDays(), e.getAmount()))
                .toList();
        List<ProrationEngine.Slice> kept = ProrationEngine.truncate(slices, segment.getDayRate(), t);
        int cutIndex = kept.size() - 1;

        for (int i = cutIndex + 1; i < live.size(); i++) {
            retire(live.get(i), t);
        }
        recut(live.get(cutIndex), kept.get(cutIndex), segment, t);

        segment.setStatus(SegmentStatus.TRUNCATED);
        segment.setToDate(t);
        segment.setDays(ProrationEngine.daysInclusive(segment.getFromDate(), t));
        // amount and day_rate are left alone on purpose. The rate is what the
        // earlier months were worth and re-deriving it from the shortened window
        // would restate them; the amount is the contract value the unearned
        // reversal was computed against, and Σ the segment's live POSTED rows is
        // the earned half of exactly that number.
        segments.save(segment);
    }

    /** A row wholly after {@code t}: cancelled if it was only planned, reversed if the ledger saw it. */
    private void retire(RecognitionEntry entry, LocalDate t) {
        if (entry.getStatus() == RecognitionStatus.POSTED && entry.getJournalId() != null) {
            postingService.reverse(entry.getJournalId(), t, "Lease terminated " + t);
            entry.setStatus(RecognitionStatus.REVERSED);
            entries.save(entry);
        } else if (entry.getStatus() == RecognitionStatus.PLANNED) {
            entry.setStatus(RecognitionStatus.CANCELLED);
            entries.save(entry);
        }
    }

    /** The row containing {@code t}, re-cut to end on it. See the method note on {@link #truncateForTermination}. */
    private void recut(RecognitionEntry entry, ProrationEngine.Slice cut, RentSegment segment, LocalDate t) {
        if (entry.getStatus() == RecognitionStatus.PLANNED) {
            entry.setPeriodEnd(cut.periodEnd());
            entry.setDays(cut.days());
            entry.setAmount(cut.amount());
            entries.save(entry);
            return;
        }
        boolean unchanged = entry.getPeriodEnd().isEqual(cut.periodEnd())
                && entry.getAmount().compareTo(cut.amount()) == 0;
        if (unchanged) {
            return;
        }
        postingService.reverse(entry.getJournalId(), t, "Lease terminated " + t);
        entry.setStatus(RecognitionStatus.REVERSED);
        entries.save(entry);
        // Flushed before the replacement is inserted: they share
        // (segment_id, period_start), and within one flush Hibernate runs every
        // insert before every update, so the partial unique index would see the
        // replacement arrive while this row was still POSTED.
        entries.flush();

        RecognitionEntry replacement = new RecognitionEntry();
        replacement.setTenantId(entry.getTenantId());
        replacement.setLease(entry.getLease());
        replacement.setSegment(segment);
        replacement.setPeriodStart(cut.periodStart());
        replacement.setPeriodEnd(cut.periodEnd());
        replacement.setDays(cut.days());
        replacement.setAmount(cut.amount());
        replacement.setStatus(RecognitionStatus.PLANNED);
        replacement = entries.saveAndFlush(replacement);
        poster.postJoining(replacement.getId());
    }

    // ------------------------------------------------------------------
    // running
    // ------------------------------------------------------------------

    /**
     * Post every planned row whose period has ended by {@code to} (spec §8.4).
     *
     * <p>Rows in a closed period are reported and left {@code PLANNED}: the books
     * are shut for a reason, and forcing income into a month somebody has already
     * signed off is worse than leaving it for the accountant to decide about.
     * They are <em>not</em> errors — they come back under
     * {@code skippedLockedEntries}, not {@code errors} — and the run does not stop
     * on them.</p>
     *
     * <p>Each row posts through {@link RecognitionPoster} in its own
     * transaction. One lease with a retired income account costs that lease its
     * month and nothing else — a whole night's recognition rolling back because
     * of a single mapping gap is how a month-end close turns into an incident.</p>
     *
     * <p><b>No transaction is held across the loop.</b> The candidates are read in
     * one short read-only transaction and the loop then works on detached rows,
     * so the only transaction open at any moment is the one entry's own. Holding
     * an outer transaction while each entry opens a {@code REQUIRES_NEW} one means
     * <em>two</em> pooled connections per run for its whole duration — a tenant
     * month-end with thousands of rows would keep a write transaction idle for
     * minutes, and N concurrent tenant runs would each wait on an N+1th
     * connection. Nothing in the loop needs the outer transaction: it writes
     * nothing, and the poster re-enables the tenant filter in its own.</p>
     *
     * @param preview answer what would happen and write nothing
     */
    public RecognitionRunResult runTo(LocalDate to, boolean preview) {
        Candidates plan = candidates(to);

        List<RecognitionEntryDTO> done = new ArrayList<>();
        List<RecognitionEntryDTO> locked = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (RecognitionEntryDTO row : plan.rows()) {
            if (plan.lockedThrough() != null && !row.periodEnd().isAfter(plan.lockedThrough())) {
                locked.add(row);
                continue;
            }
            if (preview) {
                done.add(row);
                total = total.add(row.amount());
                continue;
            }
            try {
                done.add(poster.post(row.id()));
                total = total.add(row.amount());
            } catch (RuntimeException e) {
                // The entry's own transaction rolled back; there is no other one to
                // take down with it, which is the whole point of the separate bean.
                log.warn("Recognition entry {} ({}–{}) could not be posted: {}",
                        row.id(), row.periodStart(), row.periodEnd(), e.getMessage());
                errors.add("Entry " + row.periodStart() + "–" + row.periodEnd() + ": " + e.getMessage());
            }
        }
        return new RecognitionRunResult(preview, preview ? 0 : done.size(), done.size(),
                total.setScale(2, RoundingMode.HALF_UP), done,
                locked.size(), locked, plan.lockedThrough(), errors);
    }

    /** What a run has to decide about, read once and detached. */
    private record Candidates(List<RecognitionEntryDTO> rows, LocalDate lockedThrough) {
    }

    /**
     * The candidate rows and the period lock, in one short read-only transaction.
     *
     * <p>A {@code TransactionTemplate} rather than a {@code @Transactional} method
     * on this class: {@link #runTo} is deliberately <em>not</em> transactional, and
     * calling a transactional sibling from it would go through the object rather
     * than the proxy and run with no transaction at all — which is also no tenant
     * filter, since {@code TenantAspect} only enables it inside one.</p>
     */
    private Candidates candidates(LocalDate to) {
        return readTx.execute(status -> new Candidates(toDtos(plannedThrough(to)), booksLockedThrough()));
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * One segment and its month-by-month schedule per recognisable line.
     *
     * <p>A RENT line's window is its own {@code periodStart}/{@code periodEnd}
     * when it has them — an extension's line covers the extension, not the whole
     * tenancy — and the lease's term otherwise.</p>
     */
    private void build(Lease lease, List<LeaseLine> lines) {
        for (LeaseLine line : lines) {
            if (line.getChargeType() == null || line.getChargeType().getBehaviour() != ChargeBehaviour.RENT) continue;
            BigDecimal net = line.getNetAmount() == null ? BigDecimal.ZERO : line.getNetAmount();
            if (net.signum() <= 0) continue;
            if (segments.existsByLeaseLineIdAndStatusIn(line.getId(), LIVE_SEGMENTS)) continue;

            LocalDate from = line.getPeriodStart() != null ? line.getPeriodStart() : lease.getStartDate();
            LocalDate to = line.getPeriodEnd() != null ? line.getPeriodEnd() : lease.getEndDate();
            // Refused, not skipped. The listener's own contract is that a contract
            // whose income cannot be scheduled is a refusal the accountant sees when
            // they press Post; skipping would park the rent in ADVANCE_RENT forever
            // with no schedule and nothing to show for it.
            if (from == null || to == null || to.isBefore(from)) {
                throw new BusinessRuleViolationException("Line " + line.getSeqNo()
                        + " charges rent but has no usable period to recognise it over (" + from + " – " + to + ").");
            }

            RentSegment segment = new RentSegment();
            segment.setTenantId(lease.getTenantId());
            segment.setLease(lease);
            segment.setLeaseLineId(line.getId());
            segment.setFromDate(from);
            segment.setToDate(to);
            segment.setAmount(net);
            segment.setDays(ProrationEngine.daysInclusive(from, to));
            segment.setDayRate(ProrationEngine.dayRate(net, from, to));
            segment.setStatus(SegmentStatus.ACTIVE);
            segment = segments.save(segment);

            for (ProrationEngine.Slice slice : ProrationEngine.slice(net, from, to)) {
                RecognitionEntry entry = new RecognitionEntry();
                entry.setTenantId(lease.getTenantId());
                entry.setLease(lease);
                entry.setSegment(segment);
                entry.setPeriodStart(slice.periodStart());
                entry.setPeriodEnd(slice.periodEnd());
                entry.setDays(slice.days());
                entry.setAmount(slice.amount());
                entry.setStatus(RecognitionStatus.PLANNED);
                entries.save(entry);
            }
        }
    }

    private List<RecognitionEntry> plannedThrough(LocalDate to) {
        return entries.findByTenantIdAndStatusAndPeriodEndLessThanEqualOrderByPeriodEndAsc(
                requireTenant(), RecognitionStatus.PLANNED, to);
    }

    /**
     * The tenant the run is for. A job that forgot to set the context must not get
     * an empty candidate list and a cheerful "0 posted" — that is a whole night of
     * recognition silently not happening.
     */
    private static UUID requireTenant() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) throw new IllegalStateException("No tenant in context");
        return tenantId;
    }

    /**
     * The period lock, read straight from the repository rather than through
     * {@code TenantFiscalSettingsService.get()}, which creates the settings row
     * on first access — a write this must not perform from a read-only path.
     */
    private LocalDate booksLockedThrough() {
        return fiscalSettings.findById(requireTenant())
                .map(TenantFiscalSettings::getBooksLockedThrough).orElse(null);
    }

    private Lease lease(UUID leaseId) {
        return leases.findByIdScopedToTenant(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
    }

    // ------------------------------------------------------------------
    // mapping
    // ------------------------------------------------------------------

    /**
     * Entry numbers for the whole page in one query, rather than one per row.
     *
     * <p>A {@link java.util.HashMap} rather than {@code Map.of()}: most rows of a
     * fresh schedule have no journal at all, and an immutable map throws on a null
     * key rather than answering "nothing".</p>
     */
    private List<RecognitionEntryDTO> toDtos(List<RecognitionEntry> rows) {
        List<UUID> journalIds = rows.stream()
                .map(RecognitionEntry::getJournalId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> numbers = new HashMap<>();
        if (!journalIds.isEmpty()) {
            journals.findAllById(journalIds)
                    .forEach(j -> numbers.put(j.getId(), j.getEntryNumber()));
        }
        return rows.stream().map(r -> toDto(r, r.getJournalId() == null ? null : numbers.get(r.getJournalId()))).toList();
    }

    private static RecognitionEntryDTO toDto(RecognitionEntry r, String journalNumber) {
        return new RecognitionEntryDTO(
                r.getId(),
                idOf(r.getLease(), Lease::getId),
                idOf(r.getSegment(), RentSegment::getId),
                r.getPeriodStart(),
                r.getPeriodEnd(),
                r.getDays(),
                r.getAmount(),
                r.getStatus(),
                r.getJournalId(),
                journalNumber,
                r.getPostedAt());
    }

    /** {@code getId()} on a lazy proxy is answered from the foreign key, without a select. */
    private static <T> UUID idOf(T entity, Function<T, UUID> id) {
        return entity == null ? null : id.apply(entity);
    }
}
