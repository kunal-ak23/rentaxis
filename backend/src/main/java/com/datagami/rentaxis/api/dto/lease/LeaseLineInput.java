package com.datagami.rentaxis.api.dto.lease;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One line as the caller submits it (spec §6.2).
 *
 * <p>The charge type may be named by {@code chargeTypeId} or by
 * {@code chargeTypeCode}; the id wins when both are given. The code form exists
 * for callers that have no catalogue in hand — the portfolio import, seeds and
 * tests all know they want "RENT" without knowing this tenant's row id for it.</p>
 *
 * <p>{@code creditAccountId} is an override. Left null, {@code LeaseService}
 * resolves the charge type's role against the property and leaves the line
 * unmapped if the tenant has no account for it — see {@code LeaseLine}.</p>
 *
 * <p>{@code vatApplicable} is a {@link Boolean}, not a primitive: null means
 * "take the charge type's default", which is not the same as "false".</p>
 *
 * <p>{@code addendumId} is honoured only by {@code LeasePostingService.amendLines},
 * which re-inserts every line and must keep an addendum's lines tied to it — a
 * renewal skips tied lines, so losing the tie would copy a part-term charge onto
 * the next year. The amend checks the addendum is on the lease being amended.
 * Every other path (drafts, imports, addenda, extensions) refuses a non-null
 * value: only an addendum itself may tie a new line to an addendum.</p>
 */
public record LeaseLineInput(UUID chargeTypeId,
                             String chargeTypeCode,
                             BigDecimal grossAmount,
                             BigDecimal discountAmount,
                             String narration,
                             Boolean vatApplicable,
                             UUID creditAccountId,
                             LocalDate periodStart,
                             LocalDate periodEnd,
                             UUID addendumId) {

    /** A line not tied to any addendum — every caller but an amend's re-send. */
    public LeaseLineInput(UUID chargeTypeId, String chargeTypeCode, BigDecimal grossAmount,
                          BigDecimal discountAmount, String narration, Boolean vatApplicable,
                          UUID creditAccountId, LocalDate periodStart, LocalDate periodEnd) {
        this(chargeTypeId, chargeTypeCode, grossAmount, discountAmount, narration, vatApplicable,
                creditAccountId, periodStart, periodEnd, null);
    }
}
