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
 */
public record OpeningBalanceRowDTO(UUID accountId, String code, String name, String accountType, UUID propertyId,
                                   boolean derived, AccountRole derivedRole,
                                   BigDecimal enteredDebit, BigDecimal enteredCredit) {

    public static OpeningBalanceRowDTO of(OpeningBalanceService.OpeningBalanceRow r) {
        return new OpeningBalanceRowDTO(r.accountId(), r.code(), r.name(), r.accountType(), r.propertyId(),
                r.derived(), r.derivedRole(), r.enteredDebit(), r.enteredCredit());
    }
}
