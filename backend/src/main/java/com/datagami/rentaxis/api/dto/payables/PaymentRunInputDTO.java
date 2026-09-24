package com.datagami.rentaxis.api.dto.payables;

import com.datagami.rentaxis.domain.entity.enums.VoucherPaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A payment run as the wizard saves it (spec §2): header and the selected items.
 * {@code chequeDate} and {@code firstChequeNumber} are for method CHEQUE only;
 * the cheque date defaults to the payment date. {@code applyAdvance} is per
 * vendor in effect: it is stored on each item, and any item of a vendor asking
 * for it applies that vendor's advance.
 */
public record PaymentRunInputDTO(@NotNull LocalDate paymentDate, @NotNull UUID paymentAccountId,
                                 @NotNull VoucherPaymentMethod method, LocalDate chequeDate, String firstChequeNumber,
                                 String narration, @NotNull @Valid List<Item> items) {
    public record Item(UUID invoiceId, UUID openingItemId, @NotNull BigDecimal amount, Boolean applyAdvance) { }
}
