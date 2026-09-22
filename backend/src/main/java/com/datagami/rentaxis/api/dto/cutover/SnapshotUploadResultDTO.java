package com.datagami.rentaxis.api.dto.cutover;

import com.datagami.rentaxis.core.service.cutover.OpeningBalanceService;

import java.math.BigDecimal;
import java.util.List;

/**
 * What one trial-balance upload did.
 *
 * <p>{@code unmatchedCodes} are codes stored but not recognised — PACT accounts our
 * chart has no equivalent for. They are not an error: they appear on the
 * reconciliation report so the accountant can decide whether to create the account
 * or ignore the balance. {@code problems} are lines that could not be read at all,
 * each carrying the file line number.</p>
 */
public record SnapshotUploadResultDTO(int stored, List<String> unmatchedCodes, List<String> problems,
                                      BigDecimal totalDebit, BigDecimal totalCredit, boolean balanced) {

    public static SnapshotUploadResultDTO of(OpeningBalanceService.SnapshotUploadResult r) {
        return new SnapshotUploadResultDTO(r.stored(), r.unmatchedCodes(), r.problems(),
                r.totalDebit(), r.totalCredit(), r.balanced());
    }
}
