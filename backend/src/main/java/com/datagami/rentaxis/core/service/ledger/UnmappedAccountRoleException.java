package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * No usable leaf is mapped for one or more roles. Extends BusinessRuleViolationException
 * so the API answers 400 with a message naming the roles the accountant has to map.
 */
public class UnmappedAccountRoleException extends BusinessRuleViolationException {

    private final Set<AccountRole> missingRoles;
    private final UUID propertyId;

    public UnmappedAccountRoleException(Set<AccountRole> missingRoles, UUID propertyId) {
        super("No account mapped for role(s) " + missingRoles.stream().map(Enum::name).sorted().collect(Collectors.joining(", "))
                + (propertyId != null ? " on property " + propertyId : " (tenant default)")
                + ". Map them under Property > Accounts or Settings > Default accounts.");
        this.missingRoles = missingRoles;
        this.propertyId = propertyId;
    }

    public Set<AccountRole> getMissingRoles() {
        return missingRoles;
    }

    public UUID getPropertyId() {
        return propertyId;
    }
}
