package com.datagami.rentaxis.api.dto.payables;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A POSTED payment voucher to a vendor and how much of it no invoice has taken
 * yet — an advance (spec §2). {@code paid} is Σ of its lines on the vendor's
 * payable leaf.
 */
public record AdvanceDTO(UUID paymentId, UUID vendorId, String vendorName, String voucherNumber, LocalDate docDate,
                         String paymentMethod, String reference, BigDecimal paid, BigDecimal allocated,
                         BigDecimal unallocated) { }
