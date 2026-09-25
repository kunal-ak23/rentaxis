package com.datagami.rentaxis.api.dto.lease;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * Renew a lease: the terms of the successor contract (spec §6.6).
 *
 * <p>The successor is a DRAFT, not a posting. Renewal produces the same kind of
 * object the draft wizard does — editable lines, a cheque grid still to be cut,
 * a review step before anything reaches the ledger — because a renewal <em>is</em>
 * a new contract, merely one that knows which contract it replaces.</p>
 *
 * <p>{@code lines} null means "the same charges as last year": the predecessor's
 * lines are copied across and the accountant edits the amounts in the draft. That
 * is the common case by a distance, and making the caller re-send a list it just
 * read back is how a renewal quietly loses a charge nobody noticed was there. An
 * empty list is <em>not</em> the same thing and is refused, like any other lease
 * with no lines.</p>
 *
 * <p>{@code carryDepositForward} says the renter's deposit stays where it is
 * rather than being refunded and re-collected. No DEPOSIT line is copied, and on
 * <em>post</em> one {@code JV} moves whatever is left of the predecessor's deposit
 * onto the successor. It is false by default, which is the safe answer: a deposit
 * line the accountant did not want is visible on the draft, a carry-forward they
 * did not want is a journal.</p>
 */
public record RenewLeaseRequest(LocalDate contractDate,
                                @NotNull(message = "The renewal needs a start date") LocalDate startDate,
                                @NotNull(message = "The renewal needs an end date") LocalDate endDate,
                                List<LeaseLineInput> lines,
                                boolean carryDepositForward,
                                /* Spec §4a: how the rent moves; null = NONE. */
                                RentChange rentChange,
                                /* Spec §4a: the new registration, when already known. */
                                String ejariNumber,
                                /* Spec §4d: charges added to this renewal (e.g. a renewal fee). */
                                List<LeaseLineInput> additionalLines) {

    /** The shape before §4a/§4d. */
    public RenewLeaseRequest(LocalDate contractDate, LocalDate startDate, LocalDate endDate,
                             List<LeaseLineInput> lines, boolean carryDepositForward) {
        this(contractDate, startDate, endDate, lines, carryDepositForward, null, null, null);
    }

    /**
     * Spec §4a. {@code PERCENT}: new rent = round(headline × (1 + percent/100)) to
     * whole AED, same term length only. {@code AMOUNT}: the new headline rent as
     * typed. {@code NONE}: last year's headline.
     */
    public record RentChange(Mode mode, java.math.BigDecimal percent, java.math.BigDecimal newRentAmount) {
        public enum Mode { NONE, PERCENT, AMOUNT }
    }
}
