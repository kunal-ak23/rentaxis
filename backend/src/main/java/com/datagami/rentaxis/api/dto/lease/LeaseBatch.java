package com.datagami.rentaxis.api.dto.lease;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The renewal season's batch requests and their per-contract answers (scale spec #16, #17).
 *
 * <p>A batch is a list of contracts and one set of terms. Each contract is processed by the
 * same single-contract service the detail page calls ({@code LeaseRenewalService.renew},
 * {@code LeaseTerminationService.preview/terminate}), each in a transaction of its own, so a
 * batch of N is exactly N single acts: one contract refused does not stop or undo the others,
 * and the refusal comes back on that contract's row in the words the single act would use.</p>
 */
public final class LeaseBatch {

    /** The most contracts one call takes; the screen sends the season in chunks. */
    public static final int MAX_LEASES = 100;

    private LeaseBatch() {
    }

    /**
     * How the rent moves on every renewal of a bulk renew.
     *
     * <ul>
     *   <li>{@code NONE} — last year's rent (the single renew's NONE, unchanged: no rounding);</li>
     *   <li>{@code PERCENT} — the single renew's PERCENT: base × (1 + p/100), whole AED half up,
     *       and only when the new term is as long as the old;</li>
     *   <li>{@code ADD} — base + {@code amount}, whole AED half up, drafted as the single renew's
     *       AMOUNT with that figure. {@code amount} may be negative (a reduction).</li>
     * </ul>
     * The base is the single renew's: the contract rent the renter pays (headline less discount,
     * after any credit addendum), before rent-free concessions, none of which renew.
     */
    public record RentRule(@NotNull(message = "Choose how the rent changes") Mode mode,
                           BigDecimal percent,
                           BigDecimal amount) {
        public enum Mode { NONE, PERCENT, ADD }
    }

    /**
     * Bulk renew (scale #16): draft one successor per contract, for review.
     *
     * @param termMonths      the new term in whole months; null = as long as the current term
     *                        (the single renew dialog's proposal)
     * @param contractDate    the successors' contract date; null = the single renew's default
     * @param generateGrid    also cut each draft's cheque grid with the defaults the draft editor's
     *                        "Generate cheques" uses ({@code ChequeGenerationService.generate(id, null)})
     * @param additionalLines charges added to every renewal (e.g. a renewal fee), as on the single renew
     */
    public record RenewRequest(@NotEmpty(message = "Choose at least one contract")
                               @Size(max = MAX_LEASES, message = "At most " + MAX_LEASES + " contracts at a time")
                               List<@NotNull UUID> leaseIds,
                               @NotNull(message = "Choose how the rent changes") @Valid RentRule rule,
                               Integer termMonths,
                               LocalDate contractDate,
                               boolean carryDepositForward,
                               boolean generateGrid,
                               List<@Valid LeaseLineInput> additionalLines) {
    }

    /**
     * One contract's answer to a bulk renew.
     *
     * @param renewalLeaseId the DRAFT successor, when {@code ok}
     * @param previousRent   the base the rule was applied to (null when unchanged by request)
     * @param newRent        the successor's contract rent
     */
    public record RenewRow(UUID leaseId, boolean ok, UUID renewalLeaseId, LocalDate startDate, LocalDate endDate,
                           BigDecimal previousRent, BigDecimal newRent, BigDecimal changePercent,
                           int chequeRows, String code, Map<String, Object> args, String message) {
    }

    /**
     * Bulk terminate (scale #17, PACT "Bulk Terminate"): the contracts to end, and the date.
     *
     * @param terminationDate null = each contract's own end date (the season's case: the
     *                        contracts ending on a day, ended on it)
     * @param notes           kept on each lease and its event trail, as on the single terminate
     */
    public record TerminateRequest(@NotEmpty(message = "Choose at least one contract")
                                   @Size(max = MAX_LEASES, message = "At most " + MAX_LEASES + " contracts at a time")
                                   List<@NotNull UUID> leaseIds,
                                   LocalDate terminationDate,
                                   String notes) {
    }

    /** One contract's termination preview: the single preview, or why there is none. */
    public record TerminatePreviewRow(UUID leaseId, boolean ok, LocalDate terminationDate,
                                      TerminationPreviewDTO preview,
                                      String code, Map<String, Object> args, String message) {
    }

    /** One contract's answer to a bulk terminate. */
    public record TerminateRow(UUID leaseId, boolean ok, LocalDate terminationDate, String status,
                               UUID terminationJournalId, String code, Map<String, Object> args, String message) {
    }
}
