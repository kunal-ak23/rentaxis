package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * "Is this money late?" — the one place the answer is computed, so the register
 * screen, the renter's portal and the reminder job cannot disagree about whether
 * a given cheque is overdue (spec §7.4).
 *
 * <p>These are pure functions of the row plus today's date: nothing here reads
 * the clock or the database, so a caller rendering an as-of-date view passes the
 * date it means.</p>
 */
public final class ChequeDueRules {

    private ChequeDueRules() {
    }

    /**
     * The cheque's money is owed now.
     *
     * <p>A matured registered or deposited cheque is due because the funds have not
     * arrived. A bounced cheque is due <em>whatever its date</em>: it already failed,
     * so the debt is live from that moment and does not wait for a calendar date to
     * pass.</p>
     */
    public static boolean due(Cheque cheque, LocalDate today) {
        ChequeStatus status = cheque.getStatus();
        if (status == ChequeStatus.BOUNCED) {
            return true;
        }
        return (status == ChequeStatus.REGISTERED || status == ChequeStatus.DEPOSITED)
                && !cheque.getChequeDate().isAfter(today);
    }

    /**
     * Due and past the lease's grace period. The grace window runs from the cheque
     * date, so {@code chequeDate + grace} is the last acceptable day and only the
     * day after it counts as overdue.
     */
    public static boolean overdue(Cheque cheque, int graceDays, LocalDate today) {
        return due(cheque, today) && cheque.getChequeDate().plusDays(graceDays).isBefore(today);
    }

    /**
     * Days elapsed since the grace period ended, floored at zero.
     *
     * <p>Not gated on {@link #due}: a cheque's lateness is a property of its date,
     * and callers that only want it for overdue rows ask {@link #overdue} first. It
     * never goes negative, so "3 days overdue" and "not yet late" are distinguished
     * by zero rather than by a sign the UI has to interpret.</p>
     */
    public static int daysOverdue(Cheque cheque, int graceDays, LocalDate today) {
        long days = ChronoUnit.DAYS.between(cheque.getChequeDate().plusDays(graceDays), today);
        return (int) Math.max(0, days);
    }
}
