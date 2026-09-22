package com.datagami.rentaxis.api.dto.lease;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Terminate a contract on a date (spec §9.1).
 *
 * <p><b>The two cheque lists are a decision, not a filter.</b> The preview offers
 * a default split — uncleared instruments dated after {@code terminationDate} go
 * back to the renter, ones dated on or before it are kept for collection — and
 * finance flips whichever rows it wants before confirming. What comes back here
 * is therefore the whole answer: <em>every</em> uncleared row of the lease must
 * appear in exactly one of the two lists, or the request is refused naming the
 * rows that are missing. A partial list would be read as "return these and do
 * whatever you like with the rest", and "whatever you like" here means reversing
 * a registration or not reversing it.</p>
 *
 * <p>Both lists empty (or absent) means "use the default split", which is what a
 * caller that simply accepted the preview sends.</p>
 *
 * @param terminationDate {@code T} — inside the lease's term, outside any locked
 *        period. Every journal the termination writes is dated this day.
 * @param returnChequeIds uncleared rows to hand back (each gets its {@code PDR}
 *        reversed and goes RETURNED).
 * @param keepChequeIds   uncleared rows to keep for collection; they stay exactly
 *        as they are, and their money stays owed.
 * @param notes           free text kept on the lease and on its event trail.
 */
public record TerminateLeaseRequest(@NotNull LocalDate terminationDate,
                                    List<UUID> returnChequeIds,
                                    List<UUID> keepChequeIds,
                                    String notes) {

    public List<UUID> returnChequeIdsOrEmpty() {
        return returnChequeIds == null ? List.of() : returnChequeIds;
    }

    public List<UUID> keepChequeIdsOrEmpty() {
        return keepChequeIds == null ? List.of() : keepChequeIds;
    }
}
