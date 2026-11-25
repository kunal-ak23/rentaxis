package com.datagami.rentaxis.api.dto.lease;

import java.math.BigDecimal;
import java.time.LocalDate;
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
                                BigDecimal droppedDiscount,
                                /* Rental index (spec 2026-11-25 §8): all null when RENTAL_INDEX is off. Status only
                                   (NOT_DUBAI, NO_EMIRATE, NOT_RESIDENTIAL) when the index does not apply; the range
                                   fields null on NO_INDEX. Cap, base and new rent compare on an annual basis. */
                                String indexStatus,
                                BigDecimal indexMin,
                                BigDecimal indexMax,
                                BigDecimal indexAverage,
                                BigDecimal indexGapPercent,
                                Integer indexAllowedPercent,
                                BigDecimal indexCapRent,
                                Boolean indexAboveCap,
                                UUID indexRateId,
                                String indexSource,
                                LocalDate indexCheckedOn,
                                /* The range in force is this unit's own DLD result (its printed percentage sets the cap). */
                                Boolean indexUnitResult,
                                /* Lease end − 90 days, and whether today is past it (warning only). */
                                LocalDate noticeDeadline,
                                Boolean noticePassed) {

    /** The shape before the rental index: every index field null. */
    public RenewalPreviewDTO(BigDecimal baseRent, BigDecimal newRent, BigDecimal changePercent, List<Line> copiedLines,
                             List<LeaseLineDTO> skippedOneOffLines, BigDecimal warnPercent, boolean exceedsWarn,
                             BigDecimal droppedDiscount) {
        this(baseRent, newRent, changePercent, copiedLines, skippedOneOffLines, warnPercent, exceedsWarn,
                droppedDiscount, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** One copied line as the successor will carry it. */
    public record Line(UUID chargeTypeId, String chargeTypeCode, String chargeTypeName, String chargeTypeNameAr,
                       String behaviour, BigDecimal grossAmount, BigDecimal discountAmount, boolean vatApplicable) {
    }
}
