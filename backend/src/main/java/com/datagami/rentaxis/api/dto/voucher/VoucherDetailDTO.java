package com.datagami.rentaxis.api.dto.voucher;

import com.datagami.rentaxis.core.service.voucher.VoucherMath;
import com.datagami.rentaxis.domain.entity.Voucher;
import com.datagami.rentaxis.domain.entity.enums.VoucherStatus;
import com.datagami.rentaxis.domain.entity.enums.VoucherType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Detail response. Deliberately flat rather than {header, lines}: the web form binds
 * the header fields directly and a nested wrapper would make every field access in
 * VoucherForm.tsx two hops deep for no gain.
 */
public record VoucherDetailDTO(UUID id, VoucherType docType, LocalDate docDate,
                               UUID vendorId, String vendorName, String invoiceNumber, String narration,
                               UUID propertyId, UUID unitId, UUID paymentAccountId, String paymentAccountName,
                               String chequeNumber, LocalDate chequeDate, VoucherStatus status,
                               UUID journalId, String voucherNumber, UUID amendedFromId,
                               BigDecimal netTotal, BigDecimal vatTotal, BigDecimal grossTotal, Instant postedAt,
                               List<VoucherLineDTO> lines, List<VoucherAttachmentDTO> attachments,
                               LocalDate supplierInvoiceDate, LocalDate dueDate,
                               com.datagami.rentaxis.domain.entity.enums.VoucherPaymentMethod paymentMethod,
                               String paymentReference, Settlement settlement) {

    /**
     * Spec §2, derived from live allocations, never stored. PISR: {@code amount} is
     * the gross, {@code open} what is left, status OPEN / PART_PAID / PAID. BPV:
     * {@code amount} is what it paid the vendor and {@code open} the unallocated
     * advance; status is null. Null on a draft.
     */
    public record Settlement(BigDecimal amount, BigDecimal allocated, BigDecimal open, String status) {
        public static Settlement ofInvoice(BigDecimal gross, BigDecimal allocated) {
            BigDecimal open = gross.subtract(allocated);
            String status = open.signum() <= 0 ? "PAID" : allocated.signum() > 0 ? "PART_PAID" : "OPEN";
            return new Settlement(gross, allocated, open, status);
        }

        public static Settlement ofPayment(BigDecimal paid, BigDecimal allocated) {
            return new Settlement(paid, allocated, paid.subtract(allocated), null);
        }
    }

    public static VoucherDetailDTO of(Voucher v, List<VoucherAttachmentDTO> attachments) {
        return of(v, attachments, null);
    }

    public static VoucherDetailDTO of(Voucher v, List<VoucherAttachmentDTO> attachments, Settlement settlement) {
        return new VoucherDetailDTO(v.getId(), v.getDocType(), v.getDocDate(),
                v.getVendor() == null ? null : v.getVendor().getId(),
                v.getVendor() == null ? null : v.getVendor().getNameEn(),
                v.getInvoiceNumber(), v.getNarration(), v.getPropertyId(), v.getUnitId(),
                v.getPaymentAccount() == null ? null : v.getPaymentAccount().getId(),
                v.getPaymentAccount() == null ? null : v.getPaymentAccount().getName(),
                v.getChequeNumber(), v.getChequeDate(), v.getStatus(), v.getJournalId(),
                v.getVoucherNumber(), v.getAmendedFromId(),
                VoucherMath.netTotal(v.getLines()), VoucherMath.vatTotal(v.getLines()),
                VoucherMath.grossTotal(v.getLines()), v.getPostedAt(),
                v.getLines().stream().map(VoucherLineDTO::of).toList(), attachments,
                v.getSupplierInvoiceDate(), v.getDueDate(), v.getPaymentMethod(), v.getPaymentReference(), settlement);
    }
}
