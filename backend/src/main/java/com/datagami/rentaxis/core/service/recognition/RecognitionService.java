package com.datagami.rentaxis.core.service.recognition;

import com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.JournalEntry;
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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
 * <p><b>Transactions.</b> Every method is transactional, because
 * {@code TenantAspect} enables the Hibernate tenant filter only inside one and a
 * repository call made outside would see every tenant's rows. The one exception
 * to "one transaction" is {@link #runTo}, which deliberately posts each entry
 * through {@link RecognitionPoster} in a transaction of its own.</p>
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

    public RecognitionService(RentSegmentRepository segments,
                              RecognitionEntryRepository entries,
                              LeaseRepository leases,
                              LeaseLineRepository leaseLines,
                              JournalEntryRepository journals,
                              TenantFiscalSettingsRepository fiscalSettings,
                              PostingService postingService,
                              RecognitionPoster poster) {
        this.segments = segments;
        this.entries = entries;
        this.leases = leases;
        this.leaseLines = leaseLines;
        this.journals = journals;
        this.fiscalSettings = fiscalSettings;
        this.postingService = postingService;
        this.poster = poster;
    }

    /** What a run would do, or did: counts, the money, the rows, and what it could not post. */
    public record RecognitionRunResult(int posted, BigDecimal amount, List<RecognitionEntryDTO> entries,
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
    // running
    // ------------------------------------------------------------------

    /**
     * Post every planned row whose period has ended by {@code to} (spec §8.4).
     *
     * <p>Rows in a closed period are reported and left {@code PLANNED}: the books
     * are shut for a reason, and forcing income into a month somebody has already
     * signed off is worse than leaving it for the accountant to decide about.
     * They are not errors, and the run does not stop on them.</p>
     *
     * <p>Each row posts through {@link RecognitionPoster} in its own
     * transaction. One lease with a retired income account costs that lease its
     * month and nothing else — a whole night's recognition rolling back because
     * of a single mapping gap is how a month-end close turns into an incident.</p>
     *
     * @param preview answer what would happen and write nothing
     */
    @Transactional
    public RecognitionRunResult runTo(LocalDate to, boolean preview) {
        List<RecognitionEntry> candidates = plannedThrough(to);
        LocalDate lockedThrough = booksLockedThrough();

        List<RecognitionEntryDTO> done = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (RecognitionEntry entry : candidates) {
            if (lockedThrough != null && !entry.getPeriodEnd().isAfter(lockedThrough)) {
                errors.add("Entry " + entry.getPeriodStart() + "–" + entry.getPeriodEnd()
                        + " is in a locked period (books are locked through " + lockedThrough + ")");
                continue;
            }
            if (preview) {
                done.add(toDto(entry, null));
                total = total.add(entry.getAmount());
                continue;
            }
            try {
                JournalEntry cil = poster.post(entry.getId());
                done.add(posted(entry, cil));
                total = total.add(entry.getAmount());
            } catch (RuntimeException e) {
                // The entry's own transaction rolled back; this one did not, which is
                // the whole point of posting each row through a separate bean.
                log.warn("Recognition entry {} ({}–{}) could not be posted: {}",
                        entry.getId(), entry.getPeriodStart(), entry.getPeriodEnd(), e.getMessage());
                errors.add("Entry " + entry.getPeriodStart() + "–" + entry.getPeriodEnd() + ": " + e.getMessage());
            }
        }
        return new RecognitionRunResult(done.size(), total.setScale(2, java.math.RoundingMode.HALF_UP), done, errors);
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
            if (segments.existsByLeaseLine_IdAndStatusIn(line.getId(), LIVE_SEGMENTS)) continue;

            LocalDate from = line.getPeriodStart() != null ? line.getPeriodStart() : lease.getStartDate();
            LocalDate to = line.getPeriodEnd() != null ? line.getPeriodEnd() : lease.getEndDate();
            if (from == null || to == null || to.isBefore(from)) {
                log.warn("Lease {} line {} has no usable recognition window ({} – {}); skipped",
                        lease.getId(), line.getId(), from, to);
                continue;
            }

            RentSegment segment = new RentSegment();
            segment.setTenantId(lease.getTenantId());
            segment.setLease(lease);
            segment.setLeaseLine(line);
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
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) return List.of();
        return entries.findByTenantIdAndStatusAndPeriodEndLessThanEqualOrderByPeriodEndAsc(
                tenantId, RecognitionStatus.PLANNED, to);
    }

    /**
     * The period lock, read straight from the repository rather than through
     * {@code TenantFiscalSettingsService.get()}, which creates the settings row
     * on first access — a write this must not perform from a read-only path.
     */
    private LocalDate booksLockedThrough() {
        UUID tenantId = TenantContextHolder.getTenantId();
        return tenantId == null ? null : fiscalSettings.findById(tenantId)
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
                .map(RecognitionEntry::getJournalId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, String> numbers = new java.util.HashMap<>();
        if (!journalIds.isEmpty()) {
            journals.findAllById(journalIds)
                    .forEach(j -> numbers.put(j.getId(), j.getEntryNumber()));
        }
        return rows.stream().map(r -> toDto(r, r.getJournalId() == null ? null : numbers.get(r.getJournalId()))).toList();
    }

    /**
     * The row as it now stands, built from the journal rather than re-read.
     *
     * <p>{@link RecognitionPoster} committed in a transaction of its own, so the
     * copy of the entry this transaction is holding is <em>stale</em>: still
     * PLANNED, still journal-less. Mapping it straight would answer a month-end
     * run with a list of rows that claim they were not posted — and re-reading it
     * would get the same instance back out of the first-level cache anyway.</p>
     */
    private static RecognitionEntryDTO posted(RecognitionEntry r, JournalEntry cil) {
        return new RecognitionEntryDTO(
                r.getId(),
                idOf(r.getLease(), Lease::getId),
                idOf(r.getSegment(), RentSegment::getId),
                r.getPeriodStart(), r.getPeriodEnd(), r.getDays(), r.getAmount(),
                RecognitionStatus.POSTED, cil.getId(), cil.getEntryNumber(), cil.getPostedAt());
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
