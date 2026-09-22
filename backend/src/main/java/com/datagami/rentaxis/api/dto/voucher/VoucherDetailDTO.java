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
                               List<VoucherLineDTO> lines, List<VoucherAttachmentDTO> attachments) {

    public static VoucherDetailDTO of(Voucher v, List<VoucherAttachmentDTO> attachments) {
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
                v.getLines().stream().map(VoucherLineDTO::of).toList(), attachments);
    }
}
