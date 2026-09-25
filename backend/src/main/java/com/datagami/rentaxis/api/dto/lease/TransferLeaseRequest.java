package com.datagami.rentaxis.api.dto.lease;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Spec 2026-09-24 §2: move the renter to another unit mid-lease. Creates the
 * successor as a DRAFT; nothing is written to the ledger until it is posted.
 *
 * @param moveDate           T, the last night in the current unit
 * @param endDate            the new term's end (default: the current lease's end)
 * @param contractDate       the new contract's date (default: T + 1)
 * @param lines              the new term's lines (default: the current recurring lines at the
 *                           current day rate for the new term's days; the deposit is carried)
 * @param chequeDispositions each uncleared row of the current lease: CARRY | KEEP | RETURN
 *                           (default: CARRY when dated after T, else KEEP)
 * @param rent               with no {@code lines}: the new term's rent in place of the suggested one
 */
public record TransferLeaseRequest(LocalDate moveDate, UUID targetUnitId, LocalDate endDate, LocalDate contractDate,
                                   List<LeaseLineInput> lines, List<ChequeDisposition> chequeDispositions,
                                   java.math.BigDecimal rent) {

    public TransferLeaseRequest(LocalDate moveDate, UUID targetUnitId, LocalDate endDate, LocalDate contractDate,
                                List<LeaseLineInput> lines, List<ChequeDisposition> chequeDispositions) {
        this(moveDate, targetUnitId, endDate, contractDate, lines, chequeDispositions, null);
    }

    public record ChequeDisposition(UUID chequeId, String disposition) {
    }
}
