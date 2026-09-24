package com.datagami.rentaxis.api.dto.voucher;

import com.datagami.rentaxis.domain.entity.VoucherAllocation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One allocation, with both sides named for the screen. {@code live}: not
 * released. The names are looked up by the controller, never trusted from input.
 */
public record AllocationDTO(UUID id, UUID vendorId, UUID paymentVoucherId, String paymentNumber,
                            UUID invoiceVoucherId, UUID openingItemId, String invoiceNumber, String invoiceVoucherNumber,
                            BigDecimal amount, LocalDate allocatedOn, LocalDate releasedOn, String releaseReason,
                            boolean live) {

    public static AllocationDTO of(VoucherAllocation a, String paymentNumber, String invoiceNumber,
                                   String invoiceVoucherNumber) {
        return new AllocationDTO(a.getId(), a.getVendorId(), a.getPaymentVoucherId(), paymentNumber,
                a.getInvoiceVoucherId(), a.getOpeningItemId(), invoiceNumber, invoiceVoucherNumber,
                a.getAmount(), a.getAllocatedOn(), a.getReleasedOn(), a.getReleaseReason(), a.isLive());
    }
}
