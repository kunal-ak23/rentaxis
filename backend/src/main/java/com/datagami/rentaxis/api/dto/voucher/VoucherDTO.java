package com.datagami.rentaxis.api.dto.voucher;

import com.datagami.rentaxis.core.service.voucher.VoucherMath;
import com.datagami.rentaxis.domain.entity.Voucher;
import com.datagami.rentaxis.domain.entity.enums.VoucherStatus;
import com.datagami.rentaxis.domain.entity.enums.VoucherType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** List row. Totals are derived from the lines, never stored — one source of truth. */
public record VoucherDTO(UUID id, VoucherType docType, LocalDate docDate, UUID vendorId, String vendorName,
                         String invoiceNumber, String narration, UUID propertyId, UUID unitId,
                         UUID paymentAccountId, String paymentAccountName, String chequeNumber, LocalDate chequeDate,
                         VoucherStatus status, UUID journalId, String voucherNumber, UUID amendedFromId,
                         BigDecimal netTotal, BigDecimal vatTotal, BigDecimal grossTotal, Instant postedAt,
                         LocalDate supplierInvoiceDate, LocalDate dueDate,
                         com.datagami.rentaxis.domain.entity.enums.VoucherPaymentMethod paymentMethod, String paymentReference,
                         /* F14-36: the settlement whose refund this payment pays. */
                         UUID settlementId) {

    public static VoucherDTO of(Voucher v) {
        return new VoucherDTO(v.getId(), v.getDocType(), v.getDocDate(),
                v.getVendor() == null ? null : v.getVendor().getId(),
                v.getVendor() == null ? null : v.getVendor().getNameEn(),
                v.getInvoiceNumber(), v.getNarration(), v.getPropertyId(), v.getUnitId(),
                v.getPaymentAccount() == null ? null : v.getPaymentAccount().getId(),
                v.getPaymentAccount() == null ? null : v.getPaymentAccount().getName(),
                v.getChequeNumber(), v.getChequeDate(), v.getStatus(), v.getJournalId(),
                v.getVoucherNumber(), v.getAmendedFromId(),
                VoucherMath.netTotal(v.getLines()), VoucherMath.vatTotal(v.getLines()),
                VoucherMath.grossTotal(v.getLines()), v.getPostedAt(),
                v.getSupplierInvoiceDate(), v.getDueDate(), v.getPaymentMethod(), v.getPaymentReference(),
                v.getSettlementId());
    }
}
