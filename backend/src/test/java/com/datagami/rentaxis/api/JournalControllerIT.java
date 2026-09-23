package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
class JournalControllerIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired PropertyRepository propertyRepo;
    @Autowired PostingService posting;

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

    /**
     * The same guard over HTTP: {@code POST /journals/{id}/reverse} is for manual
     * journal vouchers. An entry that belongs to a document is a 400 naming where
     * to correct it — reversing it here would leave that document posted and its
     * ledger empty. {@link com.datagami.rentaxis.core.service.ledger.JournalReverseSourceGuardIT}
     * covers one case per source-type family.
     */
    @Test
    void reversingADocumentsJournalIs400() {
        UUID entryId;
        TenantContextHolder.setTenantId(tenantId);
        try {
            entryId = posting.post(new PostingRequest(
                    JournalDocType.PISR, LocalDate.of(2026, 9, 17), "voucher fixture",
                    PostingRequest.Dimensions.none(), JournalSourceType.VOUCHER, UUID.randomUUID(), null,
                    List.of(PostingRequest.dr(UUID.fromString(bankId), new BigDecimal("100.00")),
                            PostingRequest.cr(UUID.fromString(capitalId), new BigDecimal("100.00"))))).getId();
        } finally {
            TenantContextHolder.clear();
        }

        assertThatThrownBy(() -> postAs(accountant, "/api/v1/finance/journals/" + entryId + "/reverse")
                .body(Map.of("date", "2026-09-18", "reason", "x")).retrieve().body(Map.class))
                .isInstanceOf(HttpClientErrorException.BadRequest.class)
                .hasMessageContaining("voucher");
        assertThat(getAs(accountant, "/api/v1/finance/journals/" + entryId).get("status")).isEqualTo("POSTED");
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

    // ---- dimension ids on a manual voucher are unchecked raw columns ----

    private UUID propertyIn(UUID tenant) {
        TenantContextHolder.setTenantId(tenant);
        try {
            Property p = new Property();
            p.setNameEn("Tower " + UUID.randomUUID());
            p.setEmirate(Emirate.DUBAI);
            return propertyRepo.save(p).getId();
        } finally {
            TenantContextHolder.clear();
        }
    }

    private Map<String, Object> jvWith(String key, Object value) {
        Map<String, Object> body = new LinkedHashMap<>(jv("100"));
        body.put(key, value);
        return body;
    }

    /**
     * propertyId / unitId / leaseId / renterId are nullable analytics columns on the
     * entry with no foreign key behind them, so an id belonging to another landlord
     * used to be stored verbatim and read back on this tenant's ledger. Inside the
     * @Transactional post the tenant filter is active, so a foreign id is simply
     * unknown — a 400, not a silent cross-tenant dimension.
     */
    @Test
    void anotherTenantsPropertyIdIs400() {
        LandlordOrg other = new LandlordOrg();
        other.setName("JC-dim-" + UUID.randomUUID());
        UUID foreignProperty = propertyIn(orgRepo.save(other).getId());

        assertThatThrownBy(() -> postAs(accountant, "/api/v1/finance/journals")
                .body(jvWith("propertyId", foreignProperty.toString())).retrieve().body(Map.class))
                .isInstanceOf(HttpClientErrorException.BadRequest.class)
                .hasMessageContaining("Unknown property id");
    }

    @Test
    void ownTenantsPropertyIdIsAccepted() {
        UUID ownProperty = propertyIn(tenantId);

        Map<?, ?> posted = postAs(accountant, "/api/v1/finance/journals")
                .body(jvWith("propertyId", ownProperty.toString())).retrieve().body(Map.class);

        assertThat(posted.get("propertyId")).isEqualTo(ownProperty.toString());
    }

    @Test
    void anUnknownLineDimensionIdIs400() {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("accountId", bankId);
        line.put("debit", 10);
        line.put("credit", 0);
        line.put("leaseId", UUID.randomUUID().toString());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entryDate", "2026-09-17");
        body.put("narration", "x");
        body.put("lines", List.of(line, Map.of("accountId", capitalId, "debit", 0, "credit", 10)));

        assertThatThrownBy(() -> postAs(accountant, "/api/v1/finance/journals").body(body).retrieve().body(Map.class))
                .isInstanceOf(HttpClientErrorException.BadRequest.class)
                .hasMessageContaining("Unknown lease id");
    }

    /** A literal null inside the lines array dereferenced straight into a 500. */
    @Test
    void aNullLineIs400NotA500() {
        List<Object> lines = new ArrayList<>(Arrays.asList(
                Map.of("accountId", bankId, "debit", 10, "credit", 0),
                null,
                Map.of("accountId", capitalId, "debit", 0, "credit", 10)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entryDate", "2026-09-17");
        body.put("narration", "x");
        body.put("lines", lines);

        assertThatThrownBy(() -> postAs(accountant, "/api/v1/finance/journals").body(body).retrieve().body(Map.class))
                .isInstanceOf(HttpClientErrorException.BadRequest.class)
                .hasMessageContaining("A journal line is missing");
    }

    @Test
    void unknownEntryIs404() {
        assertThatThrownBy(() -> getAs(accountant, "/api/v1/finance/journals/" + UUID.randomUUID()))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }
}
