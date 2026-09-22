package com.datagami.rentaxis.api.dto.ledger;

import com.datagami.rentaxis.domain.entity.enums.AccountRole;

import java.util.UUID;

/** inherited = resolved from the tenant default rather than a property mapping. */
public record RoleMappingDTO(AccountRole role, UUID accountId, String accountCode, String accountName, boolean inherited) {}
