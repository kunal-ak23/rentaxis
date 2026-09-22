package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.PropertyAccountMapping;
import com.datagami.rentaxis.domain.entity.TenantDefaultAccountMapping;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.repository.PropertyAccountMappingRepository;
import com.datagami.rentaxis.domain.repository.TenantDefaultAccountMappingRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** (role, property) -> leaf account. Property mapping, then tenant default, then a hard error. Spec §4.4. */
@Component
public class AccountResolver {

    private final PropertyAccountMappingRepository propertyRepo;
    private final TenantDefaultAccountMappingRepository defaultRepo;

    public AccountResolver(PropertyAccountMappingRepository propertyRepo, TenantDefaultAccountMappingRepository defaultRepo) {
        this.propertyRepo = propertyRepo;
        this.defaultRepo = defaultRepo;
    }

    @Transactional(readOnly = true)
    public Account resolve(AccountRole role, UUID propertyId) {
        Account a = tryResolve(role, propertyId);
        if (a == null) throw new UnmappedAccountRoleException(EnumSet.of(role), propertyId);
        return a;
    }

    /**
     * Same lookup, but an unmapped role is null rather than an exception — for
     * callers to whom a missing mapping is an ordinary outcome, such as a lease
     * line that is allowed to stay unmapped until posting.
     *
     * <p>Those callers must not use {@link #resolve} inside a try/catch. This
     * class is proxied, so an exception thrown out of {@code resolve} propagates
     * through the transaction interceptor and marks the caller's transaction
     * rollback-only before the catch block ever runs; the caller then completes
     * happily and the commit fails with "Transaction silently rolled back". A
     * bulk import lost every lease in the workbook to exactly that.</p>
     */
    @Transactional(readOnly = true)
    public Account resolveOrNull(AccountRole role, UUID propertyId) {
        return tryResolve(role, propertyId);
    }

    /** Resolves every role or throws once listing all that are missing — used by the lease posting guard. */
    @Transactional(readOnly = true)
    public Map<AccountRole, Account> resolveAll(Set<AccountRole> roles, UUID propertyId) {
        Map<AccountRole, Account> out = new EnumMap<>(AccountRole.class);
        if (roles.isEmpty()) return out;
        if (propertyId != null) {
            for (PropertyAccountMapping m : propertyRepo.findByPropertyIdAndRoleIn(propertyId, roles)) {
                if (usable(m.getAccount())) out.put(m.getRole(), m.getAccount());
            }
        }
        Set<AccountRole> remaining = EnumSet.copyOf(roles);
        remaining.removeAll(out.keySet());
        if (!remaining.isEmpty()) {
            for (TenantDefaultAccountMapping m : defaultRepo.findByRoleIn(remaining)) {
                if (usable(m.getAccount())) out.put(m.getRole(), m.getAccount());
            }
        }
        remaining.removeAll(out.keySet());
        if (!remaining.isEmpty()) throw new UnmappedAccountRoleException(remaining, propertyId);
        return out;
    }

    private Account tryResolve(AccountRole role, UUID propertyId) {
        if (propertyId != null) {
            Account a = propertyRepo.findByPropertyIdAndRole(propertyId, role).map(PropertyAccountMapping::getAccount).orElse(null);
            if (usable(a)) return a;
        }
        Account d = defaultRepo.findByRole(role).map(TenantDefaultAccountMapping::getAccount).orElse(null);
        return usable(d) ? d : null;
    }

    /** A role must land on an active leaf: a group account has no balance of its own to post to. */
    private static boolean usable(Account a) {
        return a != null && a.isActive() && !a.isGroup();
    }
}
