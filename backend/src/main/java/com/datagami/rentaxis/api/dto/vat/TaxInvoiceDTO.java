package com.datagami.rentaxis.api.dto.vat;

import com.datagami.rentaxis.domain.entity.enums.TaxInvoiceKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A tax invoice or tax credit note, as the lease page and the renter's documents list it. */
public record TaxInvoiceDTO(UUID id,
                            String invoiceNumber,
                            TaxInvoiceKind kind,
                            LocalDate issueDate,
                            LocalDate periodStart,
                            LocalDate periodEnd,
                            UUID leaseId,
                            UUID chequeId,
                            String propertyName,
                            String unitNumber,
                            String customerName,
                            BigDecimal taxableAmount,
                            BigDecimal vatRate,
                            BigDecimal vatAmount,
                            BigDecimal totalAmount) {
}
