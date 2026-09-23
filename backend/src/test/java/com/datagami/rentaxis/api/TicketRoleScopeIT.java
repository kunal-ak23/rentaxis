package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 5, tickets by role (audit D-F1 / #73) and who a ticket may be assigned to
 * (audit D-F2), over HTTP.
 *
 * <ul>
 *   <li>TENANT_USER: what they reported, list and every per-ticket route.</li>
 *   <li>SECURITY_GUARD: nothing; guards have no ticket screen.</li>
 *   <li>ACCOUNTANT: tenant-wide read, as before, and no writes.</li>
 *   <li>assign: an ACTIVE admin or manager of this tenant (a manager on the
 *       ticket's building); never a renter, never another tenant's user, whose name
 *       the old unscoped lookup used to echo back.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TicketRoleScopeIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private UUID tenantId;
    private Property property;
    private User admin;
    private User tenantUser;
    private String ownTicket;
    private String otherTicket;

    @BeforeEach
    void setUp() {
        tenantId = org("TRS");
        TenantContextHolder.setTenantId(tenantId);
        Property p = new Property();
        p.setNameEn("Tower " + UUID.randomUUID());
        p.setEmirate(Emirate.DUBAI);
        property = propertyRepo.save(p);
        admin = user(tenantId, UserRole.TENANT_ADMIN);
        tenantUser = user(tenantId, UserRole.TENANT_USER);
        TenantContextHolder.clear();

        ownTicket = ticket("Reported by the tenant user");
        otherTicket = ticket("Reported by somebody else");
        jdbc.update("UPDATE maintenance_tickets SET reported_by = ? WHERE id = ?",
                tenantUser.getId(), UUID.fromString(ownTicket));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    // ------------------------------------------------------------ D-F1 / #73

    @Test
    void aTenantUserReachesOnlyWhatTheyReported() {
        assertThat(ids(call(tenantUser, HttpMethod.GET, "/api/v1/tickets", null))).containsExactly(ownTicket);
        for (String route : List.of("", "/replies", "/history", "/attachments")) {
            assertThat(status(tenantUser, HttpMethod.GET, "/api/v1/tickets/" + ownTicket + route)).isEqualTo(HttpStatus.OK);
            assertThat(status(tenantUser, HttpMethod.GET, "/api/v1/tickets/" + otherTicket + route))
                    .as(route).isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(call(tenantUser, HttpMethod.POST, "/api/v1/tickets/" + otherTicket + "/replies",
                Map.of("message", "hello")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(tenantUser, HttpMethod.POST, "/api/v1/tickets/" + ownTicket + "/replies",
                Map.of("message", "any update?")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aGuardSeesNoTickets() {
        TenantContextHolder.setTenantId(tenantId);
        User guard = user(tenantId, UserRole.SECURITY_GUARD);
        TenantContextHolder.clear();

        assertThat(ids(call(guard, HttpMethod.GET, "/api/v1/tickets", null))).isEmpty();
        assertThat(status(guard, HttpMethod.GET, "/api/v1/tickets/" + otherTicket)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(guard, HttpMethod.GET, "/api/v1/tickets/" + otherTicket + "/replies"))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anAccountantReadsTenantWideButCannotWrite() {
        TenantContextHolder.setTenantId(tenantId);
        User accountant = user(tenantId, UserRole.ACCOUNTANT);
        TenantContextHolder.clear();

        assertThat(ids(call(accountant, HttpMethod.GET, "/api/v1/tickets", null)))
                .containsExactlyInAnyOrder(ownTicket, otherTicket);
        assertThat(status(accountant, HttpMethod.GET, "/api/v1/tickets/" + otherTicket)).isEqualTo(HttpStatus.OK);
        assertThat(call(accountant, HttpMethod.POST, "/api/v1/tickets/" + otherTicket + "/replies",
                Map.of("message", "hi")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ----------------------------------------------------------------- D-F2

    @Test
    void aTicketIsAssignedOnlyToActiveStaffOfThisTenant() {
        UUID foreignTenant = org("TRS-FOREIGN");
        TenantContextHolder.setTenantId(foreignTenant);
        User foreignAdmin = user(foreignTenant, UserRole.TENANT_ADMIN, "Foreign Secret Name");
        TenantContextHolder.setTenantId(tenantId);
        User renter = user(tenantId, UserRole.RENTER);
        User inactivePm = user(tenantId, UserRole.PROPERTY_MANAGER);
        inactivePm.setStatus(UserStatus.INACTIVE);
        userRepo.save(inactivePm);
        assign(inactivePm);
        User pmElsewhere = user(tenantId, UserRole.PROPERTY_MANAGER);
        User pmHere = user(tenantId, UserRole.PROPERTY_MANAGER, "Manager Here");
        assign(pmHere);
        TenantContextHolder.clear();

        ResponseEntity<String> foreign = assignTo(foreignAdmin.getId());
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreign.getBody()).doesNotContain("Foreign Secret Name");
        assertThat(history(otherTicket)).doesNotContain("Foreign Secret Name");

        assertThat(assignTo(renter.getId()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(assignTo(inactivePm.getId()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(assignTo(pmElsewhere.getId()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> ok = assignTo(pmHere.getId());
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(ok).get("assigneeName").asText()).isEqualTo("Manager Here");
        assertThat(assignTo(admin.getId()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * A multi-tenant admin works in this organisation through a
     * user_tenant_memberships row; their home tenant is another one. The tenant
     * filter used to hide their user row, so they could not be given a ticket
     * here. A membership property manager still needs the building.
     */
    @Test
    void aMembershipAdminCanBeAssignedAndAMembershipManagerNeedsTheBuilding() {
        UUID home = org("TRS-HOME");
        User memberAdmin = user(home, UserRole.TENANT_ADMIN, "Member Admin");
        User memberPm = user(home, UserRole.PROPERTY_MANAGER, "Member PM");
        User homeOnlyAdmin = user(home, UserRole.TENANT_ADMIN, "Home Only");
        for (User u : List.of(memberAdmin, memberPm)) {
            jdbc.update("INSERT INTO user_tenant_memberships (user_id, tenant_id) VALUES (?, ?)", u.getId(), tenantId);
        }

        ResponseEntity<String> ok = assignTo(memberAdmin.getId());
        assertThat(ok.getStatusCode()).as(ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(body(ok).get("assigneeName").asText()).isEqualTo("Member Admin");

        assertThat(assignTo(memberPm.getId()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assign(memberPm);
        assertThat(assignTo(memberPm.getId()).getStatusCode()).isEqualTo(HttpStatus.OK);

        // No membership here: not one of this tenant's users.
        assertThat(assignTo(homeOnlyAdmin.getId()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        jdbc.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", memberAdmin.getId());
        assertThat(assignTo(memberAdmin.getId()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * An organisation the platform team runs has no staff of its own (prod
     * TUTORIAL-MIFTAH-2Y): the super admin takes the ticket themselves. Only
     * themselves — another super admin's account is not a user of this tenant,
     * and a tenant admin cannot hand a ticket to one.
     */
    @Test
    void aSuperAdminCanAssignATicketToThemselvesOnly() {
        User superAdmin = user(null, UserRole.SUPER_ADMIN, "Platform Admin");
        User otherSuperAdmin = user(null, UserRole.SUPER_ADMIN, "Other Platform Admin");

        ResponseEntity<String> self = callIn(superAdmin, tenantId, HttpMethod.PUT,
                "/api/v1/tickets/" + otherTicket + "/assign", Map.of("assignTo", superAdmin.getId().toString()));
        assertThat(self.getStatusCode()).as(self.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(body(self).get("assigneeName").asText()).isEqualTo("Platform Admin");

        assertThat(callIn(superAdmin, tenantId, HttpMethod.PUT, "/api/v1/tickets/" + otherTicket + "/assign",
                Map.of("assignTo", otherSuperAdmin.getId().toString())).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(assignTo(superAdmin.getId()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * The "Assign To..." picker lists exactly who the assign call accepts: active
     * admins (home or membership) and active managers of the ticket's building.
     * A manager of another building cannot even ask.
     */
    @Test
    void theAssigneePickerListsOnlyWhoTheTicketCanBeAssignedTo() {
        UUID foreignTenant = org("TRS-FOREIGN");
        User foreignAdmin = user(foreignTenant, UserRole.TENANT_ADMIN);
        User memberAdmin = user(foreignTenant, UserRole.TENANT_ADMIN);
        jdbc.update("INSERT INTO user_tenant_memberships (user_id, tenant_id) VALUES (?, ?)",
                memberAdmin.getId(), tenantId);
        TenantContextHolder.setTenantId(tenantId);
        User renter = user(tenantId, UserRole.RENTER);
        User inactivePm = user(tenantId, UserRole.PROPERTY_MANAGER);
        inactivePm.setStatus(UserStatus.INACTIVE);
        userRepo.save(inactivePm);
        assign(inactivePm);
        User pmElsewhere = user(tenantId, UserRole.PROPERTY_MANAGER);
        User pmHere = user(tenantId, UserRole.PROPERTY_MANAGER);
        assign(pmHere);
        User accountant = user(tenantId, UserRole.ACCOUNTANT);
        TenantContextHolder.clear();

        String path = "/api/v1/tickets/" + otherTicket + "/assignees";
        ResponseEntity<String> res = call(admin, HttpMethod.GET, path, null);
        assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(ids(res)).containsExactlyInAnyOrder(
                admin.getId().toString(), memberAdmin.getId().toString(), pmHere.getId().toString());
        assertThat(ids(res)).doesNotContain(foreignAdmin.getId().toString(), renter.getId().toString(),
                inactivePm.getId().toString(), pmElsewhere.getId().toString(), tenantUser.getId().toString());

        // Every name offered is accepted.
        for (String id : ids(res)) {
            assertThat(assignTo(UUID.fromString(id)).getStatusCode()).as(id).isEqualTo(HttpStatus.OK);
        }

        assertThat(ids(call(pmHere, HttpMethod.GET, path, null))).contains(pmHere.getId().toString());
        assertThat(status(pmElsewhere, HttpMethod.GET, path)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(accountant, HttpMethod.GET, path)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(tenantUser, HttpMethod.GET, path)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -------------------------------------------------------------- plumbing

    private ResponseEntity<String> assignTo(UUID userId) {
        return call(admin, HttpMethod.PUT, "/api/v1/tickets/" + otherTicket + "/assign",
                Map.of("assignTo", userId.toString()));
    }

    private String history(String ticketId) {
        return call(admin, HttpMethod.GET, "/api/v1/tickets/" + ticketId + "/history", null).getBody();
    }

    private void assign(User pm) {
        UserPropertyAssignment a = new UserPropertyAssignment();
        a.setUserId(pm.getId());
        a.setPropertyId(property.getId());
        assignmentRepo.save(a);
    }

    private UUID org(String prefix) {
        LandlordOrg org = new LandlordOrg();
        org.setName(prefix + "-" + UUID.randomUUID());
        return orgRepo.save(org).getId();
    }

    private User user(UUID tenant, UserRole role) {
        return user(tenant, role, role.name());
    }

    private User user(UUID tenant, UserRole role, String name) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(name);
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenant);
        if (role == UserRole.SECURITY_GUARD) {
            u.setPhoneNumber("+9715" + String.format("%08d",
                    Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 100_000_000L)));
        }
        return userRepo.save(u);
    }

    private String ticket(String title) {
        ResponseEntity<String> res = call(admin, HttpMethod.POST, "/api/v1/tickets",
                Map.of("propertyId", property.getId().toString(), "title", title));
        assertThat(res.getStatusCode().is2xxSuccessful()).as(res.getBody()).isTrue();
        return body(res).get("id").asText();
    }

    private ResponseEntity<String> call(User caller, HttpMethod method, String path, Object body) {
        RestClient.RequestBodySpec spec = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .method(method).uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString());
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    /** A caller acting in {@code activeTenant}; a super admin has no home tenant. */
    private ResponseEntity<String> callIn(User caller, UUID activeTenant, HttpMethod method, String path, Object body) {
        RestClient.RequestBodySpec spec = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .method(method).uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", activeTenant.toString());
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    private HttpStatus status(User caller, HttpMethod method, String path) {
        return HttpStatus.valueOf(call(caller, method, path, null).getStatusCode().value());
    }

    private List<String> ids(ResponseEntity<String> res) {
        List<String> out = new ArrayList<>();
        body(res).forEach(n -> out.add(n.get("id").asText()));
        return out;
    }

    private JsonNode body(ResponseEntity<String> res) {
        try {
            return json.readTree(res.getBody());
        } catch (Exception e) {
            throw new AssertionError("Not JSON (" + res.getStatusCode() + "): " + res.getBody(), e);
        }
    }
}
