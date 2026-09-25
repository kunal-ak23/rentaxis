package com.datagami.rentaxis.api.dto.lease;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What a renewal would draft (spec 2026-09-24 §4a/§4c): the headline rent before
 * and after, the lines it copies, the one-off lines it leaves behind, and the
 * property's informational notice threshold for an increase.
 */
public record RenewalPreviewDTO(BigDecimal baseRent,
                                BigDecimal newRent,
                                BigDecimal changePercent,
                                List<Line> copiedLines,
                                List<LeaseLineDTO> skippedOneOffLines,
                                BigDecimal warnPercent,
                                boolean exceedsWarn,
                                /* PR #358 R1 P2-3: the current lease's discount, which the renewal does not carry. */
                                BigDecimal droppedDiscount) {

    /** One copied line as the successor will carry it. */
    public record Line(UUID chargeTypeId, String chargeTypeCode, String chargeTypeName, String chargeTypeNameAr,
                       String behaviour, BigDecimal grossAmount, BigDecimal discountAmount, boolean vatApplicable) {
    }
}
