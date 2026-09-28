package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.CrossTenantHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Organisation branding (2026-09-28): the stamp is saved exactly like the logo —
 * the same organisation endpoint, the same role, one organisation at a time — and
 * the organisation list a signed-in user reads for the header carries a logo
 * version (never the private storage URL).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrgBrandingIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired PropertyService propertyService;
    @Autowired JdbcTemplate jdbc;

    private static final String STAMP = "https://acct.blob.core.windows.net/shared/assets/stamp.png";
    private static final String LOGO = "https://acct.blob.core.windows.net/shared/assets/logo.png";

    private CrossTenantHttp http;
    private User superAdmin;
    private UUID orgA;
    private UUID orgB;

    @BeforeEach
    void setUp() {
        http = new CrossTenantHttp(port, orgRepo, userRepo, accountService, propertyAccountService, propertyService);
        superAdmin = http.user(http.tenant("SA-home-"), UserRole.SUPER_ADMIN);
        orgA = http.tenant("Brand A ");
        orgB = http.tenant("Brand B ");
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private String stamp(UUID org) {
        return jdbc.queryForObject("select stamp_image_url from landlord_org where id = ?", String.class, org);
    }

    @Test
    void theSuperAdminSavesAndClearsTheStampOfOneOrganisationOnly() {
        var save = http.call(superAdmin, HttpMethod.PUT, "/api/admin/tenants/" + orgA, Map.of("stampImageUrl", STAMP));
        assertThat(save.getStatusCode().value()).isEqualTo(200);
        assertThat(save.getBody().get("stampImageUrl")).isEqualTo(STAMP);
        assertThat(stamp(orgA)).isEqualTo(STAMP);
        assertThat(stamp(orgB)).isNull();
        // R5-I1: saving a stamp records when (the executed-copy sweep only retries later posts).
        java.sql.Timestamp setAt = jdbc.queryForObject("select stamp_set_at from landlord_org where id = ?",
                java.sql.Timestamp.class, orgA);
        assertThat(setAt).isNotNull();

        // A save that does not name the stamp leaves it alone (partial update).
        http.call(superAdmin, HttpMethod.PUT, "/api/admin/tenants/" + orgA, Map.of("address", "Somewhere"));
        assertThat(stamp(orgA)).isEqualTo(STAMP);
        assertThat(jdbc.queryForObject("select stamp_set_at from landlord_org where id = ?",
                java.sql.Timestamp.class, orgA)).isEqualTo(setAt);

        // A stale dialog is refused like any other field.
        var stale = http.call(superAdmin, HttpMethod.PUT, "/api/admin/tenants/" + orgA,
                Map.of("stampImageUrl", "", "expected", Map.of("stampImageUrl", "")));
        assertThat(stale.getStatusCode().value()).isEqualTo(409);
        assertThat(stamp(orgA)).isEqualTo(STAMP);

        var clear = http.call(superAdmin, HttpMethod.PUT, "/api/admin/tenants/" + orgA,
                Map.of("stampImageUrl", "", "expected", Map.of("stampImageUrl", STAMP)));
        assertThat(clear.getStatusCode().value()).isEqualTo(200);
        assertThat(stamp(orgA)).isNullOrEmpty();
        assertThat(jdbc.queryForObject("select stamp_set_at from landlord_org where id = ?",
                java.sql.Timestamp.class, orgA)).isNull();
    }

    @Test
    void anOrganisationsOwnStaffCannotSetAStampOrLogo() {
        for (UserRole role : new UserRole[]{UserRole.TENANT_ADMIN, UserRole.PROPERTY_MANAGER, UserRole.TENANT_USER}) {
            User own = http.user(orgA, role);
            var res = http.call(own, HttpMethod.PUT, "/api/admin/tenants/" + orgA,
                    Map.of("stampImageUrl", STAMP, "logoUrl", LOGO));
            assertThat(res.getStatusCode().value()).as(role.name()).isEqualTo(403);
            var other = http.call(own, HttpMethod.PUT, "/api/admin/tenants/" + orgB, Map.of("stampImageUrl", STAMP));
            assertThat(other.getStatusCode().value()).as(role.name()).isEqualTo(403);
        }
        assertThat(stamp(orgA)).isNull();
        assertThat(stamp(orgB)).isNull();
    }

    @Test
    void theHeaderOrganisationListCarriesTheLogoOfTheCallersOwnOrganisationOnly() {
        jdbc.update("update landlord_org set logo_url = ? where id = ?", LOGO, orgA);
        jdbc.update("update landlord_org set logo_url = ? where id = ?", LOGO + "?b", orgB);
        User admin = http.user(orgA, UserRole.TENANT_ADMIN);
        jdbc.update("insert into user_tenant_memberships (user_id, tenant_id, created_at) values (?, ?, now())",
                admin.getId(), orgA);

        @SuppressWarnings({"unchecked", "rawtypes"})
        List<Map<String, Object>> mine = (List) http.request(admin, HttpMethod.GET, "/api/auth/me/tenants")
                .retrieve().body(List.class);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0)).containsEntry("id", orgA.toString()).containsEntry("logoVersion", OrgBrandingController.versionOf(LOGO))
                .doesNotContainKey("logoUrl");

        @SuppressWarnings({"unchecked", "rawtypes"})
        List<Map<String, Object>> all = (List) http.request(superAdmin, HttpMethod.GET, "/api/auth/me/tenants")
                .retrieve().body(List.class);
        assertThat(all).anySatisfy(o -> assertThat(o).containsEntry("id", orgB.toString())
                .containsEntry("logoVersion", OrgBrandingController.versionOf(LOGO + "?b")));
    }
}
