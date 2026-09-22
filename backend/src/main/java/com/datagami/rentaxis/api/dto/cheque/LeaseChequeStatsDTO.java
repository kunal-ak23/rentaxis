package com.datagami.rentaxis.api.dto.cheque;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One lease's collection position, for a list screen that shows many leases at
 * once (spec §7.4).
 *
 * <p>Asked for in a batch rather than per lease because the leases table renders
 * twenty rows and twenty round trips is the shape this DTO exists to avoid.</p>
 *
 * <p>{@code uncleared} counts the instruments still outstanding — REGISTERED,
 * DEPOSITED or mid-gateway — and deliberately excludes the ones that are no
 * longer anybody's claim: cancelled, returned and superseded rows. {@code total}
 * counts every row the register holds for the lease, so {@code cleared +
 * uncleared} does not have to equal it.</p>
 */
public record LeaseChequeStatsDTO(UUID leaseId,
                                  long total,
                                  long cleared,
                                  long uncleared,
                                  long bounced,
                                  BigDecimal totalAmount,
                                  BigDecimal clearedAmount,
                                  BigDecimal dueAmount) {
}
