package com.datagami.rentaxis.api.dto.cheque;

import java.math.BigDecimal;
import java.util.List;

/**
 * How late the money is, bucketed (spec §7.5).
 *
 * <p>Built over the register's {@code due} rows and bucketed by
 * {@code ChequeDueRules.daysOverdue}, which measures from the end of the lease's
 * grace period rather than from the cheque date. That is why "current" is not the
 * same as "not yet due": a cheque that matured yesterday on a lease with five
 * days of grace is due money that is not late yet, and it belongs in the first
 * bucket rather than out of the report.</p>
 *
 * <p>A row carries its own {@code daysOverdue} as well as its bucket so the
 * screen can sort the worst offenders inside a bucket without re-deriving the
 * number and disagreeing with the totals above it.</p>
 */
public record AgingReportDTO(List<Bucket> buckets, BigDecimal totalOutstanding, long totalCount) {

    public record Bucket(String label, int fromDays, Integer toDays,
                         long count, BigDecimal amount, List<Row> rows) {
    }

    public record Row(java.util.UUID chequeId,
                      java.util.UUID leaseId,
                      String renterName,
                      String propertyName,
                      String unitIdentifier,
                      String chequeNumber,
                      java.time.LocalDate chequeDate,
                      BigDecimal amount,
                      int daysOverdue) {
    }
}
