package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Break-it round 1 (money) F4: how far ahead a user may date a manual journal-type
 * posting — a journal voucher, a payment or receipt voucher, a cash receipt, a
 * bad-debt recovery, a payment run, or a reversal of one.
 *
 * <p>Journal numbers carry a two-digit year ({@code JV-26/1};
 * {@link EntryNumberService}), and the golden replay pins that format. So a
 * one-digit typo in the year — 2126 for 2026 — drew 2026's counter row keyed by
 * fiscal year 2126, restarted at JV-26/1, and collided with a number already on
 * the books: "conflicts with existing related records (uq_journal_entries_number)",
 * every time. Dates a century out are never meant; the ruling is at most one year
 * after today. Contract-driven dates are not affected — the 50-year term cap bounds
 * those.</p>
 *
 * <p>"Today" is the application's {@link Clock} (app.time-zone, Asia/Dubai), the
 * same one the rest of the services read — so a test's fixed clock moves it too.
 * Services take this bean by setter injection and fall back to {@link #system()}
 * when built by hand in a unit test.</p>
 */
@Component
public class ManualPostingDates {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Dubai");

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final Clock clock;

    @Autowired
    public ManualPostingDates(Clock clock) {
        this.clock = clock;
    }

    /** The rule on the wall clock in Asia/Dubai, for code built outside Spring. */
    public static ManualPostingDates system() {
        return new ManualPostingDates(Clock.system(BUSINESS_ZONE));
    }

    /** The last date a manual posting may carry: one year after today. */
    public LocalDate latestAllowed() {
        return LocalDate.now(clock).plusYears(1);
    }

    /** Today on the app clock (Asia/Dubai). */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * The "not after today" question without the refusal, for a caller that collects
     * one problem per row (the batch deposit on each cheque's own date). Null is not.
     */
    public boolean isAfterToday(LocalDate date) {
        return date != null && date.isAfter(today());
    }

    /**
     * Break-it round 2 (money2) F1/F2/F3: the one "not after today" rule for events
     * that record something that has already happened — a cheque deposited, cleared
     * or bounced, a debt written off, a period locked. Batch Clear had it on its own;
     * every such path now asks here. A null date is left to the caller.
     *
     * @param what the date, for the sentence ("clearing", "bounce", "write-off")
     * @param why  what the user should know, e.g. "Funds cannot have cleared yet, and nothing was cleared."
     */
    public void requireNotAfterToday(LocalDate date, String what, String why) {
        if (!isAfterToday(date)) return;
        LocalDate today = today();
        {
            throw new BusinessRuleViolationException(
                    "The " + what + " date " + date.format(DMY) + " is in the future (today is "
                            + today.format(DMY) + "). " + why,
                    "date.inFuture",
                    Map.of("what", what, "date", date.format(DMY), "today", today.format(DMY)));
        }
    }

    /**
     * Refuses a date more than a year ahead with a 400 the user can act on. A null
     * date is left to the caller's own "a date is required" rule.
     *
     * @param what the document, for the sentence ("A journal voucher", "A cash receipt")
     */
    public void requireWithinAYear(LocalDate date, String what) {
        if (date == null) return;
        LocalDate latest = latestAllowed();
        if (date.isAfter(latest)) {
            throw new BusinessRuleViolationException(
                    what + " cannot be dated more than a year ahead: " + date.format(DMY)
                            + " is after " + latest.format(DMY) + ". Check the year.",
                    "posting.dateTooFarAhead",
                    Map.of("date", date.format(DMY), "latest", latest.format(DMY)));
        }
    }
}
