package com.datagami.rentaxis.api.dto.voucher;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** F14-42: void a posted voucher on {@code date} (on or after its own date), saying why. */
public record VoidVoucherDTO(@NotNull LocalDate date,
                             @NotBlank(message = "Give the reason for voiding the voucher") String reason) { }
