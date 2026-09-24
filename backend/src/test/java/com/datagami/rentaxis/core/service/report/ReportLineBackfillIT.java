package com.datagami.rentaxis.core.service.report;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.ChangesetSql;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Changeset 109's backfill, run again on rows this test controls inside a
 * transaction it rolls back (Liquibase only ever ran it on an empty database).
 */
@SpringBootTest
class ReportLineBackfillIT extends AbstractPostgresIT {

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired PropertyService properties;
    @Autowired AccountResolver resolver;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired jakarta.persistence.EntityManager em;

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    private String line(UUID accountId) {
        return jdbc.queryForObject("SELECT report_line FROM accounts WHERE id = ?", String.class, accountId);
    }

    @Test
    void roleLeavesTakeTheirRoleAndExpenseLeavesTheirCategory() {
        tx.executeWithoutResult(status -> {
            LandlordOrg org = new LandlordOrg();
            org.setName("Backfill-" + UUID.randomUUID());
            UUID tenantId = orgRepo.save(org).getId();
            TenantContextHolder.setTenantId(tenantId);
            accounts.seedDefaultAccounts();
            propertyAccounts.seedDefaultTemplateAndDefaults();
            Property p = new Property();
            p.setNameEn("Marina Tower");
            p.setEmirate(Emirate.DUBAI);
            UUID pid = properties.createProperty(p).getId();

            Account d01 = accounts.getAccountByCode("D-01");
            Account rental = resolver.resolve(AccountRole.RENTAL_INCOME, pid);
            Account chequePenalty = resolver.resolve(AccountRole.CHEQUE_RETURN_PENALTY, pid);
            Account rentPenalty = resolver.resolve(AccountRole.RENT_PENALTY, pid);
            // A leaf that survived a property rename, and a hand-made one sharing a category's first word.
            Account renamed = accounts.createLeaf("Cleaning - Old Marina Name", d01, pid);
            Account handMade = accounts.createLeaf("Security Deposit Refunds", d01, pid);
            Account shared = accounts.createLeaf("Cleaning - Head Office", d01, null);
            em.flush();   // the services' inserts, before plain JDBC reads and writes the same rows
            // A leaf mapped to two roles keeps the first by ordinal: RENT_PENALTY comes before CHEQUE_RETURN_PENALTY.
            jdbc.update("UPDATE property_account_mappings SET account_id = ? WHERE property_id = ? AND role = 'RENT_PENALTY'",
                    chequePenalty.getId(), pid);
            jdbc.update("UPDATE accounts SET report_line = NULL WHERE tenant_id = ?", tenantId);
            // A value somebody already chose is never overwritten.
            jdbc.update("UPDATE accounts SET report_line = 'EXP_INSURANCE' WHERE id = ?", rental.getId());

            ChangesetSql.of("109-pl-report-lines.yaml").forEach(jdbc::execute);

            assertThat(line(rental.getId())).isEqualTo("EXP_INSURANCE");
            assertThat(line(chequePenalty.getId())).isEqualTo("RENT_PENALTY");
            assertThat(line(rentPenalty.getId())).isNull();   // no longer mapped to anything
            assertThat(line(resolver.resolve(AccountRole.ADMIN_FEE, pid).getId())).isEqualTo("ADMIN_FEE");
            assertThat(line(renamed.getId())).isEqualTo("EXP_CLEANING");
            assertThat(line(handMade.getId())).isNull();
            assertThat(line(shared.getId())).isNull();          // no property: not a generated leaf
            Integer categories = jdbc.queryForObject("""
                    SELECT count(*) FROM accounts WHERE tenant_id = ? AND property_id = ? AND report_line LIKE 'EXP\\_%'
                    """, Integer.class, tenantId, pid);
            // Six generated categories, the renamed cleaning leaf, and the rental leaf set to EXP_INSURANCE by hand above.
            assertThat(categories).isEqualTo(8);
            status.setRollbackOnly();
        });
    }

    @Test
    void generationSetsTheLineOnNewLeaves() {
        tx.executeWithoutResult(status -> {
            LandlordOrg org = new LandlordOrg();
            org.setName("Backfill-gen-" + UUID.randomUUID());
            TenantContextHolder.setTenantId(orgRepo.save(org).getId());
            accounts.seedDefaultAccounts();
            propertyAccounts.seedDefaultTemplateAndDefaults();
            Property p = new Property();
            p.setNameEn("Palm");
            p.setEmirate(Emirate.DUBAI);
            UUID pid = properties.createProperty(p).getId();
            em.flush();
            assertThat(line(resolver.resolve(AccountRole.RENTAL_INCOME, pid).getId())).isEqualTo("RENTAL_INCOME");
            assertThat(line(resolver.resolve(AccountRole.BANK, pid).getId())).isEqualTo("BANK");
            Integer categories = jdbc.queryForObject(
                    "SELECT count(*) FROM accounts WHERE property_id = ? AND report_line LIKE 'EXP\\_%'", Integer.class, pid);
            assertThat(categories).isEqualTo(6);
            status.setRollbackOnly();
        });
    }
}
