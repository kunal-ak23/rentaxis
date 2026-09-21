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
 */
public record OpeningBalanceRowDTO(UUID accountId, String code, String name, String accountType, UUID propertyId,
                                   boolean derived, AccountRole derivedRole, boolean computed,
                                   BigDecimal enteredDebit, BigDecimal enteredCredit) {

    public static OpeningBalanceRowDTO of(OpeningBalanceService.OpeningBalanceRow r) {
        return new OpeningBalanceRowDTO(r.accountId(), r.code(), r.name(), r.accountType(), r.propertyId(),
                r.derived(), r.derivedRole(), r.computed(), r.enteredDebit(), r.enteredCredit());
    }
}
