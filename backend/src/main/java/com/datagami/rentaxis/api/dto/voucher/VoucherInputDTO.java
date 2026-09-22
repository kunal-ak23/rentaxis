package com.datagami.rentaxis.api.dto.voucher;

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
        @NotEmpty @Valid List<VoucherLineInputDTO> lines) {}
