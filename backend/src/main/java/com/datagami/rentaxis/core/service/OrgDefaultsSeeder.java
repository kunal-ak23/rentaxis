package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The defaults an organisation needs before it can draft a contract: the chart of
 * accounts, the property account templates (so every property gets its ledger
 * accounts) and the PACT charge types. Each step is idempotent.
 *
 * <p>{@code POST /accounts/seed} seeds the caller's organisation; provisioning seeds
 * the new one (QA-02, prod hand test 2026-11-26: a new organisation could not enter
 * rent until someone found "Seed Default Accounts").</p>
 *
 * <p>The three calls live here, not in {@code AccountService}:
 * {@code PropertyAccountService} already depends on {@code AccountService}, so the
 * reverse edge would make a constructor cycle.</p>
 */
@Service
public class OrgDefaultsSeeder {

    private final AccountService accountService;
    private final PropertyAccountService propertyAccountService;
    private final ChargeTypeService chargeTypeService;
    private final EntityManager entityManager;

    public OrgDefaultsSeeder(AccountService accountService, PropertyAccountService propertyAccountService,
                             ChargeTypeService chargeTypeService, EntityManager entityManager) {
        this.accountService = accountService;
        this.propertyAccountService = propertyAccountService;
        this.chargeTypeService = chargeTypeService;
        this.entityManager = entityManager;
    }

    /** Seeds the organisation in context. Returns the chart of accounts. */
    @Transactional
    public java.util.List<com.datagami.rentaxis.domain.entity.Account> seedCurrent() {
        var accounts = accountService.seedDefaultAccounts();
        propertyAccountService.seedDefaultTemplateAndDefaults();
        chargeTypeService.seedDefaults();
        return accounts;
    }

    /**
     * Seeds {@code tenantId}, acting in that organisation for the duration (the tenant
     * filter and the tenant stamp on new rows both follow the context), then puts the
     * caller's context back.
     *
     * <p>The repository calls switch {@code tenantFilter} on for {@code tenantId} on the
     * shared session. With no caller tenant (SUPER_ADMIN provisioning) the aspect never
     * switches it off again, so the rest of the caller's transaction would read only the
     * new organisation: the "New Organization Created" mail found no super admin. The
     * filter goes back to the caller's state here.</p>
     */
    @Transactional
    public void seedFor(UUID tenantId) {
        UUID previous = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(tenantId);
        try {
            seedCurrent();
        } finally {
            Session session = entityManager.unwrap(Session.class);
            if (previous == null) {
                TenantContextHolder.clear();
                session.disableFilter("tenantFilter");
            } else {
                TenantContextHolder.setTenantId(previous);
                session.enableFilter("tenantFilter").setParameter("tenantId", previous);
            }
        }
    }
}
