package com.datagami.rentaxis.api.dto.settlement;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * #100-d: where a transferred-out lease's deposit went (spec §2, "A's remaining
 * settlement"). The move-out statement of the lease left behind shows deposits
 * held = 0, because the transfer's deposit {@code JV} moved the liability onto the
 * successor; this line says so. <b>Display only</b> — it is not refundable on this
 * statement and never enters {@code netRefund}.
 *
 * @param leaseId        the successor lease; null when the caller may not read it
 * @param unitIdentifier the successor's unit; null when the caller may not read it
 * @param amount         what the successor's deposit {@code JV} moved off this lease
 */
public record DepositCarriedDTO(UUID leaseId, String unitIdentifier, BigDecimal amount) {
}
