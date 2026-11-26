package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-02 (prod hand test 2026-11-26): a newly provisioned organisation could not
 * draft a contract. It had no chart of accounts, no property account templates and
 * no charge types until someone found Chart of Accounts › "Seed Default Accounts".
 * Its properties then had no ledger accounts either (QA-13). Provisioning now seeds
 * all three, exactly as {@code POST /accounts/seed} does, for the new organisation only.
 */
@SpringBootTest
class OrganisationSeededAtProvisioningIT extends AbstractPostgresIT {

    @Autowired LandlordOrgService orgs;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountRepository accounts;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager em;
    @Autowired OrgDefaultsSeeder seeder;

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
    }

    private int count(String table, UUID tenant) {
        return jdbc.queryForObject("select count(*) from " + table + " where tenant_id = ?", Integer.class, tenant);
    }

    @Test
    void aNewOrganisationHasItsAccountsTemplatesAndChargeTypes() {
        LandlordOrg org = orgs.provisionTenant("QA02 Seeded " + UUID.randomUUID().toString().substring(0, 8));

        assertThat(count("accounts", org.getId())).isGreaterThan(0);
        assertThat(count("property_account_template_rows", org.getId())).isGreaterThan(0);
        assertThat(count("tenant_default_account_mappings", org.getId())).isGreaterThan(0);
        assertThat(jdbc.queryForObject(
                "select count(*) from charge_types where tenant_id = ? and behaviour = 'RENT' and active",
                Integer.class, org.getId())).isGreaterThan(0);
    }

    @Test
    void seedsOnlyTheNewOrganisationAndLeavesTheCallersContextAsItWas() {
        LandlordOrg caller = orgs.provisionTenant("QA02 Caller " + UUID.randomUUID().toString().substring(0, 8));
        int callerAccounts = count("accounts", caller.getId());
        assertThat(callerAccounts).isGreaterThan(0);
        TenantContextHolder.setTenantId(caller.getId());

        LandlordOrg fresh = orgs.provisionTenant("QA02 Fresh " + UUID.randomUUID().toString().substring(0, 8));

        assertThat(TenantContextHolder.getTenantId()).isEqualTo(caller.getId());
        assertThat(count("accounts", caller.getId())).isEqualTo(callerAccounts);
        assertThat(count("accounts", fresh.getId())).isEqualTo(callerAccounts);
    }

    @Test
    void seedForPutsTheCallersTenantFilterBackForTheRestOfItsTransaction() {
        LandlordOrg caller = orgs.provisionTenant("QA02 Filter " + UUID.randomUUID().toString().substring(0, 8));
        LandlordOrg other = orgs.provisionTenant("QA02 Other " + UUID.randomUUID().toString().substring(0, 8));
        int callerAccounts = count("accounts", caller.getId());
        assertThat(callerAccounts).isGreaterThan(0);
        TenantContextHolder.setTenantId(caller.getId());

        // seedFor directly: provisionTenant's own later repository calls would re-apply
        // the caller's filter and hide a missing reset. Seeding again is idempotent.
        List<Account> seenAfter = tx.execute(status -> {
            accounts.findAll(); // the aspect switches the caller's filter on in this session
            seeder.seedFor(other.getId());
            // Not through a repository: the aspect would re-apply the caller's filter.
            return em.createQuery("select a from Account a", Account.class).getResultList();
        });

        // Without the filter reset, this session would still filter on the new org's id.
        assertThat(seenAfter).hasSize(callerAccounts)
                .allSatisfy(a -> assertThat(a.getTenantId()).isEqualTo(caller.getId()));
    }
}
