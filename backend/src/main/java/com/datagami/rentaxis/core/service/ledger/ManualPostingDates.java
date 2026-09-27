package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Break-it round 1 (money) F4: how far ahead a user may date a manual journal-type
 * posting — a journal voucher, a payment or receipt voucher, a cash receipt, or a
 * reversal of one.
 *
 * <p>Journal numbers carry a two-digit year ({@code JV-26/1};
 * {@link EntryNumberService}), and the golden replay pins that format. So a
 * one-digit typo in the year — 2126 for 2026 — drew 2026's counter row keyed by
 * fiscal year 2126, restarted at JV-26/1, and collided with a number already on
 * the books: "conflicts with existing related records (uq_journal_entries_number)",
 * every time. Dates a century out are never meant; the ruling is at most one year
 * after today in the business zone (Asia/Dubai). Contract-driven dates are not
 * affected — the 50-year term cap bounds those.</p>
 */
public final class ManualPostingDates {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Dubai");

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private ManualPostingDates() {
    }

    /** The last date a manual posting may carry: one year after today in Asia/Dubai. */
    public static LocalDate latestAllowed() {
        return LocalDate.now(BUSINESS_ZONE).plusYears(1);
    }

    /**
     * Refuses a date more than a year ahead with a 400 the user can act on. A null
     * date is left to the caller's own "a date is required" rule.
     *
     * @param what the document, for the sentence ("A journal voucher", "A cash receipt")
     */
    public static void requireWithinAYear(LocalDate date, String what) {
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
