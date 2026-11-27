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
        // QA-13 (26 Nov prod pass): written for the person at the button, not a log —
        // no role codes, no property UUID, and the screen that fixes it named as the
        // screen calls itself. The roles stay available through getMissingRoles().
        super("No ledger account is set up for " + readable(missingRoles)
                + (propertyId != null
                    ? " on this property. Open the property's Ledger accounts tab and press"
                        + " Generate missing accounts, or set them under Settings › Default accounts."
                    : ". Set them under Settings › Default accounts."));
        this.missingRoles = missingRoles;
        this.propertyId = propertyId;
    }

    /** "advance rent, bank and PDC receivable": each role in words, sorted, joined as a list. */
    static String readable(Set<AccountRole> roles) {
        java.util.List<String> words = roles.stream().map(UnmappedAccountRoleException::words).sorted().toList();
        if (words.isEmpty()) {
            return "some accounts";
        }
        if (words.size() == 1) {
            return words.get(0);
        }
        return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.get(words.size() - 1);
    }

    private static String words(AccountRole role) {
        return java.util.Arrays.stream(role.name().split("_"))
                .map(w -> switch (w) {
                    case "PDC", "VAT" -> w;
                    default -> w.toLowerCase(java.util.Locale.ROOT);
                })
                .collect(Collectors.joining(" "));
    }

    public Set<AccountRole> getMissingRoles() {
        return missingRoles;
    }

    public UUID getPropertyId() {
        return propertyId;
    }
}
