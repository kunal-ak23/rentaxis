package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.TenantDefaultAccountMapping;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AccountSubType;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.TenantDefaultAccountMappingRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * F15-11: the tenant-level inter-property clearing leaf (A-02-06-001) when a
 * clearing leg finds no mapping — a chart seeded without the A-02-06 group (an
 * imported chart, a tenant changeset 138 found without A-02). Created under A-02
 * when there is one, else as a root group of its own, and mapped as the tenant
 * default, so a journal that spans properties is never refused over the account
 * that balances it.
 */
@Component
public class InterPropertyClearingAccounts {

    static final String GROUP = "A-02-06";
    static final String LEAF = "A-02-06-001";

    private final AccountRepository accounts;
    private final TenantDefaultAccountMappingRepository defaults;

    public InterPropertyClearingAccounts(AccountRepository accounts, TenantDefaultAccountMappingRepository defaults) {
        this.accounts = accounts;
        this.defaults = defaults;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Account ensureDefault() {
        var tenantId = TenantContextHolder.getTenantId();
        Account leaf = accounts.findByCodeAndTenantId(LEAF, tenantId).orElse(null);
        if (leaf == null) {
            Account group = accounts.findByCodeAndTenantId(GROUP, tenantId).orElseGet(() -> {
                Account g = account(GROUP, "Inter-property clearing", "مقاصة بين العقارات", true);
                g.setParent(accounts.findByCodeAndTenantId("A-02", tenantId).orElse(null));
                return accounts.save(g);
            });
            leaf = account(LEAF, "Inter-property clearing – head office", "مقاصة بين العقارات – المكتب الرئيسي", false);
            leaf.setParent(group);
            leaf = accounts.save(leaf);
        }
        if (defaults.findByRole(AccountRole.INTERPROPERTY_CLEARING).isEmpty()) {
            TenantDefaultAccountMapping m = new TenantDefaultAccountMapping();
            m.setRole(AccountRole.INTERPROPERTY_CLEARING);
            m.setAccount(leaf);
            defaults.save(m);
        }
        return leaf;
    }

    private static Account account(String code, String name, String nameAr, boolean group) {
        Account a = new Account();
        a.setCode(code);
        a.setName(name);
        a.setNameEn(name);
        a.setNameAr(nameAr);
        a.setAccountType(AccountType.ASSET);
        a.setAccountSubType(AccountSubType.OTHER_ASSET);
        a.setSystem(true);
        a.setGroup(group);
        return a;
    }
}
