package com.datagami.rentaxis.api.dto.payables;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What the user approved on the preview (PR #352 review P2-1): per vendor, the
 * net payment, the cheque number and what each item pays in cash. The post
 * re-plans under its locks and refuses with 409 when any of it differs, so the
 * money that leaves the bank is the money the preview showed.
 */
public record PostRunRequestDTO(@NotNull @Valid List<Vendor> vendors) {

    public record Vendor(@NotNull UUID vendorId, @NotNull BigDecimal netPayment, BigDecimal advanceApplied,
                         String chequeNumber, @NotNull List<Item> items) { }

    public record Item(@NotNull UUID itemId, @NotNull BigDecimal paid) { }

    /** Exactly what a preview showed. */
    public static PostRunRequestDTO of(PaymentRunPreviewDTO preview) {
        return new PostRunRequestDTO(preview.vendors().stream().map(v -> new Vendor(v.vendorId(), v.netPayment(),
                v.advanceApplied(), v.chequeNumber(),
                v.items().stream().map(i -> new Item(i.itemId(), i.paid())).toList())).toList());
    }
}
