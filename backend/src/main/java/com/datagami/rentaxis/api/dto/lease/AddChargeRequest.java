package com.datagami.rentaxis.api.dto.lease;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * Add a charge to a posted lease mid-term, as an addendum.
 *
 * <p>Additive, like an extension, but the end date does not move: every RENT line
 * covers {@code effectiveFrom} through the lease's current end date (or a window
 * inside it), and a fee is charged once. A DEPOSIT is refused. {@code cheques}
 * must total what the lines charge including VAT and are registered immediately.</p>
 *
 * @param contractDate the addendum's own date, which its TCO and the new cheques'
 *                     PDRs carry. Defaults to today.
 * @param ejariNumber  optional; blank means Ejari re-registration is pending.
 */
public record AddChargeRequest(@NotNull(message = "The addendum needs an effective date") LocalDate effectiveFrom,
                               LocalDate contractDate,
                               String ejariNumber,
                               String reason,
                               @NotEmpty(message = "At least one line is required")
                               List<LeaseLineInput> lines,
                               @NotEmpty(message = "At least one cheque row is required")
                               List<ChequeRowInput> cheques) {
}
