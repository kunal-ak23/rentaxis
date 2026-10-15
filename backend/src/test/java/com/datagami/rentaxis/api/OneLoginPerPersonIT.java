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
 * One login per person across organisations (2026-10-15).
 *
 * <ul>
 *   <li>The password belongs to the person (normalised email): a password the
 *       person sets — invite or reset link, or a change from their own password —
 *       is written to every active membership row with that email.</li>
 *   <li>Adding someone who already has a self-set login (any org, any role)
 *       reuses it: outcome {@code EXISTING_LOGIN}, no invite.</li>
 *   <li>An admin never sets a password that opens another organisation: a typed
 *       password is refused for a shared login, and the admin reset is an emailed
 *       link the person redeems.</li>
 * </ul>
 *
 * Every call goes through HTTP with a real bearer token, as production does.
 */
class OneLoginPerPersonIT extends AbstractCallerIdentityIT {

    private static final String OLD = "persons-own-password-1";
    private static final String NEW = "persons-new-password-2";

    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserService userService;

    private final ObjectMapper json = new ObjectMapper();

    // --- fixtures -------------------------------------------------------------

    private static String email() {
        return "olpp-" + UUID.randomUUID() + "@test.invalid";
    }

    /** A row in {@code tenantId} with its membership; {@code selfSet} marks the hash as the person's own. */
    private User member(UUID tenantId, UserRole role, String email, String password, boolean selfSet) {
        User u = new User();
        u.setEmail(email);
        u.setName(role.name() + " " + UUID.randomUUID().toString().substring(0, 6));
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setPasswordSelfSet(selfSet);
        u.setTenantId(tenantId);
        User saved = userRepo.save(u);
        userService.addTenantMembership(saved.getId(), tenantId);
        return saved;
    }

    private String hashOf(UUID userId) {
        return jdbc.queryForObject("select password_hash from users where id = ?", String.class, userId);
    }

    private boolean matches(UUID userId, String raw) {
        return passwordEncoder.matches(raw, hashOf(userId));
    }

    private int outbox(String eventType, UUID userId) {
        return jdbc.queryForObject("select count(*) from email_outbox where event_type = ? and dedup_key like ?",
                Integer.class, eventType, eventType + ":" + userId + "%");
    }

    private ResponseEntity<String> as(User caller, HttpMethod method, String uri, Object body) {
        var spec = asSelf(method, uri, caller);
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
    }

    private ResponseEntity<String> login(String email, String password, UUID tenantId) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", password);
        if (tenantId != null) body.put("tenantId", tenantId.toString());
        return client().post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
    }

    private JsonNode createRenter(User admin, String email) throws Exception {
        ResponseEntity<String> res = as(admin, HttpMethod.POST, "/api/v1/renters",
                Map.of("nameEn", "Shared Person", "email", email));
        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(201);
        return json.readTree(res.getBody());
    }

    private UUID portalUserOf(JsonNode renter) {
        return jdbc.query("select user_id from renters where id = ?::uuid",
                rs -> rs.next() ? (UUID) rs.getObject(1) : null, renter.get("id").asText());
    }

    // --- rule 2: an existing login skips the invite ----------------------------

    @Test
    void aRenterWhoAlreadyHasALoginElsewhereIsAddedWithoutAnInviteAndSignsInWithTheirPassword() throws Exception {
        UUID orgA = newTenant("OLPP-A");
        UUID orgB = newTenant("OLPP-B");
        String email = email();
        User inB = member(orgB, UserRole.RENTER, email, OLD, true);
        User adminA = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-a-password", false);

        JsonNode created = createRenter(adminA, email.toUpperCase());

        assertThat(created.get("portalAccount").asText()).isEqualTo("EXISTING_LOGIN");
        UUID inA = portalUserOf(created);
        assertThat(inA).isNotNull().isNotEqualTo(inB.getId());
        User rowA = userRepo.findById(inA).orElseThrow();
        assertThat(rowA.getTenantId()).isEqualTo(orgA);
        assertThat(rowA.getRole()).isEqualTo(UserRole.RENTER);
        assertThat(rowA.getInviteToken()).as("no set-password link for someone who has a password").isNull();
        assertThat(outbox("USER_INVITED", inA)).isZero();
        assertThat(matches(inA, OLD)).as("the person's existing password opens the new membership").isTrue();
        assertThat(hashOf(inB.getId())).as("the other org's row is untouched").isEqualTo(inB.getPasswordHash());

        // Rule 4: one password, two organisations -> the picker, then either org.
        ResponseEntity<String> ambiguous = login(email, OLD, null);
        assertThat(ambiguous.getStatusCode().value()).isEqualTo(409);
        List<String> offered = json.readTree(ambiguous.getBody()).get("tenants").findValuesAsText("tenantId");
        assertThat(offered).containsExactlyInAnyOrder(orgA.toString(), orgB.toString());
        ResponseEntity<String> intoA = login(email, OLD, orgA);
        assertThat(intoA.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(intoA.getBody()).get("tenantId").asText()).isEqualTo(orgA.toString());
        assertThat(json.readTree(intoA.getBody()).get("id").asText()).isEqualTo(inA.toString());
    }

    @Test
    void aLoginWhosePasswordWasNotSetByThePersonIsNeverCopiedSoTheyAreInvitedAsBefore() throws Exception {
        UUID orgA = newTenant("OLPP-A");
        UUID orgB = newTenant("OLPP-B");
        String email = email();
        // e.g. an admin of B typed it, or it predates this change
        User inB = member(orgB, UserRole.RENTER, email, OLD, false);
        User adminA = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-a-password", false);

        JsonNode created = createRenter(adminA, email);

        assertThat(created.get("portalAccount").asText()).isEqualTo("INVITED");
        UUID inA = portalUserOf(created);
        assertThat(outbox("USER_INVITED", inA)).isEqualTo(1);
        assertThat(matches(inA, OLD)).as("B's admin-known password must not open A").isFalse();
        assertThat(hashOf(inB.getId())).isEqualTo(inB.getPasswordHash());
    }

    @Test
    void aStaffUserWhoAlreadyHasALoginElsewhereIsAddedWithoutAnInvite() throws Exception {
        UUID orgA = newTenant("OLPP-A");
        UUID orgB = newTenant("OLPP-B");
        String email = email();
        member(orgB, UserRole.TENANT_ADMIN, email, OLD, true);
        User adminA = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-a-password", false);

        ResponseEntity<String> res = as(adminA, HttpMethod.POST, "/api/admin/users",
                Map.of("email", email, "name", "Shared Manager", "role", "PROPERTY_MANAGER"));

        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(200);
        JsonNode body = json.readTree(res.getBody());
        assertThat(body.get("existingLogin").asBoolean()).isTrue();
        assertThat(body.get("invitePending").asBoolean()).isFalse();
        UUID inA = UUID.fromString(body.get("id").asText());
        assertThat(outbox("USER_INVITED", inA)).isZero();
        assertThat(matches(inA, OLD)).isTrue();
    }

    @Test
    void anAdminCannotTypeAPasswordForSomeoneWhoSignsInToAnotherOrganisation() throws Exception {
        UUID orgA = newTenant("OLPP-A");
        UUID orgB = newTenant("OLPP-B");
        String email = email();
        User inB = member(orgB, UserRole.RENTER, email, OLD, true);
        User inA = member(orgA, UserRole.RENTER, email, OLD, true);
        User adminA = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-a-password", false);

        // creating with a typed password
        ResponseEntity<String> create = as(adminA, HttpMethod.POST, "/api/admin/users",
                Map.of("email", email.replace("olpp-", "olpp2-"), "name", "x", "role", "TENANT_ADMIN",
                        "password", "typed-by-admin-1"));
        assertThat(create.getStatusCode().value()).as("an unshared email may still be created with a password")
                .isEqualTo(200);
        User adminB = member(orgB, UserRole.TENANT_ADMIN, email(), "admin-b-password", false);
        ResponseEntity<String> createShared = as(adminA, HttpMethod.POST, "/api/admin/users",
                Map.of("email", adminB.getEmail(), "name", "x", "role", "TENANT_ADMIN",
                        "password", "typed-by-admin-1"));
        assertThat(createShared.getStatusCode().value()).isEqualTo(400);
        assertThat(createShared.getBody()).contains("user.passwordSharedAcrossOrgs");

        // editing with a typed password
        ResponseEntity<String> edit = as(adminA, HttpMethod.PUT, "/api/admin/users/" + inA.getId(),
                Map.of("password", "typed-by-admin-2"));
        assertThat(edit.getStatusCode().value()).isEqualTo(400);
        assertThat(edit.getBody()).contains("user.passwordSharedAcrossOrgs");
        assertThat(hashOf(inA.getId())).isEqualTo(inA.getPasswordHash());
        assertThat(hashOf(inB.getId())).isEqualTo(inB.getPasswordHash());
        assertThat(login(email, "typed-by-admin-2", orgB).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void aTypedPasswordForAnAccountThatExistsOnlyInTheAdminsOrgIsAllowedButNeverCopiedElsewhere() throws Exception {
        UUID orgA = newTenant("OLPP-A");
        UUID orgB = newTenant("OLPP-B");
        String email = email();
        User inA = member(orgA, UserRole.RENTER, email, OLD, true);
        User adminA = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-a-password", false);

        ResponseEntity<String> edit = as(adminA, HttpMethod.PUT, "/api/admin/users/" + inA.getId(),
                Map.of("password", "typed-by-admin-3"));
        assertThat(edit.getStatusCode().value()).as(edit.getBody()).isEqualTo(200);
        assertThat(matches(inA.getId(), "typed-by-admin-3")).isTrue();

        // Org B later adds the same person: admin A knows that password, so it must not open B.
        User adminB = member(orgB, UserRole.TENANT_ADMIN, email(), "admin-b-password", false);
        JsonNode created = createRenter(adminB, email);
        assertThat(created.get("portalAccount").asText()).isEqualTo("INVITED");
        assertThat(matches(portalUserOf(created), "typed-by-admin-3")).isFalse();
    }

    // --- rule 1: the person's password goes everywhere --------------------------

    @Test
    void changingYourOwnPasswordChangesItInEveryOrganisation() throws Exception {
        UUID orgA = newTenant("OLPP-A");
        UUID orgB = newTenant("OLPP-B");
        String email = email();
        User inA = member(orgA, UserRole.RENTER, email, OLD, true);
        User inB = member(orgB, UserRole.PROPERTY_MANAGER, email, OLD, true);
        String tokenB = json.readTree(login(email, OLD, orgB).getBody()).get("token").asText();

        ResponseEntity<String> res = as(inA, HttpMethod.PUT, "/api/auth/me/password",
                Map.of("currentPassword", OLD, "newPassword", NEW));

        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(200);
        assertThat(matches(inA.getId(), NEW)).isTrue();
        assertThat(matches(inB.getId(), NEW)).as("the same person's other membership").isTrue();
        assertThat(login(email, OLD, orgB).getStatusCode().value()).isEqualTo(401);
        assertThat(status(client().get().uri("/api/auth/me").header("Authorization", "Bearer " + tokenB)))
                .as("sessions opened with the old password end in every org").isEqualTo(401);
        assertThat(login(email, NEW, null).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void aChangeFromAPasswordOnlyOneRowKnowsDoesNotReachTheOtherOrganisation() throws Exception {
        UUID orgA = newTenant("OLPP-A");
        UUID orgB = newTenant("OLPP-B");
        String email = email();
        // A split left from before this change (rule 5): A's row has a password
        // admin A typed; B's row has the person's own.
        User inA = member(orgA, UserRole.RENTER, email, "admin-a-knows-this", false);
        User inB = member(orgB, UserRole.RENTER, email, OLD, true);

        ResponseEntity<String> res = as(inA, HttpMethod.PUT, "/api/auth/me/password",
                Map.of("currentPassword", "admin-a-knows-this", "newPassword", NEW));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(matches(inA.getId(), NEW)).isTrue();
        assertThat(hashOf(inB.getId())).as("knowing A's password proves nothing about B").isEqualTo(inB.getPasswordHash());
    }

    @Test
    void redeemingAnInviteSetsThePasswordForEveryActiveMembershipButNotGuardsOrInactiveRows() throws Exception {
        UUID orgA = newTenant("OLPP-A");
        UUID orgB = newTenant("OLPP-B");
        UUID orgC = newTenant("OLPP-C");
        UUID orgD = newTenant("OLPP-D");
        String email = email();
        User inB = member(orgB, UserRole.RENTER, email, "a-different-legacy-pw", false);
        User inactive = member(orgC, UserRole.RENTER, email, "inactive-pw", false);
        jdbc.update("update users set status = 'INACTIVE' where id = ?", inactive.getId());
        User guard = member(orgD, UserRole.SECURITY_GUARD, email, "guard-unusable", false);
        User adminA = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-a-password", false);
        JsonNode created = createRenter(adminA, email);
        assertThat(created.get("portalAccount").asText()).isEqualTo("INVITED");
        UUID inA = portalUserOf(created);
        String token = userRepo.findById(inA).orElseThrow().getInviteToken();

        ResponseEntity<String> res = client().post().uri("/api/auth/set-password")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("token", token, "newPassword", NEW))
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);

        assertThat(res.getStatusCode().value()).isEqualTo(204);
        assertThat(matches(inA, NEW)).isTrue();
        assertThat(matches(inB.getId(), NEW)).as("the link proved the inbox; every active row follows").isTrue();
        assertThat(hashOf(inactive.getId())).isEqualTo(inactive.getPasswordHash());
        assertThat(hashOf(guard.getId())).as("guards sign in by phone").isEqualTo(guard.getPasswordHash());
        assertThat(jdbc.queryForObject("select password_self_set from users where id = ?", Boolean.class, inB.getId()))
                .isTrue();
    }

    // --- rule 3: an admin reset is a link the person redeems --------------------

    @Test
    void anAdminResetEmailsALinkAndChangesNothingUntilThePersonUsesIt() throws Exception {
        UUID orgA = newTenant("OLPP-A");
        UUID orgB = newTenant("OLPP-B");
        String email = email();
        User inA = member(orgA, UserRole.RENTER, email, OLD, true);
        User inB = member(orgB, UserRole.RENTER, email, OLD, true);
        jdbc.update("update users set welcomed_at = now() where id in (?, ?)", inA.getId(), inB.getId());
        User adminA = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-a-password", false);

        ResponseEntity<String> res = as(adminA, HttpMethod.POST,
                "/api/admin/users/" + inA.getId() + "/send-password-reset", null);

        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(204);
        assertThat(res.getBody()).as("the link is never shown to the admin").isNull();
        assertThat(outbox("PASSWORD_RESET_REQUESTED", inA.getId())).isEqualTo(1);
        assertThat(hashOf(inA.getId())).isEqualTo(inA.getPasswordHash());
        assertThat(hashOf(inB.getId())).isEqualTo(inB.getPasswordHash());

        String token = userRepo.findById(inA.getId()).orElseThrow().getInviteToken();
        assertThat(token).isNotNull();
        ResponseEntity<String> set = client().post().uri("/api/auth/set-password")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("token", token, "newPassword", NEW))
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
        assertThat(set.getStatusCode().value()).isEqualTo(204);
        assertThat(matches(inA.getId(), NEW)).isTrue();
        assertThat(matches(inB.getId(), NEW)).isTrue();
    }

    @Test
    void anAdminOfOneOrganisationCannotResetOrEditAMembershipInAnother() throws Exception {
        UUID orgA = newTenant("OLPP-A");
        UUID orgB = newTenant("OLPP-B");
        String email = email();
        User inB = member(orgB, UserRole.RENTER, email, OLD, true);
        User adminA = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-a-password", false);

        assertThat(as(adminA, HttpMethod.POST, "/api/admin/users/" + inB.getId() + "/send-password-reset", null)
                .getStatusCode().value()).isIn(403, 404);
        assertThat(as(adminA, HttpMethod.PUT, "/api/admin/users/" + inB.getId(), Map.of("password", "x-typed-pw-9"))
                .getStatusCode().value()).isIn(403, 404);
        assertThat(outbox("PASSWORD_RESET_REQUESTED", inB.getId())).isZero();
        assertThat(hashOf(inB.getId())).isEqualTo(inB.getPasswordHash());
    }

    @Test
    void changingALoginsEmailRetiresItsLinkAndItsClaimToBeThePersonsOwnPassword() throws Exception {
        UUID orgA = newTenant("OLPP-A");
        UUID orgB = newTenant("OLPP-B");
        String victim = email();
        User inB = member(orgB, UserRole.RENTER, victim, OLD, true);
        User adminA = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-a-password", false);
        // Admin A invites an address they read, then repoints the row at the victim.
        JsonNode created = createRenter(adminA, email());
        UUID inA = portalUserOf(created);
        String heldByAdmin = userRepo.findById(inA).orElseThrow().getInviteToken();

        ResponseEntity<String> edit = as(adminA, HttpMethod.PUT, "/api/admin/users/" + inA, Map.of("email", victim));
        assertThat(edit.getStatusCode().value()).as(edit.getBody()).isEqualTo(200);

        ResponseEntity<String> set = client().post().uri("/api/auth/set-password")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("token", heldByAdmin, "newPassword", NEW))
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
        assertThat(set.getStatusCode().value()).as("the old link died with the old address").isEqualTo(404);
        assertThat(hashOf(inB.getId())).isEqualTo(inB.getPasswordHash());
        assertThat(jdbc.queryForObject("select password_self_set from users where id = ?", Boolean.class, inA))
                .isFalse();
    }

    // --- isolation ----------------------------------------------------------------

    @Test
    void aSharedLoginRevealsNothingOfTheOtherOrganisationToEitherAdmin() throws Exception {
        UUID orgA = newTenant("OLPP-A");
        UUID orgB = newTenant("OLPP-B");
        String email = email();
        User inB = member(orgB, UserRole.RENTER, email, OLD, true);
        User adminA = member(orgA, UserRole.TENANT_ADMIN, email(), "admin-a-password", false);
        JsonNode created = createRenter(adminA, email);
        UUID inA = portalUserOf(created);

        String usersA = as(adminA, HttpMethod.GET, "/api/admin/users", null).getBody();
        assertThat(usersA).contains(inA.toString()).doesNotContain(inB.getId().toString());
        assertThat(created.toString()).doesNotContain(orgB.toString()).doesNotContain(inB.getId().toString());

        // The new row belongs to A alone.
        assertThat(userService.getUserTenantIds(inA)).containsExactly(orgA);
        assertThat(userService.getUserTenantIds(inB.getId())).containsExactly(orgB);
    }
}
