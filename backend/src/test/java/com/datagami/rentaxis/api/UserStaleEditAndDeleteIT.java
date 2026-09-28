package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.UserService;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Break-it round 3 (ops3) F4 and F5.
 *
 * <p>F4: two admin tabs edit the same property manager. Tab B removes PropB. Tab A,
 * opened before, changes only the name and saves — the old client sent the full
 * property list it had loaded, and PropB was back. Now the panel sends only what
 * changed, and property assignments carry the set the panel loaded.</p>
 *
 * <p>F5: deleting a user left their open tickets ASSIGNED to nobody. They go back to
 * the queue now, recorded in each ticket's history, and the delete dialog can say how
 * many first.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserStaleEditAndDeleteIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired PropertyService propertyService;
    @Autowired UserService userService;
    @Autowired JdbcTemplate jdbc;

    private CrossTenantHttp http;
    private UUID tenant;
    private User admin;
    private User pm;
    private UUID sweepHouse;
    private UUID propB;

    @BeforeEach
    void setUp() {
        http = new CrossTenantHttp(port, orgRepo, userRepo, accountService, propertyAccountService, propertyService);
        tenant = http.tenant("UserEdit-");
        sweepHouse = http.property("Sweep House").getId();
        propB = http.property("BRK3-OPS PropB").getId();
        admin = http.admin(tenant);
        pm = http.user(tenant, UserRole.PROPERTY_MANAGER);
        userService.assignPropertyToUser(pm.getId(), sweepHouse);
        userService.assignPropertyToUser(pm.getId(), propB);
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private List<UUID> assignments() {
        return jdbc.queryForList("select property_id from user_property_assignments where user_id = ?",
                UUID.class, pm.getId());
    }

    private String name() {
        return jdbc.queryForObject("select name from users where id = ?", String.class, pm.getId());
    }

    private String url() {
        return "/api/admin/users/" + pm.getId();
    }

    @Test
    void aStalePanelChangingOnlyTheNameNoLongerRestoresARemovedProperty() {
        // Tab B removes PropB, naming the set it loaded.
        var b = http.call(admin, HttpMethod.PUT, url(), Map.of(
                "propertyIds", List.of(sweepHouse), "expectedPropertyIds", List.of(sweepHouse, propB)));
        assertThat(b.getStatusCode().value()).isEqualTo(200);
        assertThat(assignments()).containsExactly(sweepHouse);

        // Tab A changes only the name: the panel sends only the name.
        var a = http.call(admin, HttpMethod.PUT, url(), Map.of("name", "BRK3-OPS pm3 renamed"));

        assertThat(a.getStatusCode().value()).isEqualTo(200);
        assertThat(name()).isEqualTo("BRK3-OPS pm3 renamed");
        assertThat(assignments()).containsExactly(sweepHouse);
        assertThat(jdbc.queryForObject("select role from users where id = ?", String.class, pm.getId()))
                .isEqualTo("PROPERTY_MANAGER");
        assertThat(jdbc.queryForObject("select email from users where id = ?", String.class, pm.getId()))
                .isEqualTo(pm.getEmail());
        assertThat(jdbc.queryForObject("select tenant_id from users where id = ?", UUID.class, pm.getId()))
                .isEqualTo(tenant);
    }

    @Test
    void aStalePanelTouchingAssignmentsIsRefusedAndWritesNothing() {
        http.call(admin, HttpMethod.PUT, url(), Map.of(
                "propertyIds", List.of(sweepHouse), "expectedPropertyIds", List.of(sweepHouse, propB)));

        Map<String, Object> tabA = new HashMap<>();
        tabA.put("name", "Stale rename");
        tabA.put("propertyIds", List.of(sweepHouse, propB));
        tabA.put("expectedPropertyIds", List.of(sweepHouse, propB));
        var a = http.call(admin, HttpMethod.PUT, url(), tabA);

        assertThat(a.getStatusCode().value()).isEqualTo(409);
        assertThat(a.getBody().get("code")).isEqualTo("user.changed");
        assertThat(assignments()).containsExactly(sweepHouse);
        assertThat(name()).isNotEqualTo("Stale rename");
    }

    @Test
    void anOlderClientWithoutExpectedPropertyIdsBehavesAsBefore() {
        Map<String, Object> full = new HashMap<>();
        full.put("email", pm.getEmail());
        full.put("name", "Full payload");
        full.put("role", "PROPERTY_MANAGER");
        full.put("tenantId", tenant.toString());
        full.put("phoneNumber", "");
        full.put("propertyIds", List.of(propB));
        assertThat(http.call(admin, HttpMethod.PUT, url(), full).getStatusCode().value()).isEqualTo(200);
        assertThat(assignments()).containsExactly(propB);
        assertThat(name()).isEqualTo("Full payload");
    }

    // ---- F5 ----

    private UUID ticket(String status, UUID assignee) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into maintenance_tickets (id, tenant_id, property_id, reported_by, assigned_to, title, status,"
                        + " reported_date, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, current_date, now(), now())",
                id, tenant, propB, admin.getId(), assignee, "Leak " + status, status);
        return id;
    }

    private Map<String, Object> ticketRow(UUID id) {
        return jdbc.queryForMap("select status, assigned_to, version from maintenance_tickets where id = ?", id);
    }

    @Test
    void deletingAUserReturnsTheirOpenTicketsToTheQueueWithHistory() {
        UUID assigned = ticket("ASSIGNED", pm.getId());
        UUID inProgress = ticket("IN_PROGRESS", pm.getId());
        UUID reopened = ticket("REOPENED", pm.getId());
        UUID resolved = ticket("RESOLVED", pm.getId());
        UUID someoneElses = ticket("ASSIGNED", admin.getId());

        var preview = http.call(admin, HttpMethod.GET, url() + "/delete-preview", null);
        assertThat(preview.getStatusCode().value()).isEqualTo(200);
        assertThat(preview.getBody().get("openTickets")).isEqualTo(3);
        assertThat(preview.getBody().get("meetings")).isEqualTo(0);

        assertThat(http.call(admin, HttpMethod.DELETE, url(), null).getStatusCode().value()).isEqualTo(200);

        assertThat(ticketRow(assigned)).containsEntry("status", "OPEN").containsEntry("assigned_to", null);
        assertThat(ticketRow(inProgress)).containsEntry("status", "OPEN").containsEntry("assigned_to", null);
        assertThat(ticketRow(reopened)).containsEntry("status", "REOPENED").containsEntry("assigned_to", null);
        // Closed work keeps its record of who did it.
        assertThat(ticketRow(resolved)).containsEntry("status", "RESOLVED").containsEntry("assigned_to", pm.getId());
        assertThat(ticketRow(someoneElses)).containsEntry("status", "ASSIGNED").containsEntry("assigned_to", admin.getId());

        Map<String, Object> history = jdbc.queryForMap(
                "select action, from_status, to_status, assigned_from, assigned_to, performed_by, tenant_id"
                        + " from ticket_history where ticket_id = ?", assigned);
        assertThat(history).containsEntry("action", "UNASSIGNED").containsEntry("from_status", "ASSIGNED")
                .containsEntry("to_status", "OPEN").containsEntry("assigned_from", pm.getId())
                .containsEntry("assigned_to", null).containsEntry("performed_by", admin.getId())
                .containsEntry("tenant_id", tenant);
        assertThat(jdbc.queryForObject("select count(*) from ticket_history where ticket_id in (?, ?)",
                Long.class, resolved, someoneElses)).isZero();
    }
}
