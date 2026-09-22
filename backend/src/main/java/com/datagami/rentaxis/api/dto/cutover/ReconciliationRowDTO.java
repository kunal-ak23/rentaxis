package com.datagami.rentaxis.api.dto.cutover;

import com.datagami.rentaxis.core.service.cutover.OpeningBalanceService;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of the reconciliation report (spec §10.3).
 *
 * <p>Both balances are signed debit-positive, the same convention
 * {@code TrialBalanceRowDTO.balance} uses, so a credit-balance account reads
 * negative on both sides and the difference still means what it says:
 * {@code derivedBalance − pactBalance}. {@code accountId} is null for a PACT code
 * our chart has no account for — the row is kept so nothing is silently lost.</p>
 */
public record ReconciliationRowDTO(UUID accountId, String code, String name, boolean derived,
                                   BigDecimal derivedBalance, BigDecimal pactBalance, BigDecimal difference) {

    public static ReconciliationRowDTO of(OpeningBalanceService.ReconciliationRow r) {
        return new ReconciliationRowDTO(r.accountId(), r.code(), r.name(), r.derived(),
                r.derivedBalance(), r.pactBalance(), r.difference());
    }
}
