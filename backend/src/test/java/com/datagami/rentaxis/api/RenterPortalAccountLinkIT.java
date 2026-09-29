package com.datagami.rentaxis.api;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Owner ruling (2026-09-29): creating a Tenant always gives them portal access
 * when an email is given, and an email that already belongs to a user of the
 * organisation no longer aborts the create.
 *
 * <ul>
 *   <li>new email: a RENTER user is created and invited;</li>
 *   <li>the email is an unlinked RENTER user of the same organisation: that user
 *       is linked, no second invite;</li>
 *   <li>the email is any other user of the organisation (staff, or a RENTER
 *       already linked to another Tenant): the Tenant is saved without portal
 *       access and the response says why;</li>
 *   <li>the same email in another organisation is invisible: treated as new here,
 *       never linked.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RenterPortalAccountLinkIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired UserRepository userRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    private LandlordOrg org(String label) {
        LandlordOrg org = new LandlordOrg();
        org.setName("PortalLink-" + label + "-" + UUID.randomUUID());
        return orgRepo.save(org);
    }

    private User user(LandlordOrg org, UserRole role, String email) {
        User u = new User();
        u.setEmail(email);
        u.setName("user");
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash(passwordEncoder.encode("pwd"));
        u.setTenantId(org.getId());
        return userRepo.save(u);
    }

    private static String email() {
        return "portal-" + UUID.randomUUID() + "@test.invalid";
    }

    private JsonNode create(User admin, String email) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("nameEn", "Portal Link Tenant");
        if (email != null) body.put("email", email);
        ResponseEntity<String> res = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/v1/renters")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", admin.getId().toString())
                .header("X-User-Role", admin.getRole().name())
                .header("X-Tenant-Id", admin.getTenantId().toString())
                .header("X-User-Tenant-Id", admin.getTenantId().toString())
                .body(body)
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(201);
        return json.readTree(res.getBody());
    }

    private UUID renterUserId(JsonNode created) {
        return jdbc.query("select user_id from renters where id = ?::uuid",
                rs -> rs.next() ? (UUID) rs.getObject(1) : null, created.get("id").asText());
    }

    /**
     * USER_INVITED mail queued for these users. The request runs on the server's
     * thread, so ApplicationEvents cannot see it; the dispatcher writes the
     * outbox row synchronously after commit (invites bypass the tenant email
     * gate), before the response returns.
     */
    private Integer invitesQueuedFor(UUID... userIds) {
        int n = 0;
        for (UUID id : userIds) {
            n += jdbc.queryForObject("select count(*) from email_outbox where event_type = 'USER_INVITED' "
                    + "and dedup_key like ?", Integer.class, "USER_INVITED:" + id + "%");
        }
        return n;
    }

    private Integer usersWithEmail(UUID tenantId, String email) {
        return jdbc.queryForObject("select count(*) from users where tenant_id = ? and lower(email) = lower(?)",
                Integer.class, tenantId, email);
    }

    @Test
    void aNewEmailGetsAPortalUserAndAnInvite() throws Exception {
        LandlordOrg org = org("new");
        User admin = user(org, UserRole.TENANT_ADMIN, email());
        String email = email();

        JsonNode created = create(admin, email);

        assertThat(created.get("portalAccount").asText()).isEqualTo("INVITED");
        UUID linked = renterUserId(created);
        assertThat(linked).isNotNull();
        User portal = userRepo.findById(linked).orElseThrow();
        assertThat(portal.getRole()).isEqualTo(UserRole.RENTER);
        assertThat(portal.getTenantId()).isEqualTo(org.getId());
        assertThat(invitesQueuedFor(linked)).isEqualTo(1);
        assertThat(portal.getInviteToken()).isNotNull();
    }

    @Test
    void anUnlinkedRenterUserOfTheSameOrganisationIsLinkedWithoutANewInvite() throws Exception {
        LandlordOrg org = org("link");
        User admin = user(org, UserRole.TENANT_ADMIN, email());
        String email = email();
        User existing = user(org, UserRole.RENTER, email);

        // Typed with different case and padding: still the same person.
        JsonNode created = create(admin, email.toUpperCase());

        assertThat(created.get("portalAccount").asText()).isEqualTo("LINKED_EXISTING");
        assertThat(renterUserId(created)).isEqualTo(existing.getId());
        assertThat(invitesQueuedFor(existing.getId())).isZero();
        assertThat(userRepo.findById(existing.getId()).orElseThrow().getInviteToken()).isNull();
        assertThat(usersWithEmail(org.getId(), email)).isEqualTo(1);
    }

    @Test
    void aStaffUserWithTheEmailIsNotLinkedAndTheTenantIsSavedWithoutPortalAccess() throws Exception {
        LandlordOrg org = org("staff");
        User admin = user(org, UserRole.TENANT_ADMIN, email());
        String email = email();
        User pm = user(org, UserRole.PROPERTY_MANAGER, email);

        JsonNode created = create(admin, email);

        assertThat(created.get("portalAccount").asText()).isEqualTo("SKIPPED_EMAIL_IN_USE");
        assertThat(renterUserId(created)).isNull();
        assertThat(created.get("userId").isNull()).isTrue();
        assertThat(invitesQueuedFor(pm.getId())).isZero();
        User after = userRepo.findById(pm.getId()).orElseThrow();
        assertThat(after.getRole()).isEqualTo(UserRole.PROPERTY_MANAGER);
        assertThat(usersWithEmail(org.getId(), email)).isEqualTo(1);
    }

    @Test
    void aRenterUserAlreadyLinkedToAnotherTenantIsSkipped() throws Exception {
        LandlordOrg org = org("taken");
        User admin = user(org, UserRole.TENANT_ADMIN, email());
        String email = email();

        JsonNode first = create(admin, email);
        assertThat(first.get("portalAccount").asText()).isEqualTo("INVITED");
        UUID firstUser = renterUserId(first);

        JsonNode second = create(admin, email);

        assertThat(second.get("portalAccount").asText()).isEqualTo("SKIPPED_EMAIL_IN_USE");
        assertThat(renterUserId(second)).isNull();
        assertThat(renterUserId(first)).isEqualTo(firstUser);
        assertThat(invitesQueuedFor(firstUser)).isEqualTo(1);
        assertThat(usersWithEmail(org.getId(), email)).isEqualTo(1);
    }

    @Test
    void theSameEmailInAnotherOrganisationIsTreatedAsNewAndNeverLinked() throws Exception {
        LandlordOrg orgA = org("a");
        LandlordOrg orgB = org("b");
        String email = email();
        User foreign = user(orgB, UserRole.RENTER, email);
        User adminA = user(orgA, UserRole.TENANT_ADMIN, email());

        JsonNode created = create(adminA, email);

        assertThat(created.get("portalAccount").asText()).isEqualTo("INVITED");
        UUID linked = renterUserId(created);
        assertThat(linked).isNotNull().isNotEqualTo(foreign.getId());
        assertThat(userRepo.findById(linked).orElseThrow().getTenantId()).isEqualTo(orgA.getId());
        assertThat(created.toString()).doesNotContain(foreign.getId().toString());
        assertThat(invitesQueuedFor(linked)).isEqualTo(1);
        assertThat(invitesQueuedFor(foreign.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from renters where user_id = ?", Integer.class,
                foreign.getId())).isZero();
    }

    @Test
    void noEmailMeansNoPortalAccount() throws Exception {
        LandlordOrg org = org("none");
        User admin = user(org, UserRole.TENANT_ADMIN, email());

        JsonNode created = create(admin, null);

        assertThat(created.get("portalAccount").asText()).isEqualTo("NO_EMAIL");
        assertThat(renterUserId(created)).isNull();
    }
}
