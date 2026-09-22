package com.datagami.rentaxis.api.dto.lease;

import jakarta.validation.constraints.NotBlank;

/**
 * The first cheque number off the renter's book; the grid counts up from it.
 *
 * <p>A record rather than a bare string parameter so the zero-padding survives:
 * "000028" in a JSON string is six characters, where a number would arrive as 28
 * and be written back to the bank as a different cheque.</p>
 */
public record GenerateChequeNumbersRequest(@NotBlank String startingNumber) {
}
