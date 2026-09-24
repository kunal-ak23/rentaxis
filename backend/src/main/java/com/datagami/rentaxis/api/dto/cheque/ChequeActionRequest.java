package com.datagami.rentaxis.api.dto.cheque;

import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One lifecycle transition, as the register screen asks for it (spec §7.2).
 *
 * <p>One shape for deposit, clear, receive, bounce, cancel and return rather than
 * six, because the fields a transition needs are the same four and which of them
 * matters is the verb's business, not the payload's.</p>
 *
 * @param date the day the transition happened — the journal's entry date and the
 *        cheque's own {@code depositedAt}/{@code clearedAt}/{@code bouncedAt}/
 *        {@code returnedAt}. Defaults to today, because the common case is a
 *        clerk recording what just happened; a back-dated one is typed in and is
 *        still subject to the period lock.
 * @param notes free text kept on the row, and the reason on a reversal.
 * @param failureReason bounce only.
 * @param debitAccountId the bank or cash leaf this receipt actually landed in,
 *        overriding the one the row carries. Used on deposit (which bank the
 *        paper went to) and on clear/receive (where the funds arrived).
 */
public record ChequeActionRequest(LocalDate date,
                                  String notes,
                                  ChequeFailureReason failureReason,
                                  UUID debitAccountId,
                                  Boolean notOnStatement) {

    /** F14-20: without the "not on the statement" confirmation. */
    public ChequeActionRequest(LocalDate date, String notes, ChequeFailureReason failureReason, UUID debitAccountId) {
        this(date, notes, failureReason, debitAccountId, null);
    }

    public static ChequeActionRequest on(LocalDate date) {
        return new ChequeActionRequest(date, null, null, null);
    }

    /** Today, nothing overridden — what a controller passes for a bare POST. */
    public static ChequeActionRequest empty() {
        return new ChequeActionRequest(null, null, null, null);
    }

    /** The date the caller meant, with today's as the default. */
    public LocalDate dateOrToday() {
        return date != null ? date : LocalDate.now();
    }
}
