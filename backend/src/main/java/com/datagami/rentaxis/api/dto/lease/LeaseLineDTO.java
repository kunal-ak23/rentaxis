package com.datagami.rentaxis.api.dto.lease;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A persisted lease line on the wire.
 *
 * <p>The charge type and credit account are flattened to code/name as well as id
 * so the lease screen and the contract PDF can render a line without a second
 * round trip per row. {@code creditAccountId} is null when the property has no
 * mapping for the charge type's role yet — the UI shows that as "unmapped", and
 * posting refuses until it is resolved.</p>
 *
 * <p>{@code addendumId} names the addendum that charged the line (null for the
 * contract's own lines and an extension's); an amend re-sends it so the tie
 * survives the re-insert.</p>
 */
public record LeaseLineDTO(UUID id,
                           int seqNo,
                           UUID chargeTypeId,
                           String chargeTypeCode,
                           String chargeTypeName,
                           String behaviour,
                           UUID creditAccountId,
                           String creditAccountCode,
                           String creditAccountName,
                           BigDecimal grossAmount,
                           BigDecimal discountAmount,
                           BigDecimal netAmount,
                           String narration,
                           boolean vatApplicable,
                           LocalDate periodStart,
                           LocalDate periodEnd,
                           UUID addendumId,
                           /* F14-15: the Arabic names, for the /ar lease view; null when the chart has none. */
                           String chargeTypeNameAr,
                           String creditAccountNameAr,
                           /* F14-18 / §4c: the charge type's recognition (RENT_LIKE | ONE_OFF | PASS_THROUGH). */
                           String recognition,
                           /* Spec §4b: the rent-free concession on this line (0 unless it is the contract's rent). */
                           BigDecimal rentFreeAmount) {
}
