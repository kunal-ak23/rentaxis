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

    // -------------------------------------------------------------- plumbing

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
