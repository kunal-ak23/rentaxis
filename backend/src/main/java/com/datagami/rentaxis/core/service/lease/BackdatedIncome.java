package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Back-dated contracts (client call, Nov 2026). A contract posted today whose
 * term began months ago — typically the renewal of a tenancy that was held over
 * while the renter could not be reached — must not put the income of those months
 * into months whose books are already closed and reconciled. The poster picks the
 * month income starts from: every month before it is recognised in one catch-up on
 * the posting date ({@link RecognitionService#foldBeforeIncomeFrom}).
 *
 * <p>The choice runs from the term's first month (no folding: each month in its own
 * month, the old behaviour) to the contract date's month (the default: everything
 * that has passed lands in the month the contract is posted). Never a month inside
 * the period lock, and never later than the contract date's month — the advance
 * rent the catch-up releases is only on the books from the contract date.</p>
 */
@Service
public class BackdatedIncome {

    private static final DateTimeFormatter MMYYYY = DateTimeFormatter.ofPattern("MM/yyyy");

    private final LeaseRepository leases;
    private final LeaseAccessPolicy access;
    private final RecognitionService recognition;

    public BackdatedIncome(LeaseRepository leases, LeaseAccessPolicy access, @Lazy RecognitionService recognition) {
        this.leases = leases;
        this.access = access;
        this.recognition = recognition;
    }

    /**
     * What the Post dialog offers: whether the contract is back-dated, the months
     * income may start from, the default, and what the catch-up for
     * {@code requested} (or the default) would be.
     */
    public record Options(boolean backdated, LocalDate earliest, LocalDate latest, LocalDate defaultFrom,
                          LocalDate incomeFrom, LocalDate catchUpOn, int foldedMonths, BigDecimal foldedAmount,
                          List<String> monthsNamed) { }

    @Transactional(readOnly = true)
    public Options options(UUID leaseId, LocalDate requested) {
        Lease lease = leases.findByIdScopedToTenant(leaseId).orElseThrow(() -> new NotFoundException("Lease not found"));
        access.requireReadable(lease);
        if (!isBackdated(lease)) {
            return new Options(false, null, null, null, null, null, 0, BigDecimal.ZERO.setScale(2), List.of());
        }
        LocalDate earliest = earliest(lease, recognition.booksLockedThroughForStatus());
        LocalDate latest = monthOf(lease.getContractDate());
        LocalDate from = requested == null ? latest : resolve(lease, requested);
        LocalDate on = catchUpOn(lease, from);
        RecognitionService.LockedCatchUp fold = recognition.previewFoldBeforeIncomeFrom(leaseId, from, on);
        return new Options(true, earliest, latest, latest, from, on, fold.months(), fold.amount(), fold.monthsNamed());
    }

    /**
     * The validated first-of-month income starts from, or null when nothing is to be
     * folded (the contract is not back-dated, or the poster chose its first month).
     *
     * @throws BusinessRuleViolationException when the month is outside the choice.
     */
    @Transactional(readOnly = true)
    public LocalDate resolve(Lease lease, LocalDate requested) {
        if (requested == null) return null;
        LocalDate from = monthOf(requested);
        if (!isBackdated(lease)) {
            if (!from.isAfter(monthOf(lease.getStartDate()))) return null;
            throw new BusinessRuleViolationException("Income can only start later than the contract's first month when"
                    + " the contract is back-dated (it starts before the month of its contract date).");
        }
        LocalDate latest = monthOf(lease.getContractDate());
        LocalDate earliest = earliest(lease, recognition.booksLockedThroughForStatus());
        if (from.isAfter(latest)) {
            throw new BusinessRuleViolationException("Income cannot start after " + latest.format(MMYYYY)
                    + ", the month of the contract date.");
        }
        if (from.isBefore(earliest)) {
            throw new BusinessRuleViolationException("Income cannot start before " + earliest.format(MMYYYY)
                    + (earliest.isAfter(monthOf(lease.getStartDate())) ? ": the months before it are closed." : "."));
        }
        return from.equals(monthOf(lease.getStartDate())) ? null : from;
    }

    /**
     * The day the catch-up is dated: the contract date when income starts in its
     * month (the posting date the client asked for), otherwise the last day of the
     * chosen month — never earlier than that month.
     */
    public static LocalDate catchUpOn(Lease lease, LocalDate incomeFrom) {
        if (incomeFrom == null) return lease.getContractDate();
        if (YearMonth.from(incomeFrom).equals(YearMonth.from(lease.getContractDate()))) return lease.getContractDate();
        return YearMonth.from(incomeFrom).atEndOfMonth();
    }

    /** The term started before the month of the contract date. */
    public static boolean isBackdated(Lease lease) {
        return lease.getStartDate() != null && lease.getContractDate() != null
                && lease.getStartDate().isBefore(monthOf(lease.getContractDate()));
    }

    /** The first month income may start from: the term's first month, or the first month after the lock. */
    static LocalDate earliest(Lease lease, LocalDate lockedThrough) {
        LocalDate start = monthOf(lease.getStartDate());
        if (lockedThrough == null) return start;
        LocalDate open = monthOf(lockedThrough.plusDays(1));
        if (!lockedThrough.plusDays(1).equals(open)) open = open.plusMonths(1);
        return open.isAfter(start) ? open : start;
    }

    static LocalDate monthOf(LocalDate d) {
        return d.withDayOfMonth(1);
    }
}
