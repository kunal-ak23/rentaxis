package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AccountSubType;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/v1/finance/accounts/seed} on a tenant whose chart is NOT the
 * PACT seed.
 *
 * <p>{@code AccountService.seedDefaultAccounts()} returns early once any account
 * exists, so an imported or hand-built chart reaches
 * {@code PropertyAccountService.seedDefaultTemplateAndDefaults()} with none of the
 * PACT codes present. That used to resolve each template parent and each tenant
 * default with {@code getAccountByCode}, whose NotFoundException came back out of
 * the endpoint as a 404 — the tenant could never seed a template at all. The seed
 * is now per-row: a missing code skips its own row and logs, everything resolvable
 * is still created.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountSeedNonPactChartIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountRepository accountRepo;
    @Autowired JdbcTemplate jdbc;

    /**
     * Assertions read through JdbcTemplate with an explicit {@code tenant_id}
     * predicate rather than through the repositories: a repository call from the
     * test thread runs outside any transaction, where the Hibernate tenant filter
     * TenantAspect enables is not reliably the one the query is issued on.
     */
    private List<String> codes(UUID tenantId) {
        return jdbc.queryForList("select code from accounts where tenant_id = ? order by code", String.class, tenantId);
    }

    private List<String> templateRoles(UUID tenantId) {
        return jdbc.queryForList(
                "select role from property_account_template_rows where tenant_id = ? order by role", String.class, tenantId);
    }

    private List<String> defaultRoles(UUID tenantId) {
        return jdbc.queryForList(
                "select role from tenant_default_account_mappings where tenant_id = ? order by role", String.class, tenantId);
    }

    private UUID newTenant(String prefix) {
        LandlordOrg org = new LandlordOrg();
        org.setName(prefix + UUID.randomUUID());
        return orgRepo.save(org).getId();
    }

    private User admin(UUID tenantId) {
        User u = new User();
        u.setEmail("seed-" + UUID.randomUUID() + "@t.io");
        u.setName("Seeder");
        u.setRole(UserRole.TENANT_ADMIN);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        return userRepo.save(u);
    }

    private ResponseEntity<List> seedAs(User caller) {
        return RestClient.builder().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/v1/finance/accounts/seed")
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString())
                .retrieve().toEntity(List.class);
    }

    private Account group(String code, String name, AccountType type, AccountSubType subType) {
        Account a = new Account();
        a.setCode(code);
        a.setName(name);
        a.setNameEn(name);
        a.setAccountType(type);
        a.setAccountSubType(subType);
        a.setGroup(true);
        return accountRepo.save(a);
    }

    /**
     * A chart with exactly two PACT codes among a handful of arbitrary ones: the
     * seed must come back 200, create template rows and defaults ONLY for the codes
     * that exist, and leave the rest alone rather than aborting the whole request.
     */
    @Test
    void seedOnANonPactChartIs200AndSeedsOnlyTheRowsWhoseCodesExist() {
        UUID tenantId = newTenant("NP-");
        TenantContextHolder.setTenantId(tenantId);
        try {
            // Arbitrary leaves plus two codes the template happens to reference.
            group("1000", "Bank of Nowhere", AccountType.ASSET, AccountSubType.BANK);
            group("2000", "Sundry Creditors", AccountType.LIABILITY, AccountSubType.PAYABLE);
            group("A-02-01", "Rental Receivable A/c", AccountType.ASSET, AccountSubType.RECEIVABLE);
            group("A-02-05-001", "Cash Account", AccountType.ASSET, AccountSubType.CASH);
        } finally {
            TenantContextHolder.clear();
        }

        ResponseEntity<List> res = seedAs(admin(tenantId));
        assertThat(res.getStatusCode().value()).isEqualTo(200);

        // The pre-existing chart is returned untouched — seedDefaultAccounts() is a no-op.
        assertThat(codes(tenantId)).containsExactlyInAnyOrder("1000", "2000", "A-02-01", "A-02-05-001");

        // RENT_RECEIVABLE hangs off A-02-01, which exists; nothing else in the
        // template does, so nothing else was created.
        assertThat(templateRoles(tenantId)).containsExactly(AccountRole.RENT_RECEIVABLE.name());
        assertThat(jdbc.queryForObject(
                "select a.code from property_account_template_rows r join accounts a on a.id = r.parent_account_id"
                        + " where r.tenant_id = ?", String.class, tenantId)).isEqualTo("A-02-01");

        // Same per-row rule for the tenant defaults: only CASH's code is present.
        assertThat(defaultRoles(tenantId)).containsExactly(AccountRole.CASH.name());
    }

    /** The PACT path is unchanged: a fresh tenant still gets the whole template and every default. */
    @Test
    void seedOnAFreshTenantStillSeedsTheWholeTemplateAndAllDefaults() {
        UUID tenantId = newTenant("NP-pact-");

        ResponseEntity<List> res = seedAs(admin(tenantId));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isNotEmpty();

        // 14 since the penalty module (spec §7.3) added OTHER_INCOME, which
        // PenaltyReason.OTHER credits and which had no mapping of any kind before.
        assertThat(templateRoles(tenantId))
                .hasSize(14)
                .contains(AccountRole.OTHER_INCOME.name(), AccountRole.RENT_PENALTY.name(),
                        AccountRole.CHEQUE_RETURN_PENALTY.name());
        assertThat(defaultRoles(tenantId)).contains(AccountRole.CASH.name(), AccountRole.OUTPUT_VAT.name(),
                AccountRole.INPUT_VAT.name(), AccountRole.ROUNDING_OFF.name(), AccountRole.DISCOUNT_ALLOWED.name(),
                AccountRole.FORFEITED_INCOME.name(), AccountRole.OPENING_BALANCE_DIFFERENCE.name());
        assertThat(codes(tenantId)).contains("A-02-01", "A-02-02", "C-01-01", "F-02");
    }
}
