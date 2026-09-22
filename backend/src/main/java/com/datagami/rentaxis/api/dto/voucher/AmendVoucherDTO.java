package com.datagami.rentaxis.api.dto.voucher;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record AmendVoucherDTO(@NotNull LocalDate reversalDate, String reason,
                              @NotNull @Valid VoucherInputDTO replacement) {}
