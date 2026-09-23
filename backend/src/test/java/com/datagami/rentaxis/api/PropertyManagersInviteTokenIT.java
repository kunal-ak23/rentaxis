package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
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
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #342 review C2: {@code GET /properties} and {@code GET /properties/{id}/managers}
 * returned the {@code User} entity, invite token included. Any PM or accountant
 * could read a colleague's pending token, post it to {@code /auth/set-password}
 * and take the account over. Both now return {@code ManagerSummaryDTO}, and the
 * managers endpoint is scoped to properties the caller may see.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PropertyManagersInviteTokenIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired PropertyService propertyService;

    private CrossTenantHttp http;
    private UUID propertyId;
    private UUID otherPropertyInTenant;
    private UUID foreignPropertyId;
    private User callerPm;
    private User accountant;
    private User victim;
    private String victimToken;

    @BeforeEach
    void setUp() {
        http = new CrossTenantHttp(port, orgRepo, userRepo, accountService, propertyAccountService, propertyService);

        http.tenant("Mgr-foreign-");
        foreignPropertyId = http.property("Foreign Tower").getId();

        UUID tenant = http.tenant("Mgr-");
        propertyId = http.property("Marina Heights").getId();
        otherPropertyInTenant = http.property("Unassigned Tower").getId();

        victimToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        victim = user(tenant, UserRole.PROPERTY_MANAGER, victimToken);
        callerPm = user(tenant, UserRole.PROPERTY_MANAGER, null);
        accountant = user(tenant, UserRole.ACCOUNTANT, null);
        assign(victim, propertyId);
        assign(callerPm, propertyId);
        assign(victim, otherPropertyInTenant);
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private User user(UUID tenantId, UserRole role, String inviteToken) {
        User u = new User();
        u.setEmail("mgr-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("$2a$10$hashThatMustNeverLeakFromTheApi");
        u.setTenantId(tenantId);
        u.setInviteToken(inviteToken);
        u.setInviteTokenExpiresAt(inviteToken == null ? null : Instant.now().plusSeconds(86400));
        return userRepo.save(u);
    }

    private void assign(User user, UUID property) {
        UserPropertyAssignment a = new UserPropertyAssignment();
        a.setUserId(user.getId());
        a.setPropertyId(property);
        assignmentRepo.save(a);
    }

    private ResponseEntity<String> get(User caller, String path) {
        return http.request(caller, HttpMethod.GET, path)
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
    }

    private void assertNoSecrets(ResponseEntity<String> res) {
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody())
                .doesNotContain(victimToken)
                .doesNotContain("inviteToken")
                .doesNotContain("passwordHash")
                .doesNotContain("hashThatMustNeverLeak");
    }

    @Test
    void theManagersEndpointNamesTheManagersButCarriesNoSecret() {
        ResponseEntity<String> res = get(callerPm, "/api/v1/properties/" + propertyId + "/managers");

        assertNoSecrets(res);
        assertThat(res.getBody()).contains(victim.getEmail()).contains(victim.getId().toString());
    }

    @Test
    void thePropertyListNamesTheManagersButCarriesNoSecret() {
        ResponseEntity<String> asPm = get(callerPm, "/api/v1/properties");
        ResponseEntity<String> asAccountant = get(accountant, "/api/v1/properties");

        assertNoSecrets(asPm);
        assertNoSecrets(asAccountant);
        assertThat(asPm.getBody()).contains(victim.getEmail());
        assertThat(asAccountant.getBody()).contains(victim.getEmail());
    }

    @Test
    void aPropertyManagerCannotListTheManagersOfABuildingTheyAreNotAssignedTo() {
        assertThat(get(callerPm, "/api/v1/properties/" + otherPropertyInTenant + "/managers")
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void anotherTenantsPropertyIsNotFound() {
        assertThat(get(callerPm, "/api/v1/properties/" + foreignPropertyId + "/managers")
                .getStatusCode().value()).isEqualTo(404);
    }
}
