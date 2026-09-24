package com.datagami.rentaxis.api.dto.payables;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Payables aging as of a date (finance-ops spec §2 "Aging definition").
 *
 * <p>Per vendor: open items bucketed by days past due (current, 1–30, 31–60,
 * 61–90, 90+), unallocated advances, and the tie-out — {@code openTotal −
 * advances} against the credit balance of the vendor's payable leaf, the
 * difference in {@code delta}. {@code vendorLevel} is false under a property
 * filter, and advances, ledger balance and delta are then null: they are
 * vendor-level figures.</p>
 */
public record PayablesAgingDTO(LocalDate asOf, UUID propertyId, UUID vendorId, boolean vendorLevel,
                               List<VendorRow> rows, Figures totals, List<AdvanceDTO> advances) {

    public record Figures(BigDecimal current, BigDecimal d1to30, BigDecimal d31to60, BigDecimal d61to90,
                          BigDecimal d90plus, BigDecimal advances, BigDecimal openTotal,
                          BigDecimal ledgerBalance, BigDecimal delta) { }

    public record VendorRow(UUID vendorId, String vendorName, String vendorNameAr, boolean active,
                            UUID payableAccountId, Figures figures, List<OpenItemDTO> items) { }
}
