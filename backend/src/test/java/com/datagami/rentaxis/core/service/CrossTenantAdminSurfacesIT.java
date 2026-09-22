package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two admin surfaces that answered {@code findAll()} with no transaction of
 * their own: the user directory and org settings.
 *
 * <p>Sibling of {@link CrossTenantReadGuardIT} and the same argument — {@code
 * TenantAspect} enables the Hibernate tenant filter on the session bound to the
 * current transaction, so a repository read from an unannotated method runs
 * unfiltered and {@code findAll()} means "every landlord's rows". These tests go
 * through the HTTP surface as well as the service, because for org settings the
 * unfiltered read is in the controller itself.</p>
 *
 * <p>Both endpoints are reached here the way the Next.js proxy reaches them, with
 * the legacy {@code X-User-*} headers: that is what makes the tenant context in
 * these tests the same one production builds (see {@code ApiSecurityFilter}).</p>
 *
 * <h2>Why the service assertions and the HTTP assertions disagree</h2>
 * <p>Running both is what turned this up: with a tenant in context the HTTP tests
 * passed before the fix while the service test on the same method failed.
 * {@code spring.jpa.open-in-view} is unset, so it is on — Spring Boot logs the
 * "enabled by default" warning at every boot — and an open EntityManager bound to
 * the request is the session {@code TenantAspect} enables the filter on AND the
 * session the query then runs on, even with no transaction anywhere. So the
 * missing {@code @Transactional} is currently covered, over HTTP, by a default
 * that Spring Boot itself prints a warning asking you to turn off.</p>
 *
 * <p>Two things escape that cover and are live today, which the failing tests
 * below were: a caller with <em>no</em> tenant context (filter off inside the
 * request session too), and any call that does not arrive on a request thread at
 * all — a scheduled job, an event listener, an async task. The service-level
 * assertions here are written against the method rather than the endpoint for
 * that reason, and they are what stays red if OSIV is ever disabled.</p>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class CrossTenantAdminSurfacesIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired UserService users;
    @Autowired OrgSettingsService orgSettings;
    @Autowired UserRepository userRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired JdbcTemplate jdbc;

    UUID tenantA, tenantB;
    UUID adminA, adminB, staffA, staffB;

    @BeforeEach
    void setUp() {
        TenantContextHolder.clear();
        // org_settings is keyed by nothing but tenant_id, and these tests assert on
        // which row comes back first — so start from a table this class owns.
        jdbc.update("DELETE FROM org_settings");

        tenantA = tenant("A");
        tenantB = tenant("B");
        adminA = userIn(tenantA, UserRole.TENANT_ADMIN, "admin-a");
        adminB = userIn(tenantB, UserRole.TENANT_ADMIN, "admin-b");
        staffA = userIn(tenantA, UserRole.PROPERTY_MANAGER, "pm-a");
        staffB = userIn(tenantB, UserRole.PROPERTY_MANAGER, "pm-b");
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    // ---- leak 1: the user directory ----------------------------------------

    /**
     * {@code UserService.getAllUsers} was {@code userRepository.findAll()}. Called
     * with tenant A in context it must mean tenant A's users — every name, email
     * and phone number in the table otherwise belongs to whoever asks.
     */
    @Test
    void aTenantAdminListingUsersSeesOnlyTheirOwnTenantsUsers() {
        TenantContextHolder.setTenantId(tenantA);

        List<UUID> visible = users.getAllUsers().stream().map(User::getId).toList();

        assertThat(visible).contains(adminA, staffA).doesNotContain(adminB, staffB);
    }

    /** The same thing through {@code GET /api/admin/users}, which is what leaked. */
    @Test
    void theUsersEndpointAnswersATenantAdminWithOnlyTheirOwnTenantsUsers() throws Exception {
        mvc.perform(as(get("/api/admin/users"), adminA, "TENANT_ADMIN", tenantA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(staffA.toString())))
                .andExpect(jsonPath("$[*].id", not(hasItem(staffB.toString()))))
                .andExpect(jsonPath("$[*].id", not(hasItem(adminB.toString()))));
    }

    /**
     * The behaviour that must survive the fix: cross-tenant administration. A
     * SUPER_ADMIN that has selected no organisation sends no tenant header, so
     * there is no tenant context and the directory stays global.
     */
    @Test
    void aSuperAdminWithNoTenantContextStillSeesEveryTenantsUsers() throws Exception {
        mvc.perform(get("/api/admin/users")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "SUPER_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(staffA.toString())))
                .andExpect(jsonPath("$[*].id", hasItem(staffB.toString())));
    }

    /**
     * The by-id surfaces of the same controller, asserted rather than assumed.
     *
     * <p>The assertion is the refusal, not its number. Two independent gates say
     * no here and they answer differently: while the tenant filter is on,
     * {@code findById} cannot load the row at all and {@code authorizeTargetUser}
     * throws NotFound (404); with the filter off, the row loads and the explicit
     * tenant comparison throws AccessDenied (403). Pinning one of those numbers
     * would pin which gate fired, which is not the property that matters.</p>
     */
    @Test
    void aTenantAdminCannotReachAnotherTenantsUserById() throws Exception {
        mvc.perform(as(get("/api/admin/users/" + staffB + "/properties"), adminA, "TENANT_ADMIN", tenantA))
                .andExpect(status().is4xxClientError());

        mvc.perform(as(put("/api/admin/users/" + staffB), adminA, "TENANT_ADMIN", tenantA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"taken-over@example.com\",\"name\":\"Taken over\","
                                + "\"role\":\"PROPERTY_MANAGER\"}"))
                .andExpect(status().is4xxClientError());

        mvc.perform(as(delete("/api/admin/users/" + staffB), adminA, "TENANT_ADMIN", tenantA))
                .andExpect(status().is4xxClientError());

        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, staffB))
                .isEqualTo(emailOf("pm-b"));
    }

    /**
     * And the layer underneath it: {@code updateUser} is {@code @Transactional},
     * so its {@code findById} runs with the filter on and cannot even load the
     * row. Written down because "the controller checks" is one deletion away from
     * being the only check.
     */
    @Test
    void updateUserCannotLoadAnotherTenantsUserFromInsideItsTransaction() {
        TenantContextHolder.setTenantId(tenantA);

        assertThatThrownBy(() -> users.updateUser(staffB, "taken-over@example.com", null, "Taken over",
                UserRole.PROPERTY_MANAGER, tenantA.toString(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User not found");
    }

    // ---- leak 2: org settings ----------------------------------------------

    /**
     * Tenant B's row is inserted first on purpose: {@code rows.get(0)} of an
     * unfiltered {@code findAll()} is whichever row the table hands back first, and
     * the renter portal prints it as "How to pay".
     */
    @Test
    void orgSettingsReadsTheCallersOwnRowEvenWhenAnotherTenantsWasCreatedFirst() throws Exception {
        orgSettingsRow(tenantB, "Pay tenant B: IBAN AE99 BBBB");
        orgSettingsRow(tenantA, "Pay tenant A: IBAN AE11 AAAA");

        mvc.perform(as(get("/api/v1/settings/org"), staffA, "RENTER", tenantA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.penaltyPaymentInstructions", is("Pay tenant A: IBAN AE11 AAAA")));
    }

    /**
     * The same read called directly, outside any request — where the open
     * EntityManager that covers the endpoint does not exist. This is the org
     * settings twin of {@code aTenantAdminListingUsersSeesOnlyTheirOwnTenantsUsers}:
     * remove the tenant predicate from {@code OrgSettingsService} and this one goes
     * red while the HTTP tests stay green.
     */
    @Test
    void theOrgSettingsServiceReadsTheCallersOwnRowWithNoRequestAroundIt() {
        orgSettingsRow(tenantB, "Pay tenant B: IBAN AE99 BBBB");
        orgSettingsRow(tenantA, "Pay tenant A: IBAN AE11 AAAA");

        TenantContextHolder.setTenantId(tenantA);

        assertThat(orgSettings.getPenaltyPaymentInstructions()).isEqualTo("Pay tenant A: IBAN AE11 AAAA");
    }

    /** A write by tenant A must leave tenant B's row exactly as it was. */
    @Test
    void orgSettingsWriteTouchesOnlyTheCallersRow() throws Exception {
        UUID rowB = orgSettingsRow(tenantB, "Pay tenant B: IBAN AE99 BBBB");
        orgSettingsRow(tenantA, "Pay tenant A: IBAN AE11 AAAA");
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM org_settings WHERE id = ?", rowB);

        mvc.perform(as(put("/api/v1/settings/org"), adminA, "TENANT_ADMIN", tenantA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"penaltyPaymentInstructions\":\"Pay tenant A: new IBAN AE22 AAAA\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForMap("SELECT * FROM org_settings WHERE id = ?", rowB))
                .as("tenant B's row, after tenant A saved theirs")
                .isEqualTo(before);
        assertThat(instructionsOf(tenantA)).isEqualTo("Pay tenant A: new IBAN AE22 AAAA");
    }

    /** And when the caller has no row yet, it creates one rather than editing someone else's. */
    @Test
    void orgSettingsWriteCreatesTheCallersRowWhenOnlyAnotherTenantHasOne() throws Exception {
        UUID rowB = orgSettingsRow(tenantB, "Pay tenant B: IBAN AE99 BBBB");
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM org_settings WHERE id = ?", rowB);

        mvc.perform(as(put("/api/v1/settings/org"), adminA, "TENANT_ADMIN", tenantA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"penaltyPaymentInstructions\":\"Pay tenant A: IBAN AE11 AAAA\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForMap("SELECT * FROM org_settings WHERE id = ?", rowB)).isEqualTo(before);
        assertThat(instructionsOf(tenantA)).isEqualTo("Pay tenant A: IBAN AE11 AAAA");
    }

    /**
     * With no organisation selected there is no row to mean. This is the one case
     * that leaks over HTTP as well as through the service: no tenant context means
     * {@code TenantAspect} leaves the filter off even inside the request-scoped
     * session, so the unfiltered {@code findAll().get(0)} hands a SUPER_ADMIN some
     * arbitrary landlord's "How to pay" text.
     */
    @Test
    void withNoOrganisationSelectedOrgSettingsReadsEmpty() throws Exception {
        orgSettingsRow(tenantB, "Pay tenant B: IBAN AE99 BBBB");

        mvc.perform(get("/api/v1/settings/org")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "SUPER_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.penaltyPaymentInstructions", is("")));
    }

    /**
     * The same edge on the write side, which is the fraud-relevant one: a
     * SUPER_ADMIN with no organisation selected must be told to select one, not
     * silently edit whichever landlord's row comes back first.
     */
    @Test
    void withNoOrganisationSelectedTheOrgSettingsWriteIsRefused() throws Exception {
        UUID rowB = orgSettingsRow(tenantB, "Pay tenant B: IBAN AE99 BBBB");
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM org_settings WHERE id = ?", rowB);

        int status = mvc.perform(put("/api/v1/settings/org")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "SUPER_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"penaltyPaymentInstructions\":\"Pay head office\"}"))
                .andReturn().getResponse().getStatus();

        // The row first, deliberately: what this test is about is a landlord's
        // payment instructions being rewritten by someone who never named that
        // landlord, and asserting the status code first would report a number
        // instead of that.
        assertThat(jdbc.queryForMap("SELECT * FROM org_settings WHERE id = ?", rowB))
                .as("tenant B's row, after a SUPER_ADMIN with no organisation selected saved")
                .isEqualTo(before);
        assertThat(status).as("PUT with no organisation selected").isEqualTo(400);
    }

    // ---- fixtures ----------------------------------------------------------

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID userId, String role,
            UUID tenantId) {
        return request
                .header("X-User-Id", userId.toString())
                .header("X-User-Role", role)
                .header("X-User-Tenant-Id", tenantId.toString())
                .header("X-Tenant-Id", tenantId.toString());
    }

    private UUID tenant(String label) {
        TenantContextHolder.clear();
        LandlordOrg org = new LandlordOrg();
        org.setName("XT-" + label + "-" + UUID.randomUUID());
        return orgRepo.save(org).getId();
    }

    private final String suffix = UUID.randomUUID().toString();

    private String emailOf(String local) {
        return local + "+" + suffix + "@example.com";
    }

    private UUID userIn(UUID tenantId, UserRole role, String local) {
        TenantContextHolder.clear();
        User u = new User();
        u.setEmail(emailOf(local));
        u.setName(local);
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("not-a-credential");
        u.setTenantId(tenantId);
        return userRepo.save(u).getId();
    }

    /** Inserted through JDBC so the row's tenant is stated, not inferred from context. */
    private UUID orgSettingsRow(UUID tenantId, String instructions) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO org_settings (id, landlord_org_id, tenant_id, penalty_payment_instructions)"
                + " VALUES (?,?,?,?)", id, tenantId, tenantId, instructions);
        return id;
    }

    private String instructionsOf(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT penalty_payment_instructions FROM org_settings WHERE tenant_id = ?", String.class, tenantId);
    }
}
