package com.datagami.rentaxis.api.dto;

import java.math.BigDecimal;

/** GET /bookings/quote: the fee a booking would be charged, VAT as on approval. */
public record BookingQuoteDTO(String feeType, BigDecimal unitAmount, BigDecimal net, BigDecimal vat, BigDecimal total) {
}
