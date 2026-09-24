package com.datagami.rentaxis.api.dto.payables;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A cheque we issued to a supplier (finance-ops spec §2). {@code duePresent}:
 * ISSUED and its date has come — the "past date, not presented" list.
 */
public record IssuedChequeDTO(UUID id, UUID voucherId, String voucherNumber, UUID vendorId, String vendorName,
                              UUID bankAccountId, String bankAccountCode, String bankAccountName,
                              String chequeNumber, LocalDate chequeDate, BigDecimal amount, String status,
                              LocalDate presentedOn, String bpcNumber, LocalDate cancelledOn, String cancelReason,
                              boolean opening, boolean duePresent) { }
