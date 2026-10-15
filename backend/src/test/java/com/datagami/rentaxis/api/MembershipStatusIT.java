package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.UserService;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S17-01 and S17-02 (2-year simulation, 2026-10-16), over HTTP with real bearer
 * tokens as production does.
 *
 * <p>S17-01: an organisation's admin deactivates and reactivates one person's login
 * to that organisation without deleting it. The person's other organisations keep
 * working; that organisation leaves their 409 picker; its sessions end at once.
 * Never yourself, never the last active admin, never another organisation's row.
 *
 * <p>S17-02: changing a row's email drops its password and links; the new address
 * gets its person's own existing login (#407 rule 2) or an invite. The reviewer's
 * attack (repoint the row at an inbox the admin reads, set a password through the
 * link, repoint it back) no longer leaves a password the admin knows on the
 * shared-login address.
 */
class MembershipStatusIT extends AbstractCallerIdentityIT {

    private static final String OWN = "persons-own-password-1";
    private static final String NEW = "persons-new-password-2";

    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserService userService;

    private final ObjectMapper json = new ObjectMapper();

    // --- fixtures -------------------------------------------------------------

    private static String email() {
        return "s17-" + UUID.randomUUID() + "@test.invalid";
    }

    private User member(UUID tenantId, UserRole role, String email, String password, boolean selfSet) {
        User u = new User();
        u.setEmail(email);
        u.setName(role.name() + " " + UUID.randomUUID().toString().substring(0, 6));
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setPasswordSelfSet(selfSet);
        u.setPasswordSetAt(java.time.Instant.now());
        u.setWelcomedAt(java.time.Instant.now());
        u.setTenantId(tenantId);
        User saved = userRepo.save(u);
        userService.addTenantMembership(saved.getId(), tenantId);
        return saved;
    }

    private User superAdmin() {
        User u = new User();
        u.setEmail(email());
        u.setName("Platform admin");
        u.setRole(UserRole.SUPER_ADMIN);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash(passwordEncoder.encode("platform-admin-pw-1"));
        return userRepo.save(u);
    }

    private String hashOf(UUID userId) {
        return jdbc.queryForObject("select password_hash from users where id = ?", String.class, userId);
    }

    private String statusOf(UUID userId) {
        return jdbc.queryForObject("select status from users where id = ?", String.class, userId);
    }

    private String inviteTokenOf(UUID userId) {
        return jdbc.queryForObject("select invite_token from users where id = ?", String.class, userId);
    }

    private int invites(UUID userId) {
        return jdbc.queryForObject("select count(*) from email_outbox where event_type = 'USER_INVITED' "
                + "and recipient_user_id = ?", Integer.class, userId);
    }

    private ResponseEntity<String> as(User caller, HttpMethod method, String uri, Object body) {
        var spec = caller.getTenantId() == null
                ? client().method(method).uri(uri).header("Authorization", "Bearer "
                        + tokens.issue(caller.getId(), caller.getRole(), null, List.of()))
                : asSelf(method, uri, caller);
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
    }

    /** A token minted for {@code row} now, used later: proves old sessions end. */
    private String tokenFor(User row) {
        return tokens.issue(row.getId(), row.getRole(), row.getTenantId(), List.of(row.getTenantId()));
    }

    private int me(String token) {
        return status(client().get().uri("/api/auth/me").header("Authorization", "Bearer " + token));
    }

    private ResponseEntity<String> login(String email, String password, UUID tenantId) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", password);
        if (tenantId != null) body.put("tenantId", tenantId.toString());
        return client().post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
    }

    private int setPassword(String token, String password) {
        return client().post().uri("/api/auth/set-password").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("token", token, "newPassword", password))
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toBodilessEntity().getStatusCode().value();
    }

    private String code(ResponseEntity<String> res) throws Exception {
        return json.readTree(res.getBody()).path("code").asText();
    }

    private List<String> pickerTenants(ResponseEntity<String> res) throws Exception {
        return json.readTree(res.getBody()).get("tenants").findValuesAsText("tenantId");
    }

    // --- S17-01 ---------------------------------------------------------------

    @Test
    void deactivatingClosesThatOrganisationOnlyAndEndsItsSessionsAtOnce() throws Exception {
        UUID orgA = newTenant("S17-A");
        UUID orgB = newTenant("S17-B");
        String alice = email();
        User inA = member(orgA, UserRole.RENTER, alice, OWN, true);
        User inB = member(orgB, UserRole.RENTER, alice, OWN, true);
        User adminA = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-a-password", false);
        String sessionA = tokenFor(inA);
        String sessionB = tokenFor(inB);
        assertThat(me(sessionA)).isEqualTo(200);
        assertThat(pickerTenants(login(alice, OWN, null))).containsExactlyInAnyOrder(orgA.toString(), orgB.toString());

        ResponseEntity<String> off = as(adminA, HttpMethod.POST, "/api/admin/users/" + inA.getId() + "/deactivate", null);

        assertThat(off.getStatusCode().value()).as(off.getBody()).isEqualTo(200);
        assertThat(json.readTree(off.getBody()).get("status").asText()).isEqualTo("INACTIVE");
        assertThat(statusOf(inA.getId())).isEqualTo("INACTIVE");
        assertThat(me(sessionA)).as("org A's session ends on the next request").isEqualTo(401);
        assertThat(me(sessionB)).as("org B is untouched").isEqualTo(200);
        ResponseEntity<String> plain = login(alice, OWN, null);
        assertThat(plain.getStatusCode().value()).as("the picker no longer offers A: straight into B").isEqualTo(200);
        assertThat(json.readTree(plain.getBody()).get("tenantId").asText()).isEqualTo(orgB.toString());
        assertThat(login(alice, OWN, orgA).getStatusCode().value()).isEqualTo(403);
        assertThat(hashOf(inA.getId())).as("nothing deleted, password kept").isEqualTo(inA.getPasswordHash());

        ResponseEntity<String> on = as(adminA, HttpMethod.POST, "/api/admin/users/" + inA.getId() + "/reactivate", null);

        assertThat(on.getStatusCode().value()).as(on.getBody()).isEqualTo(200);
        JsonNode body = json.readTree(on.getBody());
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("reactivationLogin").asText()).isEqualTo("EXISTING_LOGIN");
        assertThat(pickerTenants(login(alice, OWN, null))).containsExactlyInAnyOrder(orgA.toString(), orgB.toString());
        assertThat(me(sessionA)).as("a session from before the deactivation stays ended").isEqualTo(401);
        assertThat(login(alice, OWN, orgA).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void aRowWhosePasswordWasClearedWhileInactiveTakesThePersonsCurrentPasswordOnReactivation() throws Exception {
        UUID orgA = newTenant("S17-A");
        UUID orgB = newTenant("S17-B");
        String alice = email();
        User inA = member(orgA, UserRole.RENTER, alice, OWN, true);
        User inB = member(orgB, UserRole.RENTER, alice, OWN, true);
        User adminA = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-a-password", false);
        assertThat(as(adminA, HttpMethod.POST, "/api/admin/users/" + inA.getId() + "/deactivate", null)
                .getStatusCode().value()).isEqualTo(200);

        // #407: changing her password while A is inactive clears A's copy of the old one.
        assertThat(as(inB, HttpMethod.PUT, "/api/auth/me/password",
                Map.of("currentPassword", OWN, "newPassword", NEW)).getStatusCode().value()).isEqualTo(200);
        assertThat(passwordEncoder.matches(OWN, hashOf(inA.getId()))).isFalse();

        ResponseEntity<String> on = as(adminA, HttpMethod.POST, "/api/admin/users/" + inA.getId() + "/reactivate", null);

        assertThat(json.readTree(on.getBody()).get("reactivationLogin").asText()).isEqualTo("EXISTING_LOGIN");
        assertThat(login(alice, NEW, orgA).getStatusCode().value()).isEqualTo(200);
        assertThat(login(alice, OWN, orgA).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void someoneWhoNeverSetAPasswordLosesTheirLinkOnDeactivationAndGetsAFreshInviteOnReactivation() throws Exception {
        UUID orgA = newTenant("S17-A");
        User adminA = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-a-password", false);
        ResponseEntity<String> created = as(adminA, HttpMethod.POST, "/api/admin/users",
                Map.of("email", email(), "name", "New Manager", "role", "PROPERTY_MANAGER"));
        UUID pm = UUID.fromString(json.readTree(created.getBody()).get("id").asText());
        String firstLink = inviteTokenOf(pm);
        assertThat(firstLink).isNotNull();
        assertThat(invites(pm)).isEqualTo(1);

        ResponseEntity<String> off = as(adminA, HttpMethod.POST, "/api/admin/users/" + pm + "/deactivate", null);
        assertThat(json.readTree(off.getBody()).get("invitePending").asBoolean()).isFalse();
        assertThat(setPassword(firstLink, NEW)).as("a suspended row holds no live link").isEqualTo(404);

        ResponseEntity<String> on = as(adminA, HttpMethod.POST, "/api/admin/users/" + pm + "/reactivate", null);

        JsonNode body = json.readTree(on.getBody());
        assertThat(body.get("reactivationLogin").asText()).isEqualTo("INVITED");
        assertThat(body.get("invitePending").asBoolean()).isTrue();
        assertThat(invites(pm)).isEqualTo(2);
        assertThat(inviteTokenOf(pm)).isNotEqualTo(firstLink);
    }

    @Test
    void aSingleOrganisationLoginKeepsItsOwnPasswordThroughDeactivationAndReactivation() throws Exception {
        UUID orgA = newTenant("S17-A");
        User adminA = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-a-password", false);
        User staff = member(orgA, UserRole.ACCOUNTANT, email(), OWN, true);

        as(adminA, HttpMethod.POST, "/api/admin/users/" + staff.getId() + "/deactivate", null);
        assertThat(login(staff.getEmail(), OWN, null).getStatusCode().value()).isEqualTo(403);
        ResponseEntity<String> on = as(adminA, HttpMethod.POST, "/api/admin/users/" + staff.getId() + "/reactivate", null);

        assertThat(json.readTree(on.getBody()).get("reactivationLogin").asText()).isEqualTo("OWN_PASSWORD");
        assertThat(invites(staff.getId())).isZero();
        assertThat(login(staff.getEmail(), OWN, null).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void anAdminCannotDeactivateThemselvesOrTheLastActiveAdmin() throws Exception {
        UUID orgA = newTenant("S17-A");
        User admin1 = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-1-password", false);
        User admin2 = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-2-password", false);
        User platform = superAdmin();

        ResponseEntity<String> self = as(admin1, HttpMethod.POST, "/api/admin/users/" + admin1.getId() + "/deactivate", null);
        assertThat(self.getStatusCode().value()).isEqualTo(400);
        assertThat(code(self)).isEqualTo(UserService.DEACTIVATE_SELF_CODE);
        assertThat(statusOf(admin1.getId())).isEqualTo("ACTIVE");

        // A platform admin row belongs to no organisation and is not switched off here.
        ResponseEntity<String> platformRow = as(admin1, HttpMethod.POST,
                "/api/admin/users/" + platform.getId() + "/deactivate", null);
        assertThat(platformRow.getStatusCode().value()).isIn(403, 404);
        assertThat(statusOf(platform.getId())).isEqualTo("ACTIVE");
        User otherPlatform = superAdmin();
        ResponseEntity<String> byPlatform = as(platform, HttpMethod.POST,
                "/api/admin/users/" + otherPlatform.getId() + "/deactivate", null);
        assertThat(byPlatform.getStatusCode().value()).isEqualTo(400);
        assertThat(code(byPlatform)).isEqualTo(UserService.NO_ORGANISATION_CODE);
        assertThat(statusOf(otherPlatform.getId())).isEqualTo("ACTIVE");

        assertThat(as(admin1, HttpMethod.POST, "/api/admin/users/" + admin2.getId() + "/deactivate", null)
                .getStatusCode().value()).isEqualTo(200);

        // admin1 is now the only active admin: not even a platform admin may switch them off.
        ResponseEntity<String> last = as(platform, HttpMethod.POST,
                "/api/admin/users/" + admin1.getId() + "/deactivate", null);
        assertThat(last.getStatusCode().value()).as(last.getBody()).isEqualTo(400);
        assertThat(code(last)).isEqualTo(UserService.LAST_ACTIVE_ADMIN_CODE);
        assertThat(statusOf(admin1.getId())).isEqualTo("ACTIVE");

        // A platform admin can reactivate admin2; then admin1 is no longer the last one.
        assertThat(as(platform, HttpMethod.POST, "/api/admin/users/" + admin2.getId() + "/reactivate", null)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(as(platform, HttpMethod.POST, "/api/admin/users/" + admin1.getId() + "/deactivate", null)
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void anOrganisationsAdminCannotChangeTheStatusOfAnotherOrganisationsRow() throws Exception {
        UUID orgA = newTenant("S17-A");
        UUID orgB = newTenant("S17-B");
        String alice = email();
        User inA = member(orgA, UserRole.RENTER, alice, OWN, true);
        User inB = member(orgB, UserRole.RENTER, alice, OWN, true);
        User adminA = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-a-password", false);
        User adminB = member(orgB, UserRole.TENANT_ADMIN, email(), "admin-b-password", false);
        User managerA = member(orgA, UserRole.PROPERTY_MANAGER, email(), "manager-a-password", false);

        assertThat(as(adminA, HttpMethod.POST, "/api/admin/users/" + inB.getId() + "/deactivate", null)
                .getStatusCode().value()).isIn(403, 404);
        assertThat(as(adminA, HttpMethod.POST, "/api/admin/users/" + adminB.getId() + "/deactivate", null)
                .getStatusCode().value()).isIn(403, 404);
        assertThat(statusOf(inB.getId())).isEqualTo("ACTIVE");
        assertThat(statusOf(adminB.getId())).isEqualTo("ACTIVE");

        // B's admin deactivates their own row of Alice; A cannot undo it.
        assertThat(as(adminB, HttpMethod.POST, "/api/admin/users/" + inB.getId() + "/deactivate", null)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(as(adminA, HttpMethod.POST, "/api/admin/users/" + inB.getId() + "/reactivate", null)
                .getStatusCode().value()).isIn(403, 404);
        assertThat(statusOf(inB.getId())).isEqualTo("INACTIVE");
        assertThat(statusOf(inA.getId())).isEqualTo("ACTIVE");

        // Below admin rank, the endpoints are closed altogether.
        assertThat(as(managerA, HttpMethod.POST, "/api/admin/users/" + inA.getId() + "/deactivate", null)
                .getStatusCode().value()).isEqualTo(403);
        assertThat(statusOf(inA.getId())).isEqualTo("ACTIVE");
    }

    // --- S17-02 ---------------------------------------------------------------

    /**
     * The re-review's run j1: A's admin repoints Alice's A row at an inbox A reads,
     * sets a password through the link mailed there, and points it back. Before the
     * fix Alice's address + A's password signed in to A as Alice.
     */
    @Test
    void repointingASharedLoginRowAndBackLeavesNoPasswordTheAdminKnows() throws Exception {
        UUID orgA = newTenant("S17-A");
        UUID orgB = newTenant("S17-B");
        String alice = email();
        User inB = member(orgB, UserRole.RENTER, alice, OWN, true);
        User adminA = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-a-password", false);
        ResponseEntity<String> added = as(adminA, HttpMethod.POST, "/api/v1/renters",
                Map.of("nameEn", "Alice", "email", alice));
        assertThat(json.readTree(added.getBody()).get("portalAccount").asText()).isEqualTo("EXISTING_LOGIN");
        UUID inA = jdbc.query("select user_id from renters where id = ?::uuid",
                rs -> rs.next() ? (UUID) rs.getObject(1) : null, json.readTree(added.getBody()).get("id").asText());

        // 1. Repoint at an inbox the admin reads: the row's password goes, an invite goes there.
        String adminInbox = email();
        assertThat(as(adminA, HttpMethod.PUT, "/api/admin/users/" + inA, Map.of("email", adminInbox))
                .getStatusCode().value()).isEqualTo(200);
        assertThat(passwordEncoder.matches(OWN, hashOf(inA))).as("Alice's password left with her address").isFalse();
        assertThat(invites(inA)).isEqualTo(1);

        // 2. The admin sets a password through that link, then through a reset link too.
        assertThat(setPassword(inviteTokenOf(inA), "admin-chosen-pw-1")).isIn(200, 204);
        assertThat(as(adminA, HttpMethod.POST, "/api/admin/users/" + inA + "/send-password-reset", null)
                .getStatusCode().value()).isEqualTo(204);
        assertThat(setPassword(inviteTokenOf(inA), "admin-chosen-pw-2")).isIn(200, 204);
        assertThat(login(adminInbox, "admin-chosen-pw-2", orgA).getStatusCode().value()).isEqualTo(200);

        // 3. Point it back at Alice.
        ResponseEntity<String> back = as(adminA, HttpMethod.PUT, "/api/admin/users/" + inA, Map.of("email", alice));
        assertThat(back.getStatusCode().value()).as(back.getBody()).isEqualTo(200);

        assertThat(login(alice, "admin-chosen-pw-2", orgA).getStatusCode().value())
                .as("the admin's password does not sign in as Alice").isEqualTo(401);
        assertThat(login(alice, "admin-chosen-pw-1", orgA).getStatusCode().value()).isEqualTo(401);
        // #407 rule 2 for the new address: Alice's own login, no invite.
        assertThat(hashOf(inA)).isEqualTo(inB.getPasswordHash());
        assertThat(jdbc.queryForObject("select password_self_set from users where id = ?", Boolean.class, inA)).isTrue();
        assertThat(invites(inA)).as("no invite to someone who has a login").isEqualTo(1);
        assertThat(pickerTenants(login(alice, OWN, null))).containsExactlyInAnyOrder(orgA.toString(), orgB.toString());
        assertThat(hashOf(inB.getId())).as("B's row untouched").isEqualTo(inB.getPasswordHash());
    }

    /** The same attack through the tenant edit ({@code PUT /api/v1/renters/{id}}), which moves the portal login's email. */
    @Test
    void repointingThroughTheTenantEditLeavesNoPasswordTheAdminKnowsEither() throws Exception {
        UUID orgA = newTenant("S17-A");
        UUID orgB = newTenant("S17-B");
        String alice = email();
        User inB = member(orgB, UserRole.RENTER, alice, OWN, true);
        User adminA = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-a-password", false);
        ResponseEntity<String> added = as(adminA, HttpMethod.POST, "/api/v1/renters",
                Map.of("nameEn", "Alice", "email", alice));
        String renterId = json.readTree(added.getBody()).get("id").asText();
        UUID inA = jdbc.query("select user_id from renters where id = ?::uuid",
                rs -> rs.next() ? (UUID) rs.getObject(1) : null, renterId);

        String adminInbox = email();
        assertThat(as(adminA, HttpMethod.PUT, "/api/v1/renters/" + renterId,
                Map.of("nameEn", "Alice", "email", adminInbox)).getStatusCode().value()).isEqualTo(200);
        assertThat(passwordEncoder.matches(OWN, hashOf(inA))).isFalse();
        assertThat(invites(inA)).as("the new address is invited").isEqualTo(1);
        assertThat(setPassword(inviteTokenOf(inA), "admin-chosen-pw-1")).isIn(200, 204);

        assertThat(as(adminA, HttpMethod.PUT, "/api/v1/renters/" + renterId,
                Map.of("nameEn", "Alice", "email", alice)).getStatusCode().value()).isEqualTo(200);

        assertThat(login(alice, "admin-chosen-pw-1", orgA).getStatusCode().value()).isEqualTo(401);
        assertThat(hashOf(inA)).isEqualTo(inB.getPasswordHash());
        assertThat(login(alice, OWN, orgA).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void changingAnEmailToAnAddressWithNoLoginClearsThePasswordAndInvitesTheNewAddress() throws Exception {
        UUID orgA = newTenant("S17-A");
        User adminA = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-a-password", false);
        User staff = member(orgA, UserRole.PROPERTY_MANAGER, email(), "typed-by-admin-1", false);
        String sessionBefore = tokenFor(staff);
        String next = email();

        ResponseEntity<String> edit = as(adminA, HttpMethod.PUT, "/api/admin/users/" + staff.getId(),
                Map.of("email", next));

        assertThat(edit.getStatusCode().value()).as(edit.getBody()).isEqualTo(200);
        assertThat(json.readTree(edit.getBody()).get("invitePending").asBoolean()).isTrue();
        assertThat(invites(staff.getId())).isEqualTo(1);
        assertThat(login(next, "typed-by-admin-1", null).getStatusCode().value()).isEqualTo(401);
        assertThat(login(staff.getEmail(), "typed-by-admin-1", null).getStatusCode().value()).isEqualTo(401);
        assertThat(me(sessionBefore)).isEqualTo(401);
        // The new address's person sets their own.
        assertThat(setPassword(inviteTokenOf(staff.getId()), NEW)).isIn(200, 204);
        assertThat(login(next, NEW, null).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void anInactiveRowWhoseEmailChangesIsInvitedOnlyWhenReactivated() throws Exception {
        UUID orgA = newTenant("S17-A");
        User adminA = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-a-password", false);
        User renter = member(orgA, UserRole.RENTER, email(), OWN, true);
        as(adminA, HttpMethod.POST, "/api/admin/users/" + renter.getId() + "/deactivate", null);

        assertThat(as(adminA, HttpMethod.PUT, "/api/admin/users/" + renter.getId(), Map.of("email", email()))
                .getStatusCode().value()).isEqualTo(200);
        assertThat(invites(renter.getId())).as("no link while inactive").isZero();
        assertThat(passwordEncoder.matches(OWN, hashOf(renter.getId()))).isFalse();

        ResponseEntity<String> on = as(adminA, HttpMethod.POST, "/api/admin/users/" + renter.getId() + "/reactivate", null);
        assertThat(json.readTree(on.getBody()).get("reactivationLogin").asText()).isEqualTo("INVITED");
        assertThat(invites(renter.getId())).isEqualTo(1);
    }
}
