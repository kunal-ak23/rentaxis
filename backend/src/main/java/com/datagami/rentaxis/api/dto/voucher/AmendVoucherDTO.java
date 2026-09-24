package com.datagami.rentaxis.api.dto.voucher;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** {@code allocations}: the invoices a replacement payment voucher settles (spec §2); ignored for a PISR. */
public record AmendVoucherDTO(@NotNull LocalDate reversalDate, String reason,
                              @NotNull @Valid VoucherInputDTO replacement,
                              @Valid java.util.List<AllocationInputDTO> allocations) {}
