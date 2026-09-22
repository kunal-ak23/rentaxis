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
                                      JournalPlan journals) {

    /** The journals the post would write: one TCO of {@code tcoLines} lines, and {@code pdr} PDRs. */
    public record JournalPlan(int tco, int tcoLines, int pdr) {
    }
}
