package com.datagami.rentaxis.api;

import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.TestIdentities;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Settings › Organisation › "Check the payee name on scanned cheques": the switch
 * and the valid payee names are the calling organisation's own, and only a
 * Company Admin (or a system admin with the organisation selected) edits them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PayeeCheckSettingsIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired UserRepository userRepo;
    @Autowired LandlordOrgRepository orgRepo;
    private final ObjectMapper json = new ObjectMapper();

    private ResponseEntity<String> call(HttpMethod method, UUID userId, UserRole role, UUID tenantId, Object body) {
        RestClient.RequestBodySpec req = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .method(method).uri("/api/v1/settings/org/payee-check")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", userId.toString())
                .header("X-User-Role", role.name())
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Tenant-Id", tenantId.toString());
        if (body != null) req = req.body(body);
        return req.retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
    }

    @Test
    void aCompanyAdminSavesTheirOwnOrganisationsListAndAnotherOrganisationNeverSeesIt() throws Exception {
        UUID orgA = TestIdentities.org(orgRepo);
        UUID orgB = TestIdentities.org(orgRepo);
        UUID adminA = TestIdentities.user(userRepo, UserRole.TENANT_ADMIN, orgA);
        UUID adminB = TestIdentities.user(userRepo, UserRole.TENANT_ADMIN, orgB);

        ResponseEntity<String> put = call(HttpMethod.PUT, adminA, UserRole.TENANT_ADMIN, orgA,
                Map.of("enabled", true, "validNames", List.of(" Palm Ridge Properties LLC ", "", "بالم ريدج")));
        assertThat(put.getStatusCode().value()).as(put.getBody()).isEqualTo(200);

        JsonNode a = json.readTree(call(HttpMethod.GET, adminA, UserRole.TENANT_ADMIN, orgA, null).getBody());
        assertThat(a.get("enabled").asBoolean()).isTrue();
        assertThat(a.get("validNames").toString()).isEqualTo("[\"Palm Ridge Properties LLC\",\"بالم ريدج\"]");

        JsonNode b = json.readTree(call(HttpMethod.GET, adminB, UserRole.TENANT_ADMIN, orgB, null).getBody());
        assertThat(b.get("enabled").asBoolean()).isFalse();
        assertThat(b.get("validNames")).isEmpty();
    }

    @Test
    void aPropertyManagerCannotChangeIt() {
        UUID org = TestIdentities.org(orgRepo);
        UUID pm = TestIdentities.user(userRepo, UserRole.PROPERTY_MANAGER, org);

        assertThat(call(HttpMethod.PUT, pm, UserRole.PROPERTY_MANAGER, org,
                Map.of("enabled", true, "validNames", List.of("X"))).getStatusCode().value()).isEqualTo(403);
    }
}
