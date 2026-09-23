package com.datagami.rentaxis.api.dto.cheque;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One bank credit covering several deposited cheques, cleared in one act — the
 * counterpart of {@link DepositBatchRequest} (gap #57). The bank clears a deposit
 * slip as a single line on the statement, and sixteen Clear dialogs for one line
 * is how the register and the statement drift apart.
 *
 * @param chequeIds the ticked rows; every one must be DEPOSITED.
 * @param clearingDate the day the funds arrived. Defaults to today.
 * @param narration optional text kept on each row, exactly as a single clear's
 *        {@code notes}.
 */
public record ClearBatchRequest(List<UUID> chequeIds, LocalDate clearingDate, String narration) {

    public LocalDate dateOrToday() {
        return clearingDate != null ? clearingDate : LocalDate.now();
    }
}
