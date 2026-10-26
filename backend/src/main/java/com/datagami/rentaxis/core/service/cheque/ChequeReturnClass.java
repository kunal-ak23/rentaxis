package com.datagami.rentaxis.core.service.cheque;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * PACT's return class of a cheque — the "BDDays" column of the Tenant Ledger (R1) and the class column of the
 * Return / Replace Cheque Register (R2), one definition for both (reports spec §2 and §5).
 *
 * <p>{@link #BOUNCED} (1) is a cheque whose bounce was posted (its CBR exists) on or before the report's as-of
 * date. {@link #REPLACED_BEFORE_DEPOSIT} (2) is a cheque swapped for another before it was ever deposited; no
 * flow records that yet (R2 phase B), so {@link #of} never produces it today. Anything else has no class, which
 * the reports print as a blank cell.</p>
 */
public enum ChequeReturnClass {

    BOUNCED(1),
    REPLACED_BEFORE_DEPOSIT(2);

    /** The number PACT prints. */
    public final int code;

    ChequeReturnClass(int code) {
        this.code = code;
    }

    /**
     * 1 ⇔ a CBR exists and the cheque bounced on or before {@code asOf}; else {@code null}. A bounce dated after
     * the as-of date had not happened yet as far as that report is concerned.
     *
     * @param cbrJournalId {@code cheques.cbr_journal_id}
     * @param bouncedAt    {@code cheques.bounced_at}, set together with the CBR
     * @param asOf         the last day the report covers; required
     */
    public static ChequeReturnClass of(UUID cbrJournalId, LocalDate bouncedAt, LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (cbrJournalId == null || bouncedAt == null) {
            return null;
        }
        return bouncedAt.isAfter(asOf) ? null : BOUNCED;
    }
}
