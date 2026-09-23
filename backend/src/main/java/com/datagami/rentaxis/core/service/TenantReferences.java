package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.IdRef;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves ids that arrive in a request body to managed rows of the caller's
 * tenant: the same pattern as {@code BankAccountService.resolveProperty}.
 *
 * <p>Call it inside the service's transaction. The Hibernate tenant filter is
 * only enabled there, and the explicit tenant comparison is the belt and braces
 * that still holds when it is not. A foreign id and a missing id get the same
 * 404, so the answer does not confirm that another tenant's row exists.</p>
 */
@Component
public class TenantReferences {

    private final PropertyRepository propertyRepository;
    private final BuildingRepository buildingRepository;
    private final AccountRepository accountRepository;

    public TenantReferences(PropertyRepository propertyRepository, BuildingRepository buildingRepository,
                            AccountRepository accountRepository) {
        this.propertyRepository = propertyRepository;
        this.buildingRepository = buildingRepository;
        this.accountRepository = accountRepository;
    }

    /** The property named by {@code ref}, or null when the body names none. */
    public Property propertyOrNull(IdRef ref) {
        if (ref == null) return null;
        return property(requireId(ref, "property"));
    }

    public Property property(UUID id) {
        if (id == null) throw new BusinessRuleViolationException("property.id is required");
        return propertyRepository.findById(id)
                .filter(p -> inCurrentTenant(p.getTenantId()))
                .orElseThrow(() -> new NotFoundException("Property not found"));
    }

    /**
     * The building named by {@code id}, which must stand on {@code property}. A
     * building of this tenant on a different property is a 400, not a 404: the
     * caller can see both, the pairing is simply wrong.
     */
    public Building building(UUID id, Property property) {
        if (id == null) throw new BusinessRuleViolationException("building.id is required");
        Building b = buildingRepository.findById(id)
                .filter(x -> inCurrentTenant(x.getTenantId()))
                .orElseThrow(() -> new NotFoundException("Building not found"));
        UUID onProperty = b.getProperty() == null ? null : b.getProperty().getId();
        if (property == null || !property.getId().equals(onProperty)) {
            throw new BusinessRuleViolationException("That building is not part of the selected property");
        }
        return b;
    }

    /**
     * A staff member's salary account: one of this tenant's active, non-group
     * EXPENSE leaves, or null when the body names none.
     *
     * <p>Re-sending the account the row already has is not a new choice and is
     * kept as it is (after the tenant check), so a row linked before this rule
     * existed can still have its name or phone edited without being re-linked.</p>
     */
    public Account salaryAccountOrNull(IdRef ref, Account current) {
        if (ref == null) return null;
        UUID id = requireId(ref, "salaryAccount");
        if (current != null && id.equals(current.getId()) && inCurrentTenant(current.getTenantId())) {
            return current;
        }
        Account a = accountRepository.findByIdScopedToTenant(id)
                .filter(acc -> inCurrentTenant(acc.getTenantId()))
                .orElseThrow(() -> new NotFoundException("Ledger account not found"));
        if (a.isGroup() || a.getAccountType() != AccountType.EXPENSE) {
            throw new BusinessRuleViolationException(
                    "A salary account must be an expense ledger account, not " + a.getCode() + " " + a.getName());
        }
        if (!a.isActive()) {
            throw new BusinessRuleViolationException("Ledger account " + a.getCode() + " is inactive");
        }
        return a;
    }

    static UUID requireId(IdRef ref, String field) {
        if (ref.id() == null) throw new BusinessRuleViolationException(field + ".id is required");
        return ref.id();
    }

    /** Belt and braces over the Hibernate tenant filter: an id from a request body never crosses tenants. */
    public static boolean inCurrentTenant(UUID rowTenantId) {
        UUID current = TenantContextHolder.getTenantId();
        return current != null && current.equals(rowTenantId);
    }
}
