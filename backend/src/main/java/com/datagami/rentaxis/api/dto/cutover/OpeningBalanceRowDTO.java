package com.datagami.rentaxis.api.dto.cutover;

import com.datagami.rentaxis.core.service.cutover.OpeningBalanceService;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of the opening-balance grid.
 *
 * <p>{@code derived} means the contract import produces this account's balance, so
 * the screen renders it read-only: the figures are shown, but the API refuses to
 * take one by hand (spec §10.3). {@code derivedRole} is what to tell the accountant
 * when they ask why.</p>
 *
 * <p>{@code computed} means <em>we</em> produce it: this is the opening-balance
 * difference account, whose figure is the balancing gap between all the other rows
 * and is recomputed every time the books are opened. Also read-only, also refused by
 * {@code PUT}, and its {@code enteredDebit}/{@code enteredCredit} carry the figure
 * the journal will post rather than anything PACT's file said — which is why the
 * rows on screen add up the way the journal does.</p>
 *
 * <p>{@code derivedDebit}/{@code derivedCredit} are what <em>our</em> books already
 * hold for this account as at the day before they open, with the opening journal's
 * own lines taken back out. On a {@code derived} row that is the figure the contract
 * import produced and the reason the row is read-only; on a manual row it is
 * normally nothing, and anything else there is worth a second look. They were
 * always {@code 0.00} until the bulk post existed to fill them.</p>
 *
 * <p><b>{@code enteredDebit}/{@code enteredCredit} are what a post would write</b>,
 * which is PACT's figure for the account <em>less</em> what our books already hold
 * (ruling R17). On almost every row our books hold nothing and that is simply PACT's
 * figure; on the two accounts the cut-over also writes to — the bank a cleared
 * cheque reached, the output VAT a contract raised — it is the remainder, and
 * posting PACT's figure gross there would have counted those twice. The pair
 * {@code derived*} + {@code entered*} therefore adds up to PACT, which is what the
 * screen should show.</p>
 */
public record OpeningBalanceRowDTO(UUID accountId, String code, String name, String accountType, UUID propertyId,
                                   boolean derived, AccountRole derivedRole, boolean computed,
                                   BigDecimal derivedDebit, BigDecimal derivedCredit,
                                   BigDecimal enteredDebit, BigDecimal enteredCredit) {

    public static OpeningBalanceRowDTO of(OpeningBalanceService.OpeningBalanceRow r) {
        return new OpeningBalanceRowDTO(r.accountId(), r.code(), r.name(), r.accountType(), r.propertyId(),
                r.derived(), r.derivedRole(), r.computed(), r.derivedDebit(), r.derivedCredit(),
                r.enteredDebit(), r.enteredCredit());
    }
}
