package com.datagami.rentaxis.api.dto.payables;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A supplier invoice with what is still owed on it (finance-ops spec §2): a
 * POSTED PISR ({@code kind = PISR}, {@code id} the voucher) or an AP opening
 * item ({@code kind = OPENING}). {@code open = gross − allocated}; status is
 * OPEN, PART_PAID or PAID. {@code daysOverdue} and {@code bucket} are measured
 * against the as-of date. Under a property filter, {@code gross},
 * {@code allocated} and {@code open} are that property's gross share.
 */
public record OpenItemDTO(String kind, UUID id, UUID vendorId, String vendorName, String docNumber,
                          String invoiceNumber, LocalDate docDate, LocalDate invoiceDate, LocalDate dueDate,
                          long daysOverdue, String bucket, BigDecimal gross, BigDecimal allocated, BigDecimal open,
                          String status, UUID propertyId) { }
