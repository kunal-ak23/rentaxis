package com.datagami.rentaxis.api.dto.cheque;

import java.util.List;
import java.util.UUID;

/**
 * Where a receipt or clearing of one register row posts when no account is named
 * ({@code target}, null when the posting would fall back to an unmapped role), and
 * the accounts the user may pick instead: cash leaves and leaves a bank account owns,
 * tenant-wide or the row's property's (R1 P2-2/P2-3).
 */
public record SettlementTargetDTO(Option target, List<Option> options) {

    /** {@code kind} is CASH or BANK; {@code bankAccount} names the owning bank account for a bank leaf. */
    public record Option(UUID id, String code, String name, String nameAr, String kind, String bankAccount) { }
}
