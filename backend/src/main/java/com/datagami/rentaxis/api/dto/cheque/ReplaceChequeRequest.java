package com.datagami.rentaxis.api.dto.cheque;

import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;

import java.time.LocalDate;
import java.util.List;

/**
 * What the renter handed over after a cheque bounced (spec §7.2, BOUNCED →
 * REPLACED).
 *
 * <p>A list, not a row: a renter who could not honour 12,750 very often comes
 * back with 10,000 now and 2,000 next month, and forcing that into one instrument
 * would mean inventing a cheque nobody wrote. Each replacement registers in its
 * own right with its own {@code PDR}.</p>
 *
 * <p>The replacements may total <em>less</em> than the bounced cheque — the
 * difference simply stays in rent receivable, where the bounce put it, and shows
 * up as what the renter still owes. They may not total more: money the renter
 * does not owe on this lease is not a replacement, it is a separate receipt.</p>
 *
 * @param date the day the replacement was agreed — the reversal/registration date.
 * @param notes kept on the bounced row for the audit trail.
 */
public record ReplaceChequeRequest(List<ChequeRowInput> replacements, LocalDate date, String notes) {

    public LocalDate dateOrToday() {
        return date != null ? date : LocalDate.now();
    }
}
