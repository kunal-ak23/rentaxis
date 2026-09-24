package com.datagami.rentaxis.api.dto.voucher;

import jakarta.validation.constraints.NotBlank;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** {@code allocations}: the invoices a replacement payment voucher settles (spec §2); ignored for a PISR. */
public record AmendVoucherDTO(@NotNull LocalDate reversalDate,
                              @NotBlank(message = "Give the reason for the amendment") String reason,
                              @NotNull @Valid VoucherInputDTO replacement,
                              @Valid java.util.List<AllocationInputDTO> allocations,
                              Boolean notOnStatement, Boolean allowNegativeCash) {

    public AmendVoucherDTO(LocalDate reversalDate, String reason, VoucherInputDTO replacement,
                           java.util.List<AllocationInputDTO> allocations) {
        this(reversalDate, reason, replacement, allocations, null, null);
    }
}
