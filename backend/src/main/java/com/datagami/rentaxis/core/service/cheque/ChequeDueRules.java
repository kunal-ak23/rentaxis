package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;

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

    /** The business day is the UAE's, whatever zone the JVM runs in. */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Dubai");

    /**
     * The statuses a Tenant can still settle: an uncleared instalment they have not
     * handed over, a checkout they started and may start again, and a bounce. A
     * DEPOSITED cheque is absent on purpose — the paper is at the bank and will
     * clear there; collecting it twice is what the register exists to prevent.
     */
    public static final Set<ChequeStatus> TENANT_COLLECTABLE = EnumSet.of(
            ChequeStatus.REGISTERED, ChequeStatus.BOUNCED, ChequeStatus.ONLINE_PENDING);

    private ChequeDueRules() {
    }

    /** Today's date in {@link #BUSINESS_ZONE}. */
    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(BUSINESS_ZONE));
    }

    /**
     * The cheque's money is owed now.
     *
     * <p>A matured registered or deposited cheque is due because the funds have not
     * arrived. A bounced cheque is due <em>whatever its date</em>: it already failed,
     * so the debt is live from that moment and does not wait for a calendar date to
     * pass.</p>
     *
     * <p><b>{@code ONLINE_PENDING} is due too.</b> A renter who opens checkout and
     * closes the browser tab leaves the row there — Razorpay sends no
     * {@code payment.failed} for an abandoned order and there is no expiry sweep —
     * and an authorisation is not money: nothing has posted, the instalment is
     * exactly as unpaid as it was a minute earlier. Leaving it out made the row
     * vanish from the due list, the aging report and the settlement preview's
     * arrears all at once, so a settlement would under-deduct by that instalment and
     * every screen would agree, because they all forgot the same state. Chasing it
     * is a separate question from owing it: {@code NotificationScheduler} keeps
     * quiet while a checkout younger than its window is open.</p>
     */
    public static boolean due(Cheque cheque, LocalDate today) {
        ChequeStatus status = cheque.getStatus();
        if (status == ChequeStatus.BOUNCED) {
            return true;
        }
        return (status == ChequeStatus.REGISTERED || status == ChequeStatus.DEPOSITED
                || status == ChequeStatus.ONLINE_PENDING)
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

    /**
     * The Tenant's side of {@link #due}: the row is owed now <em>and</em> the Tenant
     * can still pay it. Where the landlord's {@code due} keeps a DEPOSITED cheque
     * because the funds have not arrived, the Tenant has already handed the paper
     * over — nothing is payable by them, so nothing is due from them. A post-dated
     * cheque the landlord is holding is not owed before its date.
     */
    public static boolean tenantOwes(Cheque cheque, LocalDate today) {
        return due(cheque, today) && TENANT_COLLECTABLE.contains(cheque.getStatus());
    }

    /**
     * The Tenant's side of {@link #overdue}: something is payable by them and the
     * grace window has closed. Without the first half the portal told a Tenant they
     * owed AED 0 and were 84 days late on the same card (tutorial bug 2026-09-28-03).
     */
    public static boolean tenantOverdue(Cheque cheque, int graceDays, LocalDate today) {
        return tenantOwes(cheque, today) && overdue(cheque, graceDays, today);
    }
}
