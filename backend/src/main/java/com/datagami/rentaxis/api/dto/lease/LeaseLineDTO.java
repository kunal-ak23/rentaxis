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
                           LocalDate periodEnd) {
}
