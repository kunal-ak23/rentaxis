package com.datagami.rentaxis.api.dto.ledger;

import com.datagami.rentaxis.domain.entity.enums.AccountRole;

import java.util.UUID;

public record TemplateRowDTO(AccountRole role, String namePattern, UUID parentAccountId, String parentCode, boolean enabled) {
    public TemplateRowDTO withEnabled(boolean e) { return new TemplateRowDTO(role, namePattern, parentAccountId, parentCode, e); }
}
