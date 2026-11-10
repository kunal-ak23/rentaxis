package com.datagami.rentaxis.api.dto.penalty;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A one-off journal voucher on a contract: the renter is charged {@code amount}
 * (net) for something they asked for — extra parking, a replacement key — and
 * the credit goes to the income account picked here.
 *
 * @param entryDate the JV's date; null = today. The manual-date window applies.
 * @param accountId the active income leaf to credit.
 * @param vatable 5 % VAT on top; null = none. Refused on a lease without VAT.
 * @param narration what the charge is for; printed on the JV, the collection row and the receipt.
 */
public record LeaseJournalVoucherRequest(LocalDate entryDate,
                                         @NotNull UUID accountId,
                                         @NotNull @DecimalMin(value = "0.00", inclusive = false)
                                         @com.datagami.rentaxis.api.validation.Money(positive = true)
                                         BigDecimal amount,
                                         Boolean vatable,
                                         @NotBlank String narration) {
}
