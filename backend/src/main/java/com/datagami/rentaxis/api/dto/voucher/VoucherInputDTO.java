package com.datagami.rentaxis.api.dto.voucher;

import com.datagami.rentaxis.domain.entity.enums.VoucherPaymentMethod;
import jakarta.validation.constraints.Size;

import com.datagami.rentaxis.domain.entity.enums.VoucherType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record VoucherInputDTO(
        @NotNull VoucherType docType,
        @NotNull LocalDate docDate,
        UUID vendorId,
        String invoiceNumber,
        String narration,
        UUID propertyId,
        UUID unitId,
        UUID paymentAccountId,
        String chequeNumber,
        LocalDate chequeDate,
        @NotEmpty @Valid List<VoucherLineInputDTO> lines,
        /** PISR: the date on the supplier's invoice; null means the posting date. */
        LocalDate supplierInvoiceDate,
        /** PISR: null means the supplier's date plus the vendor's payment terms. */
        LocalDate dueDate,
        /** BPV: TRANSFER, CHEQUE or CASH; null is inferred from the cheque number and the account. */
        VoucherPaymentMethod paymentMethod,
        /** BPV: the bank transfer reference. */
        @Size(max = 60) String paymentReference) {}
