package com.datagami.rentaxis.api.dto.report;

import java.util.UUID;

/**
 * A receivable ledger leaf the Tenant Ledger can run over (reports spec §2): the account picker's option.
 *
 * @param nameAr       the Arabic account name, or {@code null} when it has none (the screen falls back to {@code name})
 * @param propertyId   the building the leaf belongs to, or {@code null} for a tenant-wide leaf
 * @param propertyName that building's English name, or {@code null}
 */
public record ReceivableAccountDTO(UUID id, String code, String name, String nameAr, UUID propertyId, String propertyName) {}
