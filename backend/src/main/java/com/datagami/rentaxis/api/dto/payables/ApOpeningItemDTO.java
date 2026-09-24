package com.datagami.rentaxis.api.dto.payables;

import com.datagami.rentaxis.domain.entity.ApOpeningItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A cut-over open supplier invoice, with what has been allocated to it so far. */
public record ApOpeningItemDTO(UUID id, UUID vendorId, String vendorName, String invoiceNumber, LocalDate invoiceDate,
                               LocalDate dueDate, BigDecimal amount, UUID propertyId, BigDecimal allocated,
                               BigDecimal open) {

    public static ApOpeningItemDTO of(ApOpeningItem o, String vendorName, BigDecimal allocated) {
        return new ApOpeningItemDTO(o.getId(), o.getVendorId(), vendorName, o.getInvoiceNumber(), o.getInvoiceDate(),
                o.getDueDate(), o.getAmount(), o.getPropertyId(), allocated, o.getAmount().subtract(allocated));
    }
}
