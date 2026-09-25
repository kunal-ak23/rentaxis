package com.datagami.rentaxis.api.dto.lease;

import java.math.BigDecimal;
import java.util.UUID;

/** F14-32: one line a credit addendum cut. */
public record LeaseAddendumCreditDTO(UUID leaseLineId, String chargeTypeCode, String chargeTypeName,
                                     String chargeTypeNameAr, BigDecimal newLineAmount,
                                     BigDecimal remainingBefore, BigDecimal remainingAfter,
                                     BigDecimal creditAmount, BigDecimal vatAmount) {
}
