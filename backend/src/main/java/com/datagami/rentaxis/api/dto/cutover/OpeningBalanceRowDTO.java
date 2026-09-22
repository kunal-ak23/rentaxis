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
 * {@code PUT}, and it is {@code postDebit}/{@code postCredit} that carry that
 * balancing figure — which is why the rows on screen add up the way the journal
 * does.</p>
 *
 * <p><b>Three figures, and the screen needs all three</b> (ruling R25).</p>
 * <ul>
 *   <li><b>{@code enteredDebit}/{@code enteredCredit}</b> — the figure <em>as entered
 *       or uploaded</em>: what the accountant typed through {@code PUT}, or what
 *       PACT's trial balance carried for this account, with no arithmetic of ours
 *       applied. The edit inputs are seeded from this pair, so sending a row straight
 *       back changes nothing.</li>
 *   <li><b>{@code derivedDebit}/{@code derivedCredit}</b> — what <em>our</em> books
 *       already hold for this account as at the day before they open, with the
 *       opening journal's own lines taken back out. On a {@code derived} row that is
 *       the figure the contract import produced and the reason the row is read-only;
 *       on a manual row it is normally nothing, and anything else there is worth a
 *       second look.</li>
 *   <li><b>{@code postDebit}/{@code postCredit}</b> — what the journal will actually
 *       write: {@code entered − derived} on a manual account (ruling R17 — posting
 *       PACT's figure gross on the bank a cleared cheque reached, or the output VAT a
 *       contract raised, counted those twice), nothing on a {@code derived} one, and
 *       the balancing gap on the {@code computed} row. The grid's totals and its
 *       {@code difference} are the sum of these.</li>
 * </ul>
 *
 * <p>{@code post*} was added rather than folded into {@code entered*}: the first cut
 * of R17 put the remainder in the box the accountant edits, so saving the row back
 * unchanged stored the remainder as the new PACT figure and the books drifted one
 * subtraction further each time round.</p>
 */
public record OpeningBalanceRowDTO(UUID accountId, String code, String name, String accountType, UUID propertyId,
                                   boolean derived, AccountRole derivedRole, boolean computed,
                                   BigDecimal derivedDebit, BigDecimal derivedCredit,
                                   BigDecimal enteredDebit, BigDecimal enteredCredit,
                                   BigDecimal postDebit, BigDecimal postCredit) {

    public static OpeningBalanceRowDTO of(OpeningBalanceService.OpeningBalanceRow r) {
        return new OpeningBalanceRowDTO(r.accountId(), r.code(), r.name(), r.accountType(), r.propertyId(),
                r.derived(), r.derivedRole(), r.computed(), r.derivedDebit(), r.derivedCredit(),
                r.enteredDebit(), r.enteredCredit(), r.postDebit(), r.postCredit());
    }
}
