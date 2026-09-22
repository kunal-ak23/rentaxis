package com.datagami.rentaxis.api.dto.cutover;

import com.datagami.rentaxis.core.service.cutover.OpeningBalanceService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The opening-balance screen in one response.
 *
 * <p>{@code asOf} is the books start date minus one day — the balance is the balance
 * as at the day before the books open.</p>
 *
 * <p>{@code difference} is {@code totalDebit − totalCredit} over the <em>postable</em>
 * figures and is exactly what the OB journal puts on OPENING_BALANCE_DIFFERENCE —
 * both are read off one computation, so the number on screen is the number posted. A
 * non-zero one is normal, not an error. The totals exclude the accounts the contract
 * import derives and the difference account itself; that account's own row carries
 * the same figure, so the rows add up the way the journal does.</p>
 *
 * <p><b>Every figure here is the delta</b> — PACT's trial balance less what our own
 * books already hold as at the same day (ruling R17) — so {@code totalDebit},
 * {@code totalCredit} and {@code difference} are the journal's, not the file's: they
 * are the sums of the rows' {@code postDebit}/{@code postCredit}, never of their
 * {@code enteredDebit}/{@code enteredCredit} (ruling R25). The file's own sums live
 * on the upload result.</p>
 *
 * <p>{@code changedSincePosted} is true when the grid's postable lines no longer
 * match the live OB journal's. The snapshot stays editable after posting — that is
 * the Replace workflow — and this is how the screen knows to say "unposted changes".
 * It is false whenever nothing is posted. Because the lines are deltas it now also
 * catches a cut-over bulk post or batch reverse that happened after the books were
 * opened, which the gross form could not see at all.</p>
 *
 * <p>{@code problems} are faults the accountant has to fix, or facts they have to
 * know, before posting: an account role with no usable account behind it, or a PACT
 * figure on the difference account that we do not carry over.</p>
 */
public record OpeningBalanceGridDTO(LocalDate asOf, boolean posted, UUID journalId, String journalNumber,
                                    boolean changedSincePosted,
                                    List<OpeningBalanceRowDTO> rows, BigDecimal totalDebit,
                                    BigDecimal totalCredit, BigDecimal difference,
                                    List<String> problems) {

    public static OpeningBalanceGridDTO of(OpeningBalanceService.OpeningBalanceGrid g) {
        return new OpeningBalanceGridDTO(g.asOf(), g.posted(), g.journalId(), g.journalNumber(),
                g.changedSincePosted(),
                g.rows().stream().map(OpeningBalanceRowDTO::of).toList(),
                g.totalDebit(), g.totalCredit(), g.difference(), g.problems());
    }
}
