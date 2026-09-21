package com.datagami.rentaxis.core.service.recognition;

import com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.lease.LeaseChequeRegistrar;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.RecognitionEntry;
import com.datagami.rentaxis.domain.entity.RentSegment;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.RecognitionEntryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Turns one {@code PLANNED} recognition entry into one {@code CIL} journal, in a
 * transaction of its own.
 *
 * <p><b>Why this is a bean and not a method on {@code RecognitionService}.</b>
 * A month-end run walks every planned row of every lease, and one bad row — a
 * lease whose income account was retired, a property that lost its advance-rent
 * mapping — must cost that row and nothing else. {@code REQUIRES_NEW} is what
 * gives each row its own commit boundary, and Spring's transaction advice lives
 * on the proxy: a {@code REQUIRES_NEW} method called from a sibling method of
 * the same class runs in the caller's transaction, so the first failure would
 * roll back the whole night's work. Separating the bean is the cheapest way to
 * go through the proxy.</p>
 *
 * <p><b>The row is locked <em>and refreshed</em>, not merely re-read.</b> The
 * nightly job and a hand-run close are exactly the pair that would otherwise both
 * see {@code PLANNED} and both post — and a locking finder alone does not stop
 * them, because it answers from the first-level cache for a row the transaction
 * has already loaded. See {@link #lock}.</p>
 *
 * <p><b>The debit follows the line, not the role.</b> The deferral was credited
 * when the {@code TCO} was posted, to whatever account that line named; the
 * release has to debit the very same leaf or the two halves of one contract sit
 * in different ledgers. The property's {@code ADVANCE_RENT} mapping is the
 * normal answer and almost always the same account — but a line with a manual
 * override, or a mapping edited since the lease was posted, is exactly the case
 * where "resolve the role again" silently strands the deferral.</p>
 */
@Component
public class RecognitionPoster {

    /** "Advance rent adjustment – Sep 2026" (spec §8.3). */
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private final RecognitionEntryRepository entries;
    private final LeaseLineRepository leaseLines;
    private final PostingService postingService;
    private final AccountResolver accountResolver;
    private final EntityManager entityManager;

    public RecognitionPoster(RecognitionEntryRepository entries,
                             LeaseLineRepository leaseLines,
                             PostingService postingService,
                             AccountResolver accountResolver,
                             EntityManager entityManager) {
        this.entries = entries;
        this.leaseLines = leaseLines;
        this.postingService = postingService;
        this.accountResolver = accountResolver;
        this.entityManager = entityManager;
    }

    /**
     * Claim this entry, post its {@code CIL} and mark it POSTED. Runs in its own
     * transaction, so a failure here leaves the rest of the run — and the row
     * itself — untouched.
     *
     * @return the row as it now stands. Built here rather than by the caller
     *         because this transaction is the only one that can see the committed
     *         truth: the caller's copy of the entry, if it has one, is stale the
     *         moment this returns, and re-reading it hands back the same
     *         first-level-cache instance.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RecognitionEntryDTO post(UUID entryId) {
        // Through `this`, so the body runs in the REQUIRES_NEW transaction this
        // method just opened rather than opening a third one.
        return postJoining(entryId);
    }

    /**
     * The same posting, in the <em>caller's</em> transaction.
     *
     * <p>Exists for exactly one caller: a termination, which reverses the {@code
     * CIL} for the month containing {@code T} and reposts a truncated replacement
     * (spec §8.5). A termination is all-or-nothing — if the unearned-rent {@code
     * TCR} that follows is refused, no cheque may stay RETURNED and no replacement
     * {@code CIL} may stay in the ledger — and {@link #post}'s {@code REQUIRES_NEW}
     * would have committed that replacement independently of the transaction that
     * then rolled back. A month-end run wants the opposite guarantee, so both
     * exist and each names which one it is.</p>
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public RecognitionEntryDTO postJoining(UUID entryId) {
        RecognitionEntry entry = lock(entryId);
        // Checked under the lock, which is the whole point: the loser of a race
        // blocks on the SELECT above and re-reads the winner's committed status here.
        if (entry.getStatus() != RecognitionStatus.PLANNED) {
            throw new BusinessRuleViolationException(
                    "Recognition entry " + entryId + " is already " + entry.getStatus());
        }
        RentSegment segment = entry.getSegment();
        Lease lease = entry.getLease();

        JournalEntry cil = postingService.post(PostingRequest.ofPairs(
                JournalDocType.CIL,
                entry.getPeriodEnd(),
                "Advance rent adjustment – " + MONTH.format(entry.getPeriodEnd()),
                LeaseChequeRegistrar.dimensions(lease, null),
                JournalSourceType.RECOGNITION,
                entry.getId(),
                null,
                List.of(PostingRequest.pair(
                        new PostingRequest.Line(deferralOf(segment, lease), PostingRequest.Side.DR,
                                entry.getAmount(), null, null),
                        new PostingRequest.Line(incomeOf(lease), PostingRequest.Side.CR,
                                entry.getAmount(), null, null)))));

        entry.setStatus(RecognitionStatus.POSTED);
        entry.setJournalId(cil.getId());
        entry.setPostedAt(Instant.now());
        entries.save(entry);

        // The building, for the month-end page's grouping (spec §11). Read off the
        // lease this method already holds: one entry, one lease — the N+1 the batch
        // lookup in RecognitionService exists to avoid is a *page* of rows, and
        // there is no page here.
        Unit unit = lease.getUnit();
        Property property = unit == null ? null : unit.getProperty();
        return new RecognitionEntryDTO(entry.getId(), lease.getId(), segment.getId(),
                property == null ? null : property.getId(),
                property == null ? null : property.getNameEn(),
                unit == null ? null : unit.getUnitNumber(),
                entry.getPeriodStart(), entry.getPeriodEnd(), entry.getDays(), entry.getAmount(),
                RecognitionStatus.POSTED, cil.getId(), cil.getEntryNumber(), entry.getPostedAt());
    }

    /**
     * The row, claimed {@code FOR UPDATE} <em>and re-read</em>.
     *
     * <p>{@code refresh} rather than a locking finder, and the difference is the
     * whole point of the lock. A locking query still answers from the first-level
     * cache when the transaction has already loaded that row — so it takes the lock
     * and hands back the <em>stale</em> status, which is exactly the value the lock
     * exists to stop us acting on. That is not hypothetical here: {@link
     * #postJoining} runs in the <em>caller's</em> transaction, and its one caller —
     * a termination re-cutting the month containing {@code T} — has already loaded
     * the entry to compute the cut. A row that the nightly close committed as
     * POSTED in between would read PLANNED, and this would post a second {@code
     * CIL} for the same period. {@code refresh(…, PESSIMISTIC_WRITE)} does both
     * halves: {@code SELECT … FOR UPDATE}, then overwrite the instance from the row
     * it just locked. Same fix, same reason, as {@code RecognitionService.lock}.</p>
     */
    private RecognitionEntry lock(UUID entryId) {
        RecognitionEntry entry = entries.findById(entryId)
                .orElseThrow(() -> new NotFoundException("Recognition entry not found"));
        entityManager.refresh(entry, LockModeType.PESSIMISTIC_WRITE);
        return entry;
    }

    /**
     * The liability the {@code TCO} parked this rent in. The line's own credit
     * account when it differs from the property's {@code ADVANCE_RENT} mapping —
     * see the class note — and the role otherwise, so an unmapped property still
     * produces the ledger's own "map this role" refusal rather than a null.
     *
     * <p>The line is read by id and may be gone: an amendment deletes a posted
     * lease's lines, and changeset 86 lets the retired segment keep pointing at
     * one. A cancelled segment has no entries left to post, so this is
     * belt-and-braces rather than a live path.</p>
     *
     * <p>Public because a termination's unearned-rent {@code TCR} has to debit the
     * very same leaf this releases from, and answering that question twice in two
     * places is how the two halves of one contract end up in different ledgers.
     * Call it inside a transaction — it reads the line and resolves the role.</p>
     */
    public PostingRequest.AccountRef deferralOf(RentSegment segment, Lease lease) {
        Account lineAccount = leaseLines.findById(segment.getLeaseLineId())
                .map(LeaseLine::getCreditAccount).orElse(null);
        if (lineAccount == null) {
            return new PostingRequest.ByRole(AccountRole.ADVANCE_RENT);
        }
        Account mapped = accountResolver.resolveOrNull(AccountRole.ADVANCE_RENT, propertyIdOf(lease));
        return mapped != null && mapped.getId().equals(lineAccount.getId())
                ? new PostingRequest.ByRole(AccountRole.ADVANCE_RENT)
                : new PostingRequest.ById(lineAccount.getId());
    }

    /** The lease's own income account when it names one (spec §6.3), else the property's. */
    private static PostingRequest.AccountRef incomeOf(Lease lease) {
        return lease.getIncomeAccountId() != null
                ? new PostingRequest.ById(lease.getIncomeAccountId())
                : new PostingRequest.ByRole(AccountRole.RENTAL_INCOME);
    }

    private static UUID propertyIdOf(Lease lease) {
        return lease.getUnit() != null && lease.getUnit().getProperty() != null
                ? lease.getUnit().getProperty().getId() : null;
    }
}
