package com.datagami.rentaxis.api.dto.ledger;

import java.time.LocalDate;

/** Reversal of a posted entry. {@code date} defaults to today when omitted. */
public record ReverseRequest(LocalDate date, String reason) {}
