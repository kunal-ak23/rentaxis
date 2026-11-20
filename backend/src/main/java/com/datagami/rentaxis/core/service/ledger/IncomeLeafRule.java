package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.enums.AccountType;

import java.util.Map;
import java.util.UUID;

/**
 * The one rule for an account a user may pick to be credited with income (a contract
 * journal voucher, a booking fee): an active income leaf, either shared or belonging
 * to the given property.
 */
public final class IncomeLeafRule {

    public static final String CODE = "booking.incomeAccount";

    private IncomeLeafRule() {
    }

    public static void require(Account account, UUID propertyId) {
        String label = account.getCode() + " " + account.getName();
        Map<String, Object> args = Map.of("account", label);
        if (account.isGroup() || !account.isActive()) {
            throw new BusinessRuleViolationException(label + " is not an active ledger account", CODE, args);
        }
        if (account.getAccountType() != AccountType.INCOME) {
            throw new BusinessRuleViolationException(label + " is not an income account", CODE, args);
        }
        UUID own = account.getPropertyId();
        if (own != null && !own.equals(propertyId)) {
            throw new BusinessRuleViolationException(label + " belongs to another property", CODE, args);
        }
    }
}
