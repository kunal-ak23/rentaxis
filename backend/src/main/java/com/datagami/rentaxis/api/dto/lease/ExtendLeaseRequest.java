package com.datagami.rentaxis.api.dto.lease;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * Extend a lease that is already on the books (spec §6.7).
 *
 * <p>Additive, and that is the whole distinction from a renewal: the contract does
 * not end and restart, it gets longer. The original {@code TCO} stays POSTED and
 * is never reversed; the extension posts a <em>second</em> TCO for the new lines
 * only, and the renter's receivable simply carries more on it.</p>
 *
 * <p>{@code lines} are the extension's own charges — RENT for the new window,
 * FEE for anything that goes with it. A DEPOSIT is refused: a deposit is held for
 * the tenancy, and the tenancy is the same tenancy. Every RENT line covers
 * <em>the extension window</em> (the day after the current end date through
 * {@code newEndDate}), not the whole term, or per-day recognition would charge the
 * original months twice.</p>
 *
 * <p>{@code cheques} are the instruments that pay for it, and their total must
 * equal what the new lines charge including VAT — the same rule, with the same
 * arithmetic, that the lease's first post enforces against the grid. They are
 * registered immediately: there is no draft grid on a posted lease.</p>
 *
 * @param contractDate the extension's own contract date, which its TCO and the
 *                     new cheques' PDRs carry. Defaults to today.
 */
public record ExtendLeaseRequest(@NotNull(message = "The extension needs a new end date") LocalDate newEndDate,
                                 LocalDate contractDate,
                                 @NotEmpty(message = "At least one line is required")
                                 List<LeaseLineInput> lines,
                                 @NotEmpty(message = "At least one cheque row is required")
                                 List<ChequeRowInput> cheques) {
}
