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
 * as at the day before the books open. {@code difference} is
 * {@code totalDebit − totalCredit} over the entered figures, which is what the OB
 * journal will close against OPENING_BALANCE_DIFFERENCE; a non-zero one is normal,
 * not an error. {@code problems} are configuration faults (an account role with no
 * usable account behind it) that would make posting fail, listed so the screen can
 * say so before the accountant presses Post.</p>
 */
public record OpeningBalanceGridDTO(LocalDate asOf, boolean posted, UUID journalId, String journalNumber,
                                    List<OpeningBalanceRowDTO> rows, BigDecimal totalDebit,
                                    BigDecimal totalCredit, BigDecimal difference,
                                    List<String> problems) {

    public static OpeningBalanceGridDTO of(OpeningBalanceService.OpeningBalanceGrid g) {
        return new OpeningBalanceGridDTO(g.asOf(), g.posted(), g.journalId(), g.journalNumber(),
                g.rows().stream().map(OpeningBalanceRowDTO::of).toList(),
                g.totalDebit(), g.totalCredit(), g.difference(), g.problems());
    }
}
