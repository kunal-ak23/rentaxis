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
 */
public record PostLeaseDryRunResponse(boolean ok,
                                      List<String> errors,
                                      BigDecimal contractValue,
                                      BigDecimal contractValueInclVat,
                                      BigDecimal chequeTotal,
                                      JournalPlan journals) {

    /** The journals the post would write: one TCO of {@code tcoLines} lines, and {@code pdr} PDRs. */
    public record JournalPlan(int tco, int tcoLines, int pdr) {
    }
}
