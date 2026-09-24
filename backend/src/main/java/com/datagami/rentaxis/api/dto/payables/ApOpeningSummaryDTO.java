package com.datagami.rentaxis.api.dto.payables;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The cut-over open items and, per vendor, their total beside the OB line on the
 * vendor's payable leaf (spec §2). A non-zero difference means the open items do
 * not yet explain the opening balance.
 */
public record ApOpeningSummaryDTO(List<ApOpeningItemDTO> items, List<VendorCheck> vendors) {
    public record VendorCheck(UUID vendorId, String vendorName, BigDecimal itemsTotal, BigDecimal openingBalance,
                              BigDecimal difference) { }
}
