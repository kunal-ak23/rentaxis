package com.datagami.rentaxis.api.dto.lease;

import java.util.UUID;

/**
 * The account a line of {@code chargeTypeId} is credited to on a property when the
 * user picks none — what {@code LeaseService.resolveCreditAccount} writes on save.
 * The New Contract grid shows it in the Ledger column before the draft is saved.
 */
public record LineAccountSuggestionDTO(UUID chargeTypeId, UUID accountId) {
}
