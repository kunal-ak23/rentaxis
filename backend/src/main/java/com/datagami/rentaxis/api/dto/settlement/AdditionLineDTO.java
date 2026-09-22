package com.datagami.rentaxis.api.dto.settlement;

import com.datagami.rentaxis.domain.entity.enums.AdditionCategory;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Something the landlord owes the renter on top of the deposit — interest on it,
 * agreed compensation. The {@code STL} <em>debits</em> {@link #accountId}, which
 * is why the default is an income leaf: the landlord is giving income back.
 */
public record AdditionLineDTO(
        UUID id,
        AdditionCategory category,
        String description,
        BigDecimal amount,
        UUID accountId,
        String accountName) {
}
