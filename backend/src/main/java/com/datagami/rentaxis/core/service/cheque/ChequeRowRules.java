package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What makes a typed cheque row acceptable, wherever it is typed.
 *
 * <p>These rules started life inside {@code ChequeGenerationService}, which is
 * where the grid of a draft lease is edited. They live here now because the grid
 * is no longer the only way a row is created: a bounced cheque is replaced by one
 * or more rows on a lease that is already posted, and a replacement that slipped
 * past "a PDC needs the date written on it" would be a registered instrument with
 * no maturity — invisible to the register's due list and to every reminder that
 * reads it. One copy of the rules is the only way the two doors stay the same
 * door.</p>
 *
 * <p>Everything is checked up front rather than left to
 * {@code ux_cheques_lease_number}: the user who typed the same cheque number
 * twice wants to read "cheque number 100041 is already used on this lease", not a
 * 409 quoting an index name.</p>
 */
public final class ChequeRowRules {

    private ChequeRowRules() {
    }

    /**
     * The whole draft grid, as {@code ChequeGenerationService.saveRows} sends it:
     * rows carrying an id must name a DRAFT row of this lease, and no two rows may
     * claim the same id or the same cheque number.
     *
     * @param drafts the lease's DRAFT rows by id — the only rows an edit may address.
     * @param takenByOthers PDC numbers held by rows this save will not touch.
     */
    public static void validateGrid(List<ChequeRowInput> input,
                                    Map<UUID, Cheque> drafts,
                                    Set<String> takenByOthers) {
        Set<String> seenNumbers = new HashSet<>();
        Set<UUID> seenIds = new HashSet<>();
        for (int i = 0; i < input.size(); i++) {
            ChequeRowInput row = input.get(i);
            String where = "Row " + (i + 1) + ": ";
            if (row == null) throw new BusinessRuleViolationException(where + "is empty");

            // The same row twice would resolve to one entity, and the second copy
            // would overwrite the first — two rows the user typed silently
            // becoming one, with the money of whichever came last.
            if (row.id() != null && !seenIds.add(row.id())) {
                throw new BusinessRuleViolationException(where + "cheque " + row.id() + " appears twice in the grid");
            }

            if (row.id() != null && !drafts.containsKey(row.id())) {
                // Either it belongs to another lease or it has been registered.
                // Both are "not a draft row of this lease", and neither is a 404:
                // the lease exists, the body is wrong about one of its rows.
                throw new BusinessRuleViolationException(
                        where + "cheque " + row.id() + " is not a draft row of this lease");
            }

            validateRow(row, where, seenNumbers, takenByOthers);
        }
    }

    /**
     * Rows that are being <em>created</em> against a lease — the replacements for a
     * bounced cheque, a penalty collection, a late cash receipt. There is no draft
     * grid to address here, so an id on the row is meaningless and refused rather
     * than silently ignored: a caller that sent one meant to edit something.
     *
     * @param takenNumbers every PDC number already live on the lease.
     */
    public static void validateNewRows(List<ChequeRowInput> rows, Set<String> takenNumbers, String what) {
        if (rows == null || rows.isEmpty()) {
            throw new BusinessRuleViolationException("At least one " + what + " is required");
        }
        Set<String> seenNumbers = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            ChequeRowInput row = rows.get(i);
            String where = rows.size() == 1 ? "" : what.substring(0, 1).toUpperCase() + what.substring(1) + " " + (i + 1) + ": ";
            if (row == null) throw new BusinessRuleViolationException(where + "is empty");
            if (row.id() != null) {
                throw new BusinessRuleViolationException(
                        where + "a new row cannot carry the id of an existing cheque");
            }
            validateRow(row, where, seenNumbers, takenNumbers);
        }
    }

    /**
     * One row's own rules: a positive amount, a date the instrument matures on, and
     * a cheque number only where a cheque number means something.
     *
     * <p>{@code seenNumbers} is mutated — the caller passes the set it is
     * accumulating across the payload, so two rows in the same request collide with
     * each other and not only with what is already stored.</p>
     */
    static void validateRow(ChequeRowInput row, String where, Set<String> seenNumbers, Set<String> takenElsewhere) {
        ChequeMode mode = row.mode() == null ? ChequeMode.PDC : row.mode();
        if (mode == ChequeMode.ONLINE) {
            // An online receipt is created by the payment gateway callback with
            // its own reference, never typed into the grid.
            throw new BusinessRuleViolationException(
                    where + "ONLINE receipts are recorded by the payment gateway, not entered on the grid");
        }

        if (row.amount() == null || row.amount().signum() <= 0) {
            throw new BusinessRuleViolationException(where + "amount must be greater than zero");
        }
        if (row.chequeDate() == null) {
            throw new BusinessRuleViolationException(mode == ChequeMode.PDC
                    ? where + "a post-dated cheque needs the date written on it"
                    : where + "a " + mode + " receipt needs the date it is expected on");
        }

        String number = blankToNull(row.chequeNumber());
        if (number != null) {
            if (mode != ChequeMode.PDC) {
                throw new BusinessRuleViolationException(
                        where + "a " + mode + " receipt has no cheque number");
            }
            if (!seenNumbers.add(number) || (takenElsewhere != null && takenElsewhere.contains(number))) {
                throw new BusinessRuleViolationException(
                        where + "cheque number " + number + " is already used on this lease");
            }
        }
    }

    public static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
