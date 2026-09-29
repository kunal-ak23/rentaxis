package com.datagami.rentaxis.core.service.recognition;

import com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.lease.LeaseVat;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.RecognitionEntry;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.RentSegment;
import com.datagami.rentaxis.domain.entity.Unit;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
    private final EntityManager entityManager;

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
                              EntityManager entityManager,
                              PlatformTransactionManager transactionManager) {
        this.segments = segments;
        this.entries = entries;
        this.leases = leases;
        this.leaseLines = leaseLines;
        this.journals = journals;
        this.fiscalSettings = fiscalSettings;
        this.postingService = postingService;
        this.poster = poster;
        this.entityManager = entityManager;
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
     * @param alreadyRecognised     rows another run posted while this one worked (POSTED)
     * @param withdrawnMeanwhile    rows a contract change (amendment, reduction, termination)
     *                              struck out or reversed while this one worked — CANCELLED or
     *                              REVERSED, nothing to recognise; a replacement row is PLANNED
     *                              and the next run takes it (review of R4-B M10)
     */
    public record RecognitionRunResult(boolean preview, int posted, int wouldPost, BigDecimal amount,
                                       List<RecognitionEntryDTO> entries,
                                       int skippedLocked, List<RecognitionEntryDTO> skippedLockedEntries,
                                       LocalDate booksLockedThrough,
                                       List<String> errors,
                                       int alreadyRecognised,
                                       int withdrawnMeanwhile) {
    }

    // ------------------------------------------------------------------
    // reading
    // ------------------------------------------------------------------

    /** The lease's whole schedule, every status, oldest period first. */
    @Transactional(readOnly = true)
    public List<RecognitionEntryDTO> scheduleFor(UUID leaseId) {
        return toDtos(entries.findByLease_IdOrderByPeriodStartAsc(leaseId));
    }

    /** One calendar month of the rent schedule a draft would post (owner request 2026-09-29). */
    public record RentMonth(LocalDate periodStart, LocalDate periodEnd, int days, BigDecimal amount) { }

    /**
     * The month-by-month rent a contract's RENT lines will be recognised as, before
     * anything is posted — for the New Contract wizard's Review step. The same rule
     * {@link #build} plans at posting ({@link ProrationEngine#slice}: day rate = net ÷
     * term days, each month = day rate × its days, the last month absorbs rounding),
     * per RENT line over its own window, summed per calendar month. Reads only; the
     * lease is read tenant-scoped.
     */
    @Transactional(readOnly = true)
    public List<RentMonth> previewRentSchedule(UUID leaseId) {
        Lease lease = lease(leaseId);
        java.util.TreeMap<java.time.YearMonth, RentMonth> months = new java.util.TreeMap<>();
        for (LeaseLine line : leaseLines.findByLease_IdOrderBySeqNoAsc(leaseId)) {
            boolean rent = line.getChargeType() != null && line.getChargeType().getBehaviour() == ChargeBehaviour.RENT;
            BigDecimal net = line.getNetAmount() == null ? BigDecimal.ZERO : line.getNetAmount();
            if (!rent || net.signum() <= 0) continue;
            LocalDate from = line.getPeriodStart() != null ? line.getPeriodStart() : lease.getStartDate();
            LocalDate to = line.getPeriodEnd() != null ? line.getPeriodEnd() : lease.getEndDate();
            if (from == null || to == null || to.isBefore(from)) continue;
            for (ProrationEngine.Slice slice : ProrationEngine.slice(net, from, to)) {
                java.time.YearMonth key = java.time.YearMonth.from(slice.periodStart());
                RentMonth had = months.get(key);
                months.put(key, had == null
                        ? new RentMonth(slice.periodStart(), slice.periodEnd(), slice.days(), slice.amount())
                        : new RentMonth(had.periodStart().isBefore(slice.periodStart()) ? had.periodStart() : slice.periodStart(),
                                had.periodEnd().isAfter(slice.periodEnd()) ? had.periodEnd() : slice.periodEnd(),
                                Math.max(had.days(), slice.days()), had.amount().add(slice.amount())));
            }
        }
        return List.copyOf(months.values());
    }

    /**
     * F14-27: how far recognition is behind. {@code behind} counts PLANNED rows whose
     * period ended before {@code today} and is not inside the period lock — rows the
     * nightly pass should already have posted.
     */
    public record RecognitionBehind(int behind, BigDecimal behindAmount, LocalDate oldestPeriodEnd) { }

    @Transactional(readOnly = true)
    public RecognitionBehind behind(LocalDate today) {
        LocalDate locked = booksLockedThrough();
        List<RecognitionEntry> rows = plannedThrough(today.minusDays(1)).stream()
                .filter(e -> locked == null || e.getPeriodEnd().isAfter(locked)).toList();
        BigDecimal amount = rows.stream().map(RecognitionEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new RecognitionBehind(rows.size(), amount.setScale(2, RoundingMode.HALF_UP),
                rows.isEmpty() ? null : rows.get(0).getPeriodEnd());
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
     *
     * <p><b>Every decision is made under the row's own write lock</b>, exactly as
     * {@link #truncateForTermination}'s is and for the same reason (review I-3). An
     * amendment holds the <em>lease</em> row, and {@link RecognitionPoster} takes no
     * lease lock at all, so the two are not serialised by it: a row read PLANNED
     * while the nightly or a hand-run close is posting it would be set CANCELLED,
     * its UPDATE would wait for the poster's commit and then overwrite the whole row
     * — status CANCELLED, {@code journal_id} blanked — leaving a POSTED {@code CIL}
     * with nothing pointing at it while {@code build()} plans the same month again.
     * Income recognised twice, advance rent over-released, and a trial balance that
     * still balances. Under the lock the same row reads POSTED and is reversed.</p>
     */
    @Transactional
    public void rebuildAfterAmend(UUID leaseId, LocalDate reversalDate) {
        Lease lease = lease(leaseId);
        LocalDate d = reversalDate == null ? LocalDate.now() : reversalDate;
        String reason = "Lease amended " + d;
        // S16-14 (#376 P3-1): on a lease of an acquired building the days before its books
        // start are the previous owner's (released to the vendor at the acquisition) — the
        // amendment's arithmetic starts there, and never moves their days through our income.
        LocalDate own = lease.getUnit() == null || lease.getUnit().getProperty() == null ? null
                : lease.getUnit().getProperty().getBooksStartDate();

        // --- 1. the old schedule keeps what it earned through d − 1 ---------------
        // Nothing the ledger saw before the amendment is touched: months already
        // recognised stay where they were posted (#371 review, amendment-date design).
        // Each live segment is cut at d − 1 the way a credit addendum cuts one
        // (reduceFrom): rows after it reversed (never dated before d), the row holding
        // d re-cut; a segment starting on or after d never began and is cancelled.
        // The segment's accounts are resolved before its line can be read no more.
        Map<List<UUID>, UUID> lineOfKey = new java.util.LinkedHashMap<>();
        Map<List<UUID>, LocalDate> fromOfKey = new java.util.LinkedHashMap<>();
        Map<UUID, List<UUID>> keyOfSegment = new HashMap<>();
        // S16-15: which keys are a periodic fee's — only those catch-ups name their
        // accounts; a rent catch-up stays a rent row (no income account = rent).
        java.util.Set<List<UUID>> feeKeys = new java.util.HashSet<>();
        for (RentSegment seg : segments.findByLease_IdOrderByFromDateAsc(leaseId)) {
            if (seg.getStatus() == SegmentStatus.CANCELLED) continue;
            List<UUID> key = List.of(poster.deferralAccountId(seg, lease), poster.incomeAccountId(seg, lease));
            keyOfSegment.put(seg.getId(), key);
            lineOfKey.putIfAbsent(key, seg.getLeaseLineId());
            if (seg.getIncomeAccountId() != null) feeKeys.add(key);
            fromOfKey.merge(key, own != null && seg.getFromDate().isBefore(own) ? own : seg.getFromDate(),
                    (x, y) -> x.isBefore(y) ? x : y);
            if (seg.getStatus() != SegmentStatus.ACTIVE) continue;
            if (!seg.getFromDate().isBefore(d)) {
                cancelWholeSegment(seg, d, reason);
            } else if (!seg.getToDate().isBefore(d)) {
                truncateSegment(seg, d.minusDays(1), d, reason);
                // Back to ACTIVE although it now ends at d − 1 (as reduceFrom does): a later
                // amendment must still find it and count what it kept, and a termination never
                // cuts it — a termination is refused before the amendment date (#372 P2-1), and
                // from d on this window has simply run its course.
                seg.setStatus(SegmentStatus.ACTIVE);
                segments.save(seg);
            }
        }
        // What the old schedule has recognised, or will — per (deferral, income) pair:
        // POSTED rows (earlier catch-ups included), and PLANNED rows the runner can
        // still post. A PLANNED row inside the period lock never will be, so it is
        // cancelled and left to the catch-up below.
        LocalDate locked = booksLockedThrough();
        Map<List<UUID>, BigDecimal> kept = new java.util.LinkedHashMap<>();
        Map<List<UUID>, BigDecimal> folded = new java.util.LinkedHashMap<>();
        Map<List<UUID>, Integer> foldedMonths = new java.util.LinkedHashMap<>();
        Map<List<UUID>, BigDecimal> theirsKept = new java.util.LinkedHashMap<>();
        for (RecognitionEntry e : entries.findByLease_IdOrderByPeriodStartAsc(leaseId)) {
            List<UUID> key = keyOfSegment.get(e.getSegment().getId());
            if (key == null) continue;
            if (own != null && e.getPeriodEnd().isBefore(own)) {
                // The previous owner's days: compared on their own below, never with ours.
                if (e.getStatus() == RecognitionStatus.POSTED) theirsKept.merge(key, e.getAmount(), BigDecimal::add);
                continue;
            }
            RecognitionStatus status = e.getStatus();
            if (status == RecognitionStatus.PLANNED && locked != null && !e.getPeriodEnd().isAfter(locked)) {
                RecognitionEntry row = lock(e.getId());
                if (row.getStatus() == RecognitionStatus.PLANNED) {
                    row.setStatus(RecognitionStatus.CANCELLED);
                    entries.save(row);
                    folded.merge(key, row.getAmount(), BigDecimal::add);
                    foldedMonths.merge(key, 1, Integer::sum);
                    continue;
                }
                status = row.getStatus();
            }
            if (status == RecognitionStatus.POSTED || status == RecognitionStatus.PLANNED) {
                kept.merge(key, e.getAmount(), BigDecimal::add);
            }
        }

        // --- 2. the new lines: the remaining term, per day -------------------------
        // Each line's whole-term value, split at d: what it would have earned through
        // d − 1 (compared with what was recognised, below) and the rest, earned from d
        // over the remaining days (remaining ÷ remaining days; the last period absorbs
        // the rounding).
        Map<List<UUID>, BigDecimal> newEarned = new java.util.LinkedHashMap<>();
        Map<List<UUID>, BigDecimal> theirsNew = new java.util.LinkedHashMap<>();
        Map<List<UUID>, LocalDate> theirsFrom = new java.util.LinkedHashMap<>();
        for (LeaseLine line : leaseLines.findByLease_IdOrderBySeqNoAsc(leaseId)) {
            Window w = schedulable(lease, line);
            if (w == null) continue;
            if (segments.existsByLeaseLineIdAndStatusIn(line.getId(), LIVE_SEGMENTS)) continue;
            BigDecimal earned = w.from().isBefore(d)
                    ? ProrationEngine.earnedThrough(w.net(), w.from(), w.to(), d.minusDays(1)) : BigDecimal.ZERO;
            BigDecimal theirs = own != null && w.from().isBefore(own)
                    ? ProrationEngine.earnedThrough(w.net(), w.from(), w.to(), own.minusDays(1)) : BigDecimal.ZERO;
            LocalDate newFrom = w.from().isBefore(d) ? d : w.from();
            RentSegment probe = newSegment(lease, line, w.fee(), w.from(), w.to(), w.net());
            List<UUID> key = List.of(poster.deferralAccountId(probe, lease), poster.incomeAccountId(probe, lease));
            lineOfKey.put(key, line.getId());
            if (w.fee()) feeKeys.add(key);
            LocalDate ours = own != null && w.from().isBefore(own) ? own : w.from();
            fromOfKey.merge(key, ours, (x, y) -> x.isBefore(y) ? x : y);
            newEarned.merge(key, earned.subtract(theirs), BigDecimal::add);
            if (own != null && w.from().isBefore(own)) {
                theirsNew.merge(key, theirs, BigDecimal::add);
                theirsFrom.merge(key, w.from(), (x, y) -> x.isBefore(y) ? x : y);
            }
            BigDecimal remaining = w.net().subtract(earned);
            if (remaining.signum() <= 0 || newFrom.isAfter(w.to())) continue;
            schedule(lease, newSegment(lease, line, w.fee(), newFrom, w.to(), remaining));
        }

        // --- 3. the elapsed period's difference, as one catch-up dated d -----------
        java.util.Set<List<UUID>> keys = new java.util.LinkedHashSet<>(kept.keySet());
        keys.addAll(newEarned.keySet());
        for (List<UUID> key : keys) {
            BigDecimal diff = newEarned.getOrDefault(key, BigDecimal.ZERO)
                    .subtract(kept.getOrDefault(key, BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP);
            if (diff.signum() == 0) continue;
            // #372 review P3-1: say so when the catch-up takes months the lock closed
            // before anybody recognised them.
            String why = foldedMonths.containsKey(key)
                    ? reason + "; includes " + foldedMonths.get(key) + " locked month(s) never recognised ("
                            + folded.get(key).setScale(2, RoundingMode.HALF_UP).toPlainString() + ")"
                    : reason;
            postCatchUp(lease, key, feeKeys.contains(key), lineOfKey.get(key), fromOfKey.get(key), d, diff, why);
        }

        // --- 4. the previous owner's days, re-priced: settled with the vendor -------
        // #376 P3-1: the amended contract values their days differently from what was
        // released to them at the acquisition; the difference is theirs (the vendor
        // account), never our income. Dated d, like the catch-up.
        if (own != null) {
            java.util.Set<List<UUID>> theirKeys = new java.util.LinkedHashSet<>(theirsKept.keySet());
            theirKeys.addAll(theirsNew.keySet());
            for (List<UUID> key : theirKeys) {
                BigDecimal diff = theirsNew.getOrDefault(key, BigDecimal.ZERO)
                        .subtract(theirsKept.getOrDefault(key, BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP);
                if (diff.signum() == 0) continue;
                LocalDate from = theirsFrom.getOrDefault(key, lease.getStartDate());
                postCatchUp(lease, key, feeKeys.contains(key), lineOfKey.get(key), from, own, d, diff,
                        reason + "; the previous owner's days before " + own + ", settled with the vendor",
                        poster.acquisitionClearingOf(lease));
            }
        }
    }

    /** The date a journal is dated on (null when there is none) — an amendment's re-posted contract. */
    @Transactional(readOnly = true)
    public LocalDate entryDateOf(UUID journalId) {
        return journalId == null ? null : journals.findById(journalId).map(JournalEntry::getEntryDate).orElse(null);
    }

    /**
     * #372 review P3-1: the lease's months still PLANNED inside the period lock — never
     * to be posted by the runner — which an amendment folds into its catch-up: how many
     * and how much. Asked before the amendment so its response can say so.
     */
    public record LockedUnrecognised(int months, BigDecimal amount) { }

    @Transactional(readOnly = true)
    public LockedUnrecognised lockedUnrecognised(UUID leaseId) {
        LocalDate locked = booksLockedThrough();
        if (locked == null) return new LockedUnrecognised(0, BigDecimal.ZERO);
        List<RecognitionEntry> rows = entries.findByLease_IdOrderByPeriodStartAsc(leaseId).stream()
                .filter(e -> e.getStatus() == RecognitionStatus.PLANNED && !e.getPeriodEnd().isAfter(locked)).toList();
        return new LockedUnrecognised(rows.size(), rows.stream().map(RecognitionEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP));
    }

    /** A line's recognition window and value, or null when it has nothing to recognise. */
    private record Window(LocalDate from, LocalDate to, BigDecimal net, boolean fee) { }

    private Window schedulable(Lease lease, LeaseLine line) {
        boolean rent = line.getChargeType() != null && line.getChargeType().getBehaviour() == ChargeBehaviour.RENT;
        boolean fee = !rent && LeasePostingService.earnedOverTerm(lease, line);
        if (!rent && !fee) return null;
        BigDecimal net = line.getNetAmount() == null ? BigDecimal.ZERO : line.getNetAmount();
        if (net.signum() <= 0) return null;
        LocalDate from = line.getPeriodStart() != null ? line.getPeriodStart() : lease.getStartDate();
        LocalDate to = line.getPeriodEnd() != null ? line.getPeriodEnd() : lease.getEndDate();
        if (from == null || to == null || to.isBefore(from)) {
            throw new BusinessRuleViolationException("Line " + line.getSeqNo()
                    + (rent ? " charges rent" : " is earned over the term")
                    + " but has no usable period to recognise it over (" + from + " – " + to + ").");
        }
        return new Window(from, to, net, fee);
    }

    /** An unsaved segment of {@code amount} over {@code from}..{@code to} for the line. */
    private RentSegment newSegment(Lease lease, LeaseLine line, boolean fee, LocalDate from, LocalDate to,
                                   BigDecimal amount) {
        RentSegment segment = new RentSegment();
        segment.setTenantId(lease.getTenantId());
        segment.setLease(lease);
        segment.setLeaseLineId(line.getId());
        segment.setFromDate(from);
        segment.setToDate(to);
        segment.setAmount(amount);
        segment.setDays(ProrationEngine.daysInclusive(from, to));
        segment.setDayRate(ProrationEngine.dayRate(amount, from, to));
        segment.setStatus(SegmentStatus.ACTIVE);
        if (fee) {
            segment.setDeferralAccountId(poster.unearnedChargesLeaf(lease));
            segment.setIncomeAccountId(line.getCreditAccount() == null ? null : line.getCreditAccount().getId());
            if (segment.getIncomeAccountId() == null) {
                throw new BusinessRuleViolationException("Line " + line.getSeqNo() + " has no income account to earn into.");
            }
        }
        return segment;
    }

    /** Saves the segment and plans its months. */
    private void schedule(Lease lease, RentSegment segment) {
        segment = segments.save(segment);
        for (ProrationEngine.Slice slice : ProrationEngine.slice(segment.getAmount(), segment.getFromDate(), segment.getToDate())) {
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

    /**
     * The elapsed period's difference between the amended contract and what was
     * recognised, posted as one {@code CIL} dated the amendment date — a release when
     * the contract rose (Dr deferral / Cr income), a reversal of income when it fell.
     * Recorded as a POSTED row of a closed (TRUNCATED) catch-up segment over the
     * elapsed window, so it shows on the schedule and a later amendment counts it.
     */
    private void postCatchUp(Lease lease, List<UUID> key, boolean fee, UUID lineId, LocalDate from, LocalDate d,
                             BigDecimal diff, String reason) {
        postCatchUp(lease, key, fee, lineId, from, d, d, diff, reason, null);
    }

    /**
     * The same, over the window {@code from} → {@code end − 1}, still dated today's
     * amendment date; {@code creditOverride}, when set, takes the income side (the
     * vendor account for the previous owner's days).
     */
    private void postCatchUp(Lease lease, List<UUID> key, boolean fee, UUID lineId, LocalDate from, LocalDate endExclusive,
                             LocalDate d, BigDecimal diff, String reason, UUID creditOverride) {
        LocalDate end = endExclusive.minusDays(1);
        LocalDate start = from == null || from.isAfter(end) ? end : from;
        RentSegment seg = new RentSegment();
        seg.setTenantId(lease.getTenantId());
        seg.setLease(lease);
        seg.setLeaseLineId(lineId);
        seg.setFromDate(start);
        seg.setToDate(end);
        seg.setAmount(diff);
        seg.setDays(ProrationEngine.daysInclusive(start, end));
        seg.setDayRate(BigDecimal.ZERO);
        seg.setStatus(SegmentStatus.TRUNCATED);
        if (fee) {
            // Only a fee's segment names its accounts (F14-18); a rent segment resolves
            // them from its line and the property's roles — to this same key.
            seg.setDeferralAccountId(key.get(0));
            seg.setIncomeAccountId(key.get(1));
        }
        seg = segments.save(seg);

        RecognitionEntry entry = new RecognitionEntry();
        entry.setTenantId(lease.getTenantId());
        entry.setLease(lease);
        entry.setSegment(seg);
        entry.setPeriodStart(start);
        entry.setPeriodEnd(end);
        entry.setDays(seg.getDays());
        entry.setAmount(diff);
        entry.setStatus(RecognitionStatus.PLANNED);
        entry = entries.saveAndFlush(entry);

        BigDecimal amount = diff.abs();
        PostingRequest.Line deferral = new PostingRequest.Line(new PostingRequest.ById(key.get(0)),
                diff.signum() > 0 ? PostingRequest.Side.DR : PostingRequest.Side.CR, amount, null, null);
        PostingRequest.Line income = new PostingRequest.Line(new PostingRequest.ById(creditOverride != null ? creditOverride : key.get(1)),
                diff.signum() > 0 ? PostingRequest.Side.CR : PostingRequest.Side.DR, amount, null, null);
        JournalEntry cil = postingService.post(PostingRequest.ofPairs(com.datagami.rentaxis.domain.entity.enums.JournalDocType.CIL, d,
                "Recognition catch-up – " + reason,
                com.datagami.rentaxis.core.service.lease.LeaseChequeRegistrar.dimensions(lease, null),
                com.datagami.rentaxis.domain.entity.enums.JournalSourceType.RECOGNITION, entry.getId(), null,
                List.of(diff.signum() > 0 ? PostingRequest.pair(deferral, income) : PostingRequest.pair(income, deferral))));
        entry.setStatus(RecognitionStatus.POSTED);
        entry.setJournalId(cil.getId());
        entry.setPostedAt(java.time.Instant.now());
        entries.save(entry);
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
     * @param unearnedVat     Σ over the same segments of the VAT their lease line
     *                        charged on that unearned amount, through the shared
     *                        {@code LeaseVat} helper. The {@code TCR} credits it
     *                        back to the receivable as a credit note
     *                        ({@code Dr OUTPUT_VAT}); zero on a residential tenancy.
     *                        Computed per segment and summed, never 5% of the total,
     *                        because a lease can mix a VAT-bearing rent line with
     *                        one that is not
     * @param deferrals       the unearned amount split by the account the
     *                        {@code TCO} actually deferred into, one entry per
     *                        segment that has anything left. The debit has to face
     *                        the same leaf the credit went to; see
     *                        {@code RecognitionPoster}'s class note
     * @param latestPostingDate the latest date this termination will write a
     *                        journal on: {@code t} itself, or the entry date of the
     *                        newest {@code CIL} it has to reverse — a reversal is
     *                        never dated before the entry it reverses. The caller
     *                        checks it against the period lock <em>before</em> it
     *                        starts writing
     */
    public record TerminationRecognition(BigDecimal earnedThrough, BigDecimal recognisedSoFar,
                                         BigDecimal unearned, BigDecimal unearnedVat,
                                         List<UnearnedDeferral> deferrals,
                                         LocalDate latestPostingDate,
                                         BigDecimal unearnedVatTaxable,
                                         BigDecimal unearnedVatContract) {
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
                truncateSegment(segment, t, t, "Lease terminated " + t);
            }
        }
        return summary;
    }

    // ------------------------------------------------------------------
    // mid-term reduction (F14-32)
    // ------------------------------------------------------------------

    /**
     * The line's live schedule from {@code e} on: its ACTIVE segments that run to
     * {@code e} or later, oldest first. Empty when the line has no schedule (a
     * one-off fee, a lease posted with fees at posting) or its schedule ended before
     * {@code e}.
     */
    @Transactional(readOnly = true)
    public List<RentSegment> liveSegmentsFrom(UUID leaseId, UUID lineId, LocalDate e) {
        return liveSegments(leaseId).stream()
                .filter(s -> lineId.equals(s.getLeaseLineId()) && !s.getToDate().isBefore(e))
                .toList();
    }

    /** What these segments still have to earn from {@code e} on: amount − earned through the day before. */
    public static BigDecimal remainingFrom(List<RentSegment> segs, LocalDate e) {
        BigDecimal remaining = BigDecimal.ZERO;
        for (RentSegment s : segs) {
            BigDecimal earned = ProrationEngine.earnedThrough(s.getAmount(), s.getFromDate(), s.getToDate(), e.minusDays(1));
            remaining = remaining.add(s.getAmount().subtract(earned));
        }
        return remaining.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * A credit addendum cut a line's rate from {@code e} (F14-32): the schedule
     * keeps what was earned through {@code e − 1} and earns {@code newRemaining}
     * over {@code e} → the old end, day by day. Months already recognised after
     * {@code e − 1} are reversed (dated no earlier than {@code floor}) and the month
     * containing it is re-cut, exactly as a termination does; the kept part stays
     * ACTIVE (not TRUNCATED), so a later termination before {@code e} still cuts it.
     *
     * @return the new segment, or null when nothing is left to earn (the charge was removed)
     */
    @Transactional
    public RentSegment reduceFrom(Lease lease, List<RentSegment> segs, LocalDate e, BigDecimal newRemaining,
                                  LocalDate floor, String reason) {
        if (segs.isEmpty()) throw new IllegalStateException("No live schedule to reduce");
        RentSegment template = segs.get(0);
        LocalDate from = segs.stream().map(RentSegment::getFromDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate to = segs.stream().map(RentSegment::getToDate).max(LocalDate::compareTo).orElseThrow();
        LocalDate newFrom = from.isAfter(e) ? from : e;
        for (RentSegment seg : segs) {
            if (!seg.getFromDate().isBefore(e)) {
                cancelWholeSegment(seg, floor, reason);
            } else {
                truncateSegment(seg, e.minusDays(1), floor, reason);
                seg.setStatus(SegmentStatus.ACTIVE);
                segments.save(seg);
            }
        }
        if (newRemaining == null || newRemaining.signum() <= 0) return null;
        RentSegment segment = new RentSegment();
        segment.setTenantId(lease.getTenantId());
        segment.setLease(lease);
        segment.setLeaseLineId(template.getLeaseLineId());
        segment.setFromDate(newFrom);
        segment.setToDate(to);
        segment.setAmount(newRemaining);
        segment.setDays(ProrationEngine.daysInclusive(newFrom, to));
        segment.setDayRate(ProrationEngine.dayRate(newRemaining, newFrom, to));
        segment.setStatus(SegmentStatus.ACTIVE);
        segment.setDeferralAccountId(template.getDeferralAccountId());
        segment.setIncomeAccountId(template.getIncomeAccountId());
        segments.flush();
        segment = segments.save(segment);
        for (ProrationEngine.Slice slice : ProrationEngine.slice(newRemaining, newFrom, to)) {
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
        return segment;
    }

    /** Only ACTIVE: a TRUNCATED segment has already been cut, and a CANCELLED one is history. */
    private List<RentSegment> liveSegments(UUID leaseId) {
        return segments.findByLease_IdAndStatusInOrderByFromDateAsc(leaseId, EnumSet.of(SegmentStatus.ACTIVE));
    }

    private TerminationRecognition summarise(Lease lease, List<RentSegment> live, LocalDate t) {
        BigDecimal earned = BigDecimal.ZERO;
        BigDecimal unearned = BigDecimal.ZERO;
        BigDecimal unearnedVat = BigDecimal.ZERO;
        // The part of `unearned` that VAT was charged on — the net a termination's
        // VAT settlement is computed against (spec 2026-09-24 §1).
        BigDecimal unearnedVatTaxable = BigDecimal.ZERO;
        BigDecimal unearnedVatContract = BigDecimal.ZERO;
        List<UnearnedDeferral> deferrals = new ArrayList<>();
        for (RentSegment segment : live) {
            BigDecimal segmentEarned = ProrationEngine.earnedThrough(
                    segment.getAmount(), segment.getFromDate(), segment.getToDate(), t);
            BigDecimal segmentUnearned = segment.getAmount().subtract(segmentEarned);
            earned = earned.add(segmentEarned);
            unearned = unearned.add(segmentUnearned);
            if (segmentUnearned.signum() > 0) {
                deferrals.add(new UnearnedDeferral(poster.deferralOf(segment, lease), segmentUnearned));
                // The tax follows the supply: whatever this segment's line charged
                // VAT on, the part of it the tenancy never used is handed back too.
                // Asked of LeaseVat rather than multiplied here — one definition of
                // which lines are taxed and at what rate (spec §6.2).
                LeaseLine line = lineOf(segment);
                BigDecimal segmentVat = LeaseVat.vatOnPortion(line, segmentUnearned);
                unearnedVat = unearnedVat.add(segmentVat);
                // #376 R1-P2-1: the part on the contract's own lines (an acquired lease's
                // vendor VAT is a share of it; an addendum's is always ours).
                if (com.datagami.rentaxis.core.service.vat.AcquiredLeaseVat.contractLine(lease, line)) {
                    unearnedVatContract = unearnedVatContract.add(segmentVat);
                }
                if (segmentVat.signum() > 0) unearnedVatTaxable = unearnedVatTaxable.add(segmentUnearned);
            }
        }
        List<RecognitionEntry> posted = entries
                .findByLease_IdAndStatusInOrderByPeriodStartAsc(lease.getId(), EnumSet.of(RecognitionStatus.POSTED));
        BigDecimal recognised = posted.stream()
                .map(RecognitionEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TerminationRecognition(
                earned.setScale(2, RoundingMode.HALF_UP),
                recognised.setScale(2, RoundingMode.HALF_UP),
                unearned.setScale(2, RoundingMode.HALF_UP),
                unearnedVat.setScale(2, RoundingMode.HALF_UP),
                List.copyOf(deferrals),
                latestPostingDate(posted, t),
                unearnedVatTaxable.setScale(2, RoundingMode.HALF_UP),
                unearnedVatContract.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * The lease line a segment was cut from, or null when an amendment has since
     * deleted it.
     *
     * <p>Null is a real answer here rather than a failure: changeset 86 dropped
     * {@code fk_rs_line} precisely so a retired segment can outlive its line, and a
     * line that no longer exists charges no VAT to hand back. A <em>live</em>
     * segment always has its line — a rebuild cancels the old segments and cuts new
     * ones from the new lines — so the null case cannot be reached from a
     * termination on a lease that is still running.</p>
     */
    private LeaseLine lineOf(RentSegment segment) {
        return segment.getLeaseLineId() == null ? null
                : leaseLines.findById(segment.getLeaseLineId()).orElse(null);
    }

    /**
     * The newest date this termination would write on.
     *
     * <p>Every row whose period ends on or after {@code t} is a candidate for
     * reversal, and a reversal carries the later of {@code t} and the entry's own
     * date, so the answer is the newest of those. Read off the journals rather
     * than off {@code periodEnd}, which is what {@code RecognitionPoster} happens
     * to stamp: one of those two is the ledger's own record and the other is an
     * assumption about it.</p>
     */
    private LocalDate latestPostingDate(List<RecognitionEntry> posted, LocalDate t) {
        List<UUID> journalIds = posted.stream()
                .filter(e -> !e.getPeriodEnd().isBefore(t))
                .map(RecognitionEntry::getJournalId).filter(Objects::nonNull).toList();
        LocalDate latest = t;
        if (!journalIds.isEmpty()) {
            for (JournalEntry journal : journals.findAllById(journalIds)) {
                if (journal.getEntryDate() != null && journal.getEntryDate().isAfter(latest)) {
                    latest = journal.getEntryDate();
                }
            }
        }
        return latest;
    }

    private void cancelWholeSegment(RentSegment segment, LocalDate t) {
        cancelWholeSegment(segment, t, "Lease terminated " + t);
    }

    private void cancelWholeSegment(RentSegment segment, LocalDate t, String reason) {
        for (RecognitionEntry entry : entries.findBySegment_IdOrderByPeriodStartAsc(segment.getId())) {
            retire(entry.getId(), t, reason);
        }
        segment.setStatus(SegmentStatus.CANCELLED);
        segments.save(segment);
    }

    /**
     * @param t      the last day the segment keeps
     * @param floor  the earliest date a reversal files on (a termination's own date;
     *               a credit addendum's entry date)
     * @param reason the narration on the reversals
     */
    private void truncateSegment(RentSegment segment, LocalDate t, LocalDate floor, String reason) {
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

        // Ids, not the instances just read. Each row is re-read under its own write
        // lock before it is touched, and every decision below is made on the status
        // that read returns — see retire/recut. Periods and amounts are immutable
        // once a row exists, so the arithmetic above is safe to compute from the
        // unlocked read; only the status can have moved under us, and that is
        // exactly what changes cancel into reverse.
        for (int i = cutIndex + 1; i < live.size(); i++) {
            retire(live.get(i).getId(), floor, reason);
        }
        recut(live.get(cutIndex).getId(), kept.get(cutIndex), segment, floor, reason);

        // Read before anything moves: earnedThrough is defined over the segment's
        // ORIGINAL window, and the next four lines are about to shorten it.
        BigDecimal earned = ProrationEngine.earnedThrough(
                segment.getAmount(), segment.getFromDate(), segment.getToDate(), t);
        segment.setOriginalAmount(segment.getAmount());
        segment.setOriginalToDate(segment.getToDate());
        segment.setStatus(SegmentStatus.TRUNCATED);
        segment.setAmount(earned);
        segment.setToDate(t);
        segment.setDays(ProrationEngine.daysInclusive(segment.getFromDate(), t));
        // amount, to_date and days move together so the row goes on describing one
        // consistent window. It has to: the obvious question a settlement asks is
        // earnedThrough(amount, fromDate, toDate, T), and a row that kept the
        // contract's 51,000 against a 145-day window answers it with 51,000 — a
        // 30,739.73 error, silently, on the statement that decides the refund. The
        // contract figures are not lost, they move to original_amount /
        // original_to_date, and the unearned rent this termination reverses is
        // exactly the difference.
        //
        // day_rate is the one field that stays: it is what the months before the
        // cut were worth, it is what ProrationEngine.truncate was handed to compute
        // the cut, and re-deriving it from the shortened window would restate them.
        segments.save(segment);
    }

    /**
     * A row wholly after {@code t}: cancelled if it was only planned, reversed if
     * the ledger saw it — decided <em>under the row's write lock</em>.
     *
     * <p>{@code RecognitionPoster} claims a row with {@code lockById} before it
     * posts, precisely because the nightly job and a hand-run close would otherwise
     * both see PLANNED. A termination is the third writer and needs the same claim
     * for a sharper reason: the poster's race costs a duplicate journal, this one
     * costs a <em>lost update</em>. Read without the lock, a row the nightly close
     * posted a moment ago still looks PLANNED, gets CANCELLED, and its {@code CIL}
     * is left POSTED with nothing pointing at it — income recognised for a period
     * after the tenancy ended, advance rent over-released, and a trial balance that
     * still balances. Under the lock the same row reads POSTED and is reversed.</p>
     *
     * <p>Lock order is lease → entry, everywhere: a termination takes the lease row
     * first and the poster takes no lease lock at all, so the two cannot cycle.</p>
     */
    private void retire(UUID entryId, LocalDate t, String reason) {
        RecognitionEntry entry = lock(entryId);
        if (entry.getStatus() == RecognitionStatus.POSTED) {
            if (entry.getJournalId() == null) {
                // Unreachable: RecognitionPoster sets status and journal together in
                // one transaction. Refused rather than skipped, because skipping
                // leaves recognised income past the end of the tenancy and says
                // nothing about it.
                throw new IllegalStateException(
                        "Recognition entry " + entryId + " is POSTED with no journal to reverse");
            }
            postingService.reverse(entry.getJournalId(), reversalDate(entry.getJournalId(), t), reason);
            entry.setStatus(RecognitionStatus.REVERSED);
            entries.save(entry);
        } else if (entry.getStatus() == RecognitionStatus.PLANNED) {
            entry.setStatus(RecognitionStatus.CANCELLED);
            entries.save(entry);
        }
    }

    /**
     * When a recognition reversal files: {@code max(t, the original's own date)}.
     *
     * <p><b>A reversal must never precede the entry it reverses.</b> A month closed
     * after {@code t} — the tenancy ended on the 15th, the close for that month had
     * already run — carries its own month-end date, and cancelling it on {@code t}
     * would make the books wrong <em>between</em> the two dates: read as of
     * {@code t}, the income would be short by every month that had run ahead and
     * advance rent would be in debit. All-time balances net out either way, which
     * is exactly why this is easy to miss and why the settlement statement, which
     * is drawn as of {@code t}, is the reader that would have been wrong.</p>
     *
     * <p>The later date is always open: {@code books_locked_through} is a single
     * high-water mark, a termination is refused unless {@code t} is past it, and
     * this date is at or after {@code t}. {@code LeaseTerminationService} checks it
     * before writing anything all the same.</p>
     */
    private LocalDate reversalDate(UUID journalId, LocalDate t) {
        LocalDate original = journals.findById(journalId).map(JournalEntry::getEntryDate).orElse(t);
        return original.isAfter(t) ? original : t;
    }

    /**
     * The row, claimed {@code FOR UPDATE} <em>and re-read</em>.
     *
     * <p>{@code refresh} rather than a locking finder, and the difference is the
     * whole point of the lock here. A locking query still answers from the
     * first-level cache when the transaction has already loaded that row — which
     * this one has, a few lines earlier, to compute the arithmetic — so it would
     * take the lock and hand back the <em>stale</em> status, which is exactly the
     * value the lock exists to stop us acting on. {@code refresh(…,
     * PESSIMISTIC_WRITE)} does both halves: {@code SELECT … FOR UPDATE}, then
     * overwrite the instance from the row it just locked.</p>
     */
    private RecognitionEntry lock(UUID entryId) {
        RecognitionEntry entry = entries.findById(entryId)
                .orElseThrow(() -> new NotFoundException("Recognition entry not found"));
        requireOwnTenant(entry);
        entityManager.refresh(entry, LockModeType.PESSIMISTIC_WRITE);
        return entry;
    }

    /**
     * The row this transaction is about to lock and post belongs to the tenant in
     * context, and there <em>is</em> one.
     *
     * <p>The Hibernate filter already scopes the read above ({@code TenantAspect}
     * covers inherited repository methods — {@code TenantAspectIT} pins it), so
     * this is the second layer rather than the first. It earns its place by being
     * local: the entry id arrives from a caller, and what happens next writes a
     * {@code CIL} into the ledger of whoever is in context, under that tenant's
     * entry number. An empty context fails closed here rather than several calls
     * later — the nightly close sets the context per organisation
     * ({@code RevenueRecognitionJob}), so nothing legitimate reaches this without
     * one, and a posting path is the wrong place to discover that by accident.</p>
     */
    private static void requireOwnTenant(RecognitionEntry entry) {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "No tenant in context; recognition entry " + entry.getId() + " cannot be posted");
        }
        if (!tenantId.equals(entry.getTenantId())) {
            throw new NotFoundException("Recognition entry not found");
        }
    }

    /**
     * The row containing {@code t}, re-cut to end on it — under the row's own write
     * lock, for the reason {@link #retire} explains.
     *
     * <p>The lost update is worse here than there: a row read as PLANNED and edited
     * in place, that the nightly close posted in between, ends POSTED at 1–15 Feb
     * for 2,095.88 while the {@code CIL} it names says 3,912.33 for the whole of
     * February.</p>
     *
     * <p>See the method note on {@link #truncateForTermination} for why the
     * comparison is on amount as well as period.</p>
     */
    private void recut(UUID entryId, ProrationEngine.Slice cut, RentSegment segment, LocalDate t, String reason) {
        RecognitionEntry entry = lock(entryId);
        if (entry.getStatus() == RecognitionStatus.PLANNED) {
            entry.setPeriodEnd(cut.periodEnd());
            entry.setDays(cut.days());
            entry.setAmount(cut.amount());
            entries.save(entry);
            return;
        }
        if (entry.getStatus() != RecognitionStatus.POSTED || entry.getJournalId() == null) {
            throw new IllegalStateException("Recognition entry " + entryId + " is " + entry.getStatus()
                    + " and cannot be re-cut");
        }
        boolean unchanged = entry.getPeriodEnd().isEqual(cut.periodEnd())
                && entry.getAmount().compareTo(cut.amount()) == 0;
        if (unchanged) {
            return;
        }
        postingService.reverse(entry.getJournalId(), reversalDate(entry.getJournalId(), t), reason);
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
        int alreadyDone = 0;
        int withdrawn = 0;
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
            } catch (RecognitionEntryNotPlannedException e) {
                // Break-it R4 money4 F1: another run (two people closing the month at
                // once) posted this row after the candidates were read, or an amendment
                // replaced it with a PLANNED row the next run picks up. Either way the
                // work is done exactly once; it is not a failure to report or re-run.
                // Review of R4-B M10: only a POSTED row was recognised by someone else. A
                // CANCELLED (or REVERSED) one was taken off the schedule by a contract
                // change meanwhile — nothing was recognised for it, and saying "another
                // run did it" would be untrue; it is reported on its own.
                if (e.getStatus() == RecognitionStatus.POSTED) alreadyDone++;
                else withdrawn++;
            } catch (RuntimeException e) {
                // The entry's own transaction rolled back; there is no other one to
                // take down with it, which is the whole point of the separate bean.
                log.warn("Recognition entry {} ({}–{}) could not be posted: {}",
                        row.id(), row.periodStart(), row.periodEnd(), e.getMessage());
                // Break-it R3 data3 F4: a refusal written for users as is; anything else
                // (SQL, ids) is logged under a reference and only the reference is shown.
                errors.add("Entry " + row.periodStart() + "–" + row.periodEnd() + ": "
                        + com.datagami.rentaxis.core.service.ImportFailures.safe(e,
                                com.datagami.rentaxis.core.service.ImportFailures.Kind.POST, log,
                                "Recognition entry " + row.id()).message());
            }
        }
        return new RecognitionRunResult(preview, preview ? 0 : done.size(), done.size(),
                total.setScale(2, RoundingMode.HALF_UP), done,
                locked.size(), locked, plan.lockedThrough(), errors, alreadyDone, withdrawn);
    }

    /** What a run has to decide about, read once and detached. */
    private record Candidates(List<RecognitionEntryDTO> rows, LocalDate lockedThrough) {
    }

    /** What a cut-over catch-up recognised for one lease. */
    public record LeaseCatchUp(int posted, BigDecimal amount) {
    }

    /**
     * Recognise everything <em>one</em> lease has already earned, up to and
     * including {@code through} (spec §10.3, controller ruling R13).
     *
     * <p>A cut-over contract normally started months before the client's books
     * open. Its {@code TCO} parks the whole year in advance rent on the contract
     * date; the months between then and the cut-over are income the landlord has
     * already earned, and the books cannot open with them still sitting in a
     * liability. This is what earns them, and it stops on {@code through} —
     * {@code booksStart − 1} — because everything after that belongs to the
     * ordinary month-end close on this system.</p>
     *
     * <p><b>Not {@link #runTo}.</b> That one walks every planned row of the whole
     * organisation, which during a bulk post would sweep up the leases of other
     * batches and of contracts typed in by hand, stamping them all with a batch id
     * they have nothing to do with — and then a reverse of that batch would take
     * them off the books. It also skips rows inside the period lock, which is every
     * row a cut-over has. This one is scoped to the lease and is deliberately
     * lock-blind: the exemption comes from the batch id it threads through.</p>
     *
     * <p><b>{@code MANDATORY}, and {@code postJoining} rather than
     * {@code post}.</b> The catch-up is part of its lease's own all-or-nothing
     * post: a lease that cannot recognise its history — an unmapped income account,
     * a term with no usable period — must leave no {@code TCO} and no {@code PDR}
     * behind either. {@code RecognitionPoster.post}'s {@code REQUIRES_NEW} would
     * commit each {@code CIL} independently of exactly the transaction that is
     * about to roll back, and would not see the schedule the same uncommitted
     * transaction has just built.</p>
     *
     * @param through      the last period end to recognise, inclusive
     * @param importBatchId the batch every {@code CIL} carries; never null here in
     *                      practice, and the parameter is what makes that explicit
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public LeaseCatchUp catchUpLease(UUID leaseId, LocalDate through, UUID importBatchId) {
        lease(leaseId); // tenant-scoped, and a 404 rather than a silent empty run
        int posted = 0;
        BigDecimal amount = BigDecimal.ZERO;
        for (RecognitionEntry entry : entries.findByLease_IdAndStatusInOrderByPeriodStartAsc(
                leaseId, EnumSet.of(RecognitionStatus.PLANNED))) {
            if (entry.getPeriodEnd().isAfter(through)) continue;
            poster.postJoining(entry.getId(), importBatchId);
            posted++;
            amount = amount.add(entry.getAmount());
        }
        return new LeaseCatchUp(posted, amount.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * S16-14: a running tenancy of a building acquired on {@code a} — what it earned
     * before {@code a} is the previous owner's. Each live segment that began before
     * {@code a} is split there, per day ({@link ProrationEngine#earnedThrough}): the
     * earned part is one POSTED row of a closed (TRUNCATED) segment, released by a
     * {@code CIL} dated {@code a} from the deferral into the acquisition's opening
     * position ({@code openingAccountId}) rather than into income; the rest is planned
     * over {@code a} → the old end (remaining ÷ remaining days, the last period
     * absorbing the rounding) and earned by the month-end close like any schedule.
     * Nothing is dated before {@code a}.
     *
     * <p>{@code MANDATORY}: part of the lease's own all-or-nothing post, as
     * {@link #catchUpLease} is.</p>
     *
     * @return the {@code CIL}s posted
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public int acquireFrom(UUID leaseId, LocalDate a, UUID openingAccountId, UUID importBatchId) {
        Lease lease = lease(leaseId);
        String reason = "Acquired " + a;
        int posted = 0;
        for (RentSegment seg : segments.findByLease_IdOrderByFromDateAsc(leaseId)) {
            if (seg.getStatus() != SegmentStatus.ACTIVE || !seg.getFromDate().isBefore(a)) continue;
            LocalDate end = seg.getToDate().isBefore(a) ? seg.getToDate() : a.minusDays(1);
            BigDecimal earned = ProrationEngine.earnedThrough(seg.getAmount(), seg.getFromDate(), seg.getToDate(), end);
            BigDecimal remaining = seg.getAmount().subtract(earned);
            PostingRequest.AccountRef deferral = poster.deferralOf(seg, lease);
            cancelWholeSegment(seg, a, reason);
            if (remaining.signum() > 0 && !seg.getToDate().isBefore(a)) {
                schedule(lease, copyOf(seg, a, seg.getToDate(), remaining, SegmentStatus.ACTIVE));
            }
            if (earned.signum() <= 0) continue;

            RentSegment before = segments.save(copyOf(seg, seg.getFromDate(), end, earned, SegmentStatus.TRUNCATED));
            RecognitionEntry entry = new RecognitionEntry();
            entry.setTenantId(lease.getTenantId());
            entry.setLease(lease);
            entry.setSegment(before);
            entry.setPeriodStart(before.getFromDate());
            entry.setPeriodEnd(end);
            entry.setDays(before.getDays());
            entry.setAmount(earned);
            entry.setStatus(RecognitionStatus.PLANNED);
            entry = entries.saveAndFlush(entry);
            JournalEntry cil = postingService.post(PostingRequest.ofPairs(
                    com.datagami.rentaxis.domain.entity.enums.JournalDocType.CIL, a,
                    "Earned by the previous owner before the acquisition on " + a,
                    com.datagami.rentaxis.core.service.lease.LeaseChequeRegistrar.dimensions(lease, null),
                    com.datagami.rentaxis.domain.entity.enums.JournalSourceType.RECOGNITION, entry.getId(), importBatchId,
                    List.of(PostingRequest.pair(
                            new PostingRequest.Line(deferral, PostingRequest.Side.DR, earned, null, null),
                            new PostingRequest.Line(new PostingRequest.ById(openingAccountId), PostingRequest.Side.CR,
                                    earned, null, null)))));
            entry.setStatus(RecognitionStatus.POSTED);
            entry.setJournalId(cil.getId());
            entry.setPostedAt(java.time.Instant.now());
            entries.save(entry);
            posted++;
        }
        return posted;
    }

    /** A segment like {@code template} (its line and, for a fee, its accounts) over {@code from}..{@code to}. */
    private static RentSegment copyOf(RentSegment template, LocalDate from, LocalDate to, BigDecimal amount,
                                      SegmentStatus status) {
        RentSegment segment = new RentSegment();
        segment.setTenantId(template.getTenantId());
        segment.setLease(template.getLease());
        segment.setLeaseLineId(template.getLeaseLineId());
        segment.setFromDate(from);
        segment.setToDate(to);
        segment.setAmount(amount);
        segment.setDays(ProrationEngine.daysInclusive(from, to));
        segment.setDayRate(status == SegmentStatus.ACTIVE ? ProrationEngine.dayRate(amount, from, to) : BigDecimal.ZERO);
        segment.setStatus(status);
        segment.setDeferralAccountId(template.getDeferralAccountId());
        segment.setIncomeAccountId(template.getIncomeAccountId());
        return segment;
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
            boolean rent = line.getChargeType() != null && line.getChargeType().getBehaviour() == ChargeBehaviour.RENT;
            // F14-18: a periodic fee the lease earns over its term, deferred by the TCO.
            boolean fee = !rent && LeasePostingService.earnedOverTerm(lease, line);
            if (!rent && !fee) continue;
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
                        + (rent ? " charges rent" : " is earned over the term")
                        + " but has no usable period to recognise it over (" + from + " – " + to + ").");
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
            if (fee) {
                // Resolved now, in the posting transaction that just credited it, so
                // each month's release debits the very leaf the TCO deferred into and
                // credits the fee's own income leaf — whatever the mappings say later.
                segment.setDeferralAccountId(poster.unearnedChargesLeaf(lease));
                segment.setIncomeAccountId(line.getCreditAccount() == null ? null : line.getCreditAccount().getId());
                if (segment.getIncomeAccountId() == null) {
                    throw new BusinessRuleViolationException("Line " + line.getSeqNo() + " has no income account to earn into.");
                }
            }
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

        // The building each row belongs to, in one query for the whole page. The
        // entry has no property of its own — it is the lease's, through the unit —
        // and the month-end list groups by it (spec §11), so reading it off the
        // lazy association row by row would be two selects per line.
        List<UUID> leaseIds = rows.stream()
                .map(r -> idOf(r.getLease(), Lease::getId)).filter(Objects::nonNull).distinct().toList();
        Map<UUID, Where> where = new HashMap<>();
        if (!leaseIds.isEmpty()) {
            leases.findAllWithUnitAndPropertyByIdIn(leaseIds).forEach(l -> where.put(l.getId(), whereOf(l)));
        }

        // F14-18: which rows earn a fee rather than rent, and which fee — one query
        // for the page's segments and one for their lines.
        List<UUID> segmentIds = rows.stream()
                .map(r -> idOf(r.getSegment(), RentSegment::getId)).filter(Objects::nonNull).distinct().toList();
        Map<UUID, LeaseLine> feeLineBySegment = new HashMap<>();
        java.util.Set<UUID> feeSegments = new java.util.HashSet<>();
        if (!segmentIds.isEmpty()) {
            Map<UUID, RentSegment> feeSegmentById = new HashMap<>();
            segments.findAllById(segmentIds).forEach(sg -> {
                if (sg.getIncomeAccountId() != null) feeSegmentById.put(sg.getId(), sg);
            });
            if (!feeSegmentById.isEmpty()) {
                Map<UUID, LeaseLine> lines = new HashMap<>();
                leaseLines.findAllById(feeSegmentById.values().stream().map(RentSegment::getLeaseLineId)
                        .filter(Objects::nonNull).distinct().toList()).forEach(l -> lines.put(l.getId(), l));
                Map<UUID, List<LeaseLine>> currentLines = new HashMap<>();
                Map<UUID, java.util.Optional<UUID>> advanceRentLeaf = new HashMap<>();
                feeSegmentById.forEach((sid, sg) -> {
                    UUID leaseId = idOf(sg.getLease(), Lease::getId);
                    // S16-15 (#375 P3-1): a segment deferring from the property's Advance
                    // Rent is rent, whatever accounts it names — #372 wrote rent catch-ups
                    // with both, and a later amendment deletes the line they named, so the
                    // deferral is the one fact about them that survives.
                    UUID propertyId = leaseId == null ? null
                            : where.getOrDefault(leaseId, Where.UNKNOWN).propertyId();
                    java.util.Optional<UUID> advance = advanceRentLeaf.computeIfAbsent(propertyId,
                            p -> java.util.Optional.ofNullable(poster.advanceRentLeafOf(p)));
                    if (sg.getDeferralAccountId() != null && advance.isPresent()
                            && advance.get().equals(sg.getDeferralAccountId())) return;
                    LeaseLine l = sg.getLeaseLineId() == null ? null : lines.get(sg.getLeaseLineId());
                    if (l == null && leaseId != null) {
                        // An amendment replaces the lease's lines, so a fee segment kept from
                        // before it names a line that is gone: the lease's current line earning
                        // into the same income account — only when exactly one does (#375
                        // P3-2); two fees into one account leave the honest generic "FEE".
                        List<LeaseLine> same = currentLines
                                .computeIfAbsent(leaseId, leaseLines::findByLease_IdOrderBySeqNoAsc).stream()
                                .filter(c -> c.getCreditAccount() != null
                                        && sg.getIncomeAccountId().equals(c.getCreditAccount().getId()))
                                .toList();
                        l = same.size() == 1 ? same.get(0) : null;
                    }
                    // A RENT line is rent (the line survives when the lease was amended once).
                    if (l != null && l.getChargeType() != null
                            && l.getChargeType().getBehaviour() == ChargeBehaviour.RENT) return;
                    feeSegments.add(sid);
                    if (l != null) feeLineBySegment.put(sid, l);
                });
            }
        }

        return rows.stream().map(r -> toDto(r,
                r.getJournalId() == null ? null : numbers.get(r.getJournalId()),
                where.getOrDefault(idOf(r.getLease(), Lease::getId), Where.UNKNOWN),
                feeSegments.contains(idOf(r.getSegment(), RentSegment::getId)),
                feeLineBySegment.get(idOf(r.getSegment(), RentSegment::getId)))).toList();
    }

    /** Which building and flat a row is about — resolved once per lease, not per row. */
    private record Where(UUID propertyId, String propertyName, String unitName) {
        static final Where UNKNOWN = new Where(null, null, null);
    }

    private static Where whereOf(Lease lease) {
        Unit unit = lease.getUnit();
        Property property = unit == null ? null : unit.getProperty();
        return new Where(property == null ? null : property.getId(),
                property == null ? null : property.getNameEn(),
                unit == null ? null : unit.getUnitNumber());
    }

    private static RecognitionEntryDTO toDto(RecognitionEntry r, String journalNumber, Where where, boolean fee,
                                             LeaseLine feeLine) {
        var type = feeLine == null ? null : feeLine.getChargeType();
        return new RecognitionEntryDTO(
                r.getId(),
                idOf(r.getLease(), Lease::getId),
                idOf(r.getSegment(), RentSegment::getId),
                where.propertyId(),
                where.propertyName(),
                where.unitName(),
                r.getPeriodStart(),
                r.getPeriodEnd(),
                r.getDays(),
                r.getAmount(),
                r.getStatus(),
                r.getJournalId(),
                journalNumber,
                r.getPostedAt(),
                !fee ? null : (type == null ? "FEE" : type.getCode()),
                type == null ? null : type.getNameEn(),
                type == null ? null : type.getNameAr());
    }

    /** {@code getId()} on a lazy proxy is answered from the foreign key, without a select. */
    private static <T> UUID idOf(T entity, Function<T, UUID> id) {
        return entity == null ? null : id.apply(entity);
    }
}
