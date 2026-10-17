package com.datagami.rentaxis.api.dto.lease;

import java.math.BigDecimal;
import java.util.List;

/**
 * The answer to "what would happen if I posted this?" — every validation run,
 * nothing written.
 *
 * <p>It exists because posting is irreversible in the way that matters: the
 * correction for a wrong TCO is a TCR and a fresh TCO, which an auditor sees
 * forever. The review step before the button therefore has to be able to ask the
 * server, not guess from the form, and it has to get <em>all</em> the problems at
 * once rather than one refusal per attempt.</p>
 *
 * <p>{@code contractValue} is Σ of the lines' net, the figure the rest of the
 * codebase calls the contract value. {@code contractValueInclVat} is what
 * {@code chequeTotal} is actually required to equal — the two differ exactly when
 * some line carries VAT, and showing only one of them is how a grid that is
 * "short by 100" looks like a rounding bug.</p>
 *
 * <p>{@code depositCarriedForward} is what the renewal's {@code JV} would move off
 * the predecessor (spec §6.6), and zero for every lease that is not a renewal with
 * the flag set. It is shown because it is emphatically <em>not</em> the deposit
 * printed on last year's contract: a deposit partly refunded or partly forfeited
 * during the term carries forward at what is left of it, and the accountant
 * approving the renewal is the person who needs to notice the difference.</p>
 */
public record PostLeaseDryRunResponse(boolean ok,
                                      List<String> errors,
                                      BigDecimal contractValue,
                                      BigDecimal contractValueInclVat,
                                      BigDecimal chequeTotal,
                                      BigDecimal depositCarriedForward,
                                      JournalPlan journals,
                                      /* F15-13: the instruments a transfer brings onto this grid when it posts. */
                                      List<CarriedCheque> carriedCheques,
                                      /* PR #369 R1 P3-8: what the post will do that is not an error (e.g. a prepaid transfer's VAT timing). */
                                      List<String> notices) {

    public PostLeaseDryRunResponse(boolean ok, List<String> errors, BigDecimal contractValue, BigDecimal contractValueInclVat,
                                   BigDecimal chequeTotal, BigDecimal depositCarriedForward, JournalPlan journals,
                                   List<CarriedCheque> carriedCheques) {
        this(ok, errors, contractValue, contractValueInclVat, chequeTotal, depositCarriedForward, journals, carriedCheques, List.of());
    }

    public PostLeaseDryRunResponse(boolean ok, List<String> errors, BigDecimal contractValue, BigDecimal contractValueInclVat,
                                   BigDecimal chequeTotal, BigDecimal depositCarriedForward, JournalPlan journals) {
        this(ok, errors, contractValue, contractValueInclVat, chequeTotal, depositCarriedForward, journals, List.of());
    }

    /** F15-13: one carried cheque — the old lease's row, re-registered on the new grid at posting. */
    public record CarriedCheque(int seqNo, String chequeNumber, java.time.LocalDate chequeDate, BigDecimal amount) {
    }

    /**
     * The journals the post would write: one TCO of {@code tcoLines} lines, and {@code pdr} PDRs.
     *
     * @param transfer #100-f: on a transfer successor, the groups the transfer itself posts
     *                 (spec §2, "Postings, in order"); null for every other lease, so a
     *                 client that predates it reads the same three counts as before.
     */
    public record JournalPlan(int tco, int tcoLines, int pdr, TransferJournals transfer) {
        public JournalPlan(int tco, int tcoLines, int pdr) {
            this(tco, tcoLines, pdr, null);
        }
    }

    /**
     * #100-f: the transfer's journal groups, in posting order, estimated from the ledger
     * as it stands (nothing written).
     *
     * @param moveDate           T, the last day in the old unit
     * @param pdrReversals       PDR reversals on A, dated T — one per row leaving A's PDCs
     *                           (carried or returned)
     * @param pdrReversalTotal   Σ of those rows
     * @param unearned           the TCR on A, dated T: rent unearned after T
     * @param unearnedVat        the TCR's VAT on that rent (0 when A carries no VAT)
     * @param balanceCarried     C, carried to B by a JV on T + 1: &lt;0 prepaid (credited to
     *                           B), &gt;0 owed (charged to B); 0 means no JV
     * @param balanceCarriedDate the balance JV's date, T + 1
     * @param depositCarried     the deposit JV on B's contract date: what A still holds
     */
    public record TransferJournals(java.time.LocalDate moveDate,
                                   int pdrReversals,
                                   BigDecimal pdrReversalTotal,
                                   BigDecimal unearned,
                                   BigDecimal unearnedVat,
                                   BigDecimal balanceCarried,
                                   java.time.LocalDate balanceCarriedDate,
                                   BigDecimal depositCarried) {
    }
}
