package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HTTP-level cover for {@link JournalController}: an accountant posts a manual
 * journal voucher, finds it in the list, reads it back with its lines and
 * reverses it; a property manager is refused; an unbalanced voucher is a 400.
 *
 * <p>Auth context normally injected by the Next.js proxy is simulated with the
 * X-User-* / X-Tenant-* headers {@code ApiSecurityFilter} reads, as in
 * {@link UserControllerRoleAuthorizationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class JournalControllerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort int port;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;

    UUID tenantId;
    User accountant;
    User manager;
    String bankId;
    String capitalId;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("JC-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        try {
            accounts.seedDefaultAccounts();
            propertyAccounts.seedDefaultTemplateAndDefaults();
            bankId = accounts.createLeaf("ENBD Main", accounts.getAccountByCode("A-02-02"), null).getId().toString();
            capitalId = accounts.getAccountByCode("F-01").getId().toString();
            accountant = user(UserRole.ACCOUNTANT);
            manager = user(UserRole.PROPERTY_MANAGER);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private User user(UserRole role) {
        return user(role, tenantId);
    }

    private User user(UserRole role, UUID tenant) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenant);
        return userRepo.save(u);
    }

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private RestClient.RequestBodySpec postAs(User caller, String uri) {
        return client().post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString());
    }

    private Map<?, ?> getAs(User caller, String uri) {
        return client().get().uri(uri)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString())
                .retrieve().body(Map.class);
    }

    private Map<String, Object> jv(String amount) {
        return Map.of("entryDate", "2026-09-17", "narration", "Capital injection", "lines", List.of(
                Map.of("accountId", bankId, "debit", amount, "credit", 0),
                Map.of("accountId", capitalId, "debit", 0, "credit", amount)));
    }

    /** The JSON number comes back as a Double or an Integer depending on scale; compare numerically. */
    private static BigDecimal number(Object raw) {
        return new BigDecimal(String.valueOf(raw));
    }

    @Test
    void accountantPostsListsReadsAndReversesAManualJournal() {
        Map<?, ?> posted = postAs(accountant, "/api/v1/finance/journals").body(jv("5000")).retrieve().body(Map.class);
        assertThat(posted.get("entryNumber")).isEqualTo("JV-26/1");
        assertThat(posted.get("docType")).isEqualTo("JV");
        assertThat(posted.get("status")).isEqualTo("POSTED");
        assertThat((List<?>) posted.get("lines")).hasSize(2);
        String id = (String) posted.get("id");

        Map<?, ?> page = getAs(accountant, "/api/v1/finance/journals?docType=JV");
        List<?> content = (List<?>) page.get("content");
        assertThat(content).hasSize(1);
        Map<?, ?> row = (Map<?, ?>) content.get(0);
        assertThat(row.get("id")).isEqualTo(id);
        // A list row carries its total but not its lines.
        assertThat((List<?>) row.get("lines")).isEmpty();
        assertThat(number(row.get("total"))).isEqualByComparingTo("5000");

        Map<?, ?> detail = getAs(accountant, "/api/v1/finance/journals/" + id);
        assertThat(number(detail.get("total"))).isEqualByComparingTo("5000");
        assertThat((List<?>) detail.get("lines")).hasSize(2);

        Map<?, ?> rev = postAs(accountant, "/api/v1/finance/journals/" + id + "/reverse")
                .body(Map.of("date", "2026-09-18", "reason", "typo")).retrieve().body(Map.class);
        assertThat(rev.get("reversalOfId")).isEqualTo(id);
        assertThat(rev.get("entryNumber")).isEqualTo("JV-26/2");
        assertThat(getAs(accountant, "/api/v1/finance/journals/" + id).get("status")).isEqualTo("REVERSED");
    }

    @Test
    void docTypesAreListed() {
        @SuppressWarnings("unchecked")
        List<String> types = client().get().uri("/api/v1/finance/journals/doc-types")
                .header("X-User-Id", accountant.getId().toString())
                .header("X-User-Role", accountant.getRole().name())
                .header("X-Tenant-Id", accountant.getTenantId().toString())
                .header("X-User-Tenant-Id", accountant.getTenantId().toString())
                .retrieve().body(List.class);
        assertThat(types).contains("JV", "TCO", "OB");
    }

    @Test
    void propertyManagerIsForbidden() {
        assertThatThrownBy(() -> postAs(manager, "/api/v1/finance/journals").body(jv("10")).retrieve().body(Map.class))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
        assertThatThrownBy(() -> getAs(manager, "/api/v1/finance/journals"))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void unbalancedManualJournalIs400WithMessage() {
        Map<String, Object> bad = Map.of("entryDate", "2026-09-17", "narration", "x", "lines", List.of(
                Map.of("accountId", bankId, "debit", 10, "credit", 0),
                Map.of("accountId", capitalId, "debit", 0, "credit", 9)));
        assertThatThrownBy(() -> postAs(accountant, "/api/v1/finance/journals").body(bad).retrieve().body(Map.class))
                .isInstanceOf(HttpClientErrorException.BadRequest.class)
                .hasMessageContaining("not balanced");
    }

    @Test
    void aLineThatIsBothDebitAndCreditIs400() {
        Map<String, Object> bad = Map.of("entryDate", "2026-09-17", "narration", "x", "lines", List.of(
                Map.of("accountId", bankId, "debit", 10, "credit", 10),
                Map.of("accountId", capitalId, "debit", 0, "credit", 10)));
        assertThatThrownBy(() -> postAs(accountant, "/api/v1/finance/journals").body(bad).retrieve().body(Map.class))
                .isInstanceOf(HttpClientErrorException.BadRequest.class)
                .hasMessageContaining("not both");
    }

    /** Entries are tenant-scoped data: another landlord's accountant must not see them (CLAUDE.md P0). */
    @Test
    void anotherTenantsAccountantSeesNeitherTheEntryNorTheList() {
        Map<?, ?> posted = postAs(accountant, "/api/v1/finance/journals").body(jv("5000")).retrieve().body(Map.class);
        String id = (String) posted.get("id");

        LandlordOrg other = new LandlordOrg();
        other.setName("JC-other-" + UUID.randomUUID());
        User outsider = user(UserRole.ACCOUNTANT, orgRepo.save(other).getId());

        assertThatThrownBy(() -> getAs(outsider, "/api/v1/finance/journals/" + id))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
        assertThat((List<?>) getAs(outsider, "/api/v1/finance/journals").get("content")).isEmpty();
    }

    @Test
    void unknownEntryIs404() {
        assertThatThrownBy(() -> getAs(accountant, "/api/v1/finance/journals/" + UUID.randomUUID()))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }
}
