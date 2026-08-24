package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.UserService;
import com.datagami.rentaxis.core.service.auth.FirebaseIdTokenVerifier;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthControllerRegistrationTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort int port;
    @Autowired LandlordOrgRepository orgRepository;
    @MockitoBean UserService userService;
    @MockitoBean FirebaseIdTokenVerifier firebaseIdTokenVerifier;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void invalidPublicRegistrationIsRejectedBeforeCreatingATenant() {
        long before = orgRepository.count();

        try {
            client().post().uri("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "fullName", "",
                            "companyName", "",
                            "email", "not-an-email",
                            "password", "short"))
                    .retrieve().body(Map.class);
            throw new AssertionError("expected 400");
        } catch (HttpStatusCodeException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getResponseBodyAsString()).contains("Validation failed");
        }

        assertThat(orgRepository.count()).isEqualTo(before);
    }

    @Test
    void failedAdminCreationRollsBackTheFreshTenant() {
        long before = orgRepository.count();
        when(userService.createUser(
                anyString(), anyString(), anyString(), any(), anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("simulated user persistence failure"));

        try {
            client().post().uri("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "fullName", "Aisha Admin",
                            "companyName", "Rollback Towers",
                            "email", "rollback@example.com",
                            "password", "correct-horse"))
                    .retrieve().body(Map.class);
            throw new AssertionError("expected 500");
        } catch (HttpStatusCodeException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(e.getResponseBodyAsString()).doesNotContain("simulated user persistence failure");
        }

        assertThat(orgRepository.count()).isEqualTo(before);
    }

    @Test
    void multibytePasswordBeyondBcryptLimitIsRejectedBeforeCreatingATenant() {
        long before = orgRepository.count();

        try {
            client().post().uri("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "fullName", "Aisha Admin",
                            "companyName", "Unicode Towers",
                            "email", "unicode@example.com",
                            // 19 four-byte code points: under the 72-character
                            // Bean Validation limit but over bcrypt's 72 bytes.
                            "password", "💥".repeat(19)))
                    .retrieve().body(Map.class);
            throw new AssertionError("expected 400");
        } catch (HttpStatusCodeException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getResponseBodyAsString()).contains("at most 72 UTF-8 bytes");
        }

        assertThat(orgRepository.count()).isEqualTo(before);
    }
}
