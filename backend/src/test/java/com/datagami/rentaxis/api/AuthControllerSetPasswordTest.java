package com.datagami.rentaxis.api;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthControllerSetPasswordTest extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired UserRepository userRepo;
    @Autowired LandlordOrgRepository orgRepo;

    @Test
    void validateAndAcceptHappyPath() {
        LandlordOrg org = new LandlordOrg();
        org.setName("ApiTest-" + UUID.randomUUID());
        org = orgRepo.save(org);

        User u = new User();
        u.setEmail("invitee+" + UUID.randomUUID() + "@test");
        u.setName("Invitee");
        u.setRole(UserRole.RENTER);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("placeholder");
        u.setTenantId(org.getId());
        u.setInviteToken("test-token-happy");
        u.setInviteTokenExpiresAt(Instant.now().plusSeconds(3600));
        userRepo.save(u);

        RestClient client = RestClient.create("http://localhost:" + port);

        // GET validate
        Map<?,?> info = client.get().uri("/api/auth/set-password/validate?token=test-token-happy")
                .retrieve().body(Map.class);
        assertThat(info.get("email")).isEqualTo(u.getEmail());

        // POST accept
        client.post().uri("/api/auth/set-password")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("token", "test-token-happy", "newPassword", "Strong#Pass1"))
                .retrieve().toBodilessEntity();

        User after = userRepo.findById(u.getId()).orElseThrow();
        assertThat(after.getInviteToken()).isNull();
        assertThat(after.getInviteTokenExpiresAt()).isNull();
        assertThat(after.getPasswordHash()).isNotEqualTo("placeholder");
    }

    @Test
    void validateExpiredReturns410() {
        LandlordOrg org = new LandlordOrg();
        org.setName("ApiTest-" + UUID.randomUUID());
        org = orgRepo.save(org);
        User u = new User();
        u.setEmail("expired+" + UUID.randomUUID() + "@test");
        u.setName("Expired");
        u.setRole(UserRole.RENTER);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("ph");
        u.setTenantId(org.getId());
        u.setInviteToken("test-token-expired");
        u.setInviteTokenExpiresAt(Instant.now().minusSeconds(60));
        userRepo.save(u);

        RestClient client = RestClient.create("http://localhost:" + port);
        HttpStatusCodeException ex = null;
        try {
            client.get().uri("/api/auth/set-password/validate?token=test-token-expired")
                    .retrieve().toBodilessEntity();
        } catch (HttpStatusCodeException e) { ex = e; }
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(410);
    }

    @Test
    void validateUnknownReturns404() {
        RestClient client = RestClient.create("http://localhost:" + port);
        HttpStatusCodeException ex = null;
        try {
            client.get().uri("/api/auth/set-password/validate?token=does-not-exist")
                    .retrieve().toBodilessEntity();
        } catch (HttpStatusCodeException e) { ex = e; }
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(404);
    }
}
