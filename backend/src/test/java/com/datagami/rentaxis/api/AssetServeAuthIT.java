package com.datagami.rentaxis.api;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #300: {@code /api/v1/assets/serve/**} was {@code permitAll()} wholesale.
 *
 * <p>Anything the local-disk storage fallback held was therefore readable by
 * anyone holding the storage key — settlement deduction scans, lease documents,
 * maintenance ticket photos — with no authentication, no role and no tenant check
 * anywhere in the path. Keys carry UUIDs, so this is not enumerable; URLs leak all
 * the same, through logs, referrers, browser history and shared screenshots.</p>
 *
 * <p>The default is now inverted: the public folder is open, everything else under
 * the route needs a caller. Both halves are asserted here, because a fix that only
 * shut the door would have taken every logo on every login page with it.</p>
 *
 * <p>Files are written straight to the configured storage path rather than through
 * the features that own them: what is under test is the route, and routing three
 * different upload endpoints through this class would test them instead.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AssetServeAuthIT extends AbstractPostgresIT {

    static Path storageRoot;

    @DynamicPropertySource
    static void storagePath(DynamicPropertyRegistry registry) {
        try {
            storageRoot = Files.createTempDirectory("rentaxis-assets-it");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        registry.add("rentaxis.assets.storage-path", () -> storageRoot.toString());
    }

    @LocalServerPort int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;

    private User staff;

    private static final String PUBLIC_LOGO = "assets/logo.png";
    private static final String TICKET_PHOTO = "ticket-attachments/broken-tap.png";
    private static final String LEASE_DOCUMENT = "lease-docs/tenancy-contract.pdf";
    private static final String DEDUCTION_SCAN = "settlement-deductions/"
            + "11111111-1111-1111-1111-111111111111/damage.png";
    private static final String VOUCHER_SCAN = "private/vouchers/"
            + "22222222-2222-2222-2222-222222222222/invoice.pdf";

    @BeforeEach
    void setUp() {
        write(PUBLIC_LOGO, "a logo anybody may see");
        write(TICKET_PHOTO, "a renter's kitchen");
        write(LEASE_DOCUMENT, "a signed tenancy contract");
        write(DEDUCTION_SCAN, "a damage photograph");
        write(VOUCHER_SCAN, "a supplier invoice");

        LandlordOrg org = new LandlordOrg();
        org.setName("AssetServe-" + UUID.randomUUID());
        UUID tenantId = orgRepo.save(org).getId();

        User user = new User();
        user.setEmail("staff+" + UUID.randomUUID() + "@test");
        user.setName("Asset Staff");
        user.setRole(UserRole.TENANT_ADMIN);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("x");
        user.setTenantId(tenantId);
        staff = userRepo.save(user);
    }

    @Test
    void anAnonymousCallerCannotFetchAPrivateAttachment() {
        for (String key : new String[] {TICKET_PHOTO, LEASE_DOCUMENT, DEDUCTION_SCAN}) {
            assertThat(anonymousGet(key).getStatusCode())
                    .as("unauthenticated GET of %s", key)
                    .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void aPublicAssetIsStillServedWithoutAuthentication() {
        ResponseEntity<String> served = anonymousGet(PUBLIC_LOGO);

        assertThat(served.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(served.getBody()).isEqualTo("a logo anybody may see");
    }

    /** Shutting the door on anonymous callers must not shut it on the staff using the app. */
    @Test
    void aLoggedInCallerStillGetsThePrivateAttachment() {
        ResponseEntity<String> served = authenticatedGet(TICKET_PHOTO);

        assertThat(served.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(served.getBody()).isEqualTo("a renter's kitchen");
    }

    /**
     * The {@code private/} prefix is refused through this route whoever asks — it
     * has a tenant-checked streaming endpoint of its own, and this route has no
     * tenant check at all. 404 rather than 403: a refusal that distinguishes the
     * two would confirm the file exists.
     */
    @Test
    void thePrivatePrefixIsRefusedEvenWhenLoggedIn() {
        assertThat(authenticatedGet(VOUCHER_SCAN).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(anonymousGet(VOUCHER_SCAN).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }

    /**
     * The upload endpoint is the one writer whose folder a caller chooses, so it may
     * write only where this route serves without authentication — otherwise it
     * hands back a URL that no logged-in browser and no anonymous one can read.
     */
    @Test
    void theUploadEndpointRefusesAFolderOutsideThePublicOne() {
        org.springframework.http.client.MultipartBodyBuilder body =
                new org.springframework.http.client.MultipartBodyBuilder();
        body.part("file", new org.springframework.core.io.ByteArrayResource("x".getBytes()) {
            @Override public String getFilename() { return "photo.png"; }
        }).contentType(org.springframework.http.MediaType.IMAGE_PNG);
        org.springframework.util.MultiValueMap<String, org.springframework.http.HttpEntity<?>> parts =
                body.build();

        ResponseEntity<String> refused = client().post()
                .uri(URI.create(base() + "/api/v1/assets/upload?folder=lease-docs"))
                .header("X-User-Id", staff.getId().toString())
                .header("X-User-Role", staff.getRole().name())
                .header("X-Tenant-Id", staff.getTenantId().toString())
                .header("X-User-Tenant-Id", staff.getTenantId().toString())
                .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("folder");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void write(String key, String content) {
        try {
            Path file = storageRoot.resolve(key);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private RestClient client() {
        return RestClient.builder().baseUrl(base()).build();
    }

    /** A browser with no session at all — the attacker holding a leaked key. */
    private ResponseEntity<String> anonymousGet(String key) {
        return client().get().uri(URI.create(base() + "/api/v1/assets/serve/" + key))
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    /** The same request from a signed-in user, as the web proxy sends it. */
    private ResponseEntity<String> authenticatedGet(String key) {
        return client().get().uri(URI.create(base() + "/api/v1/assets/serve/" + key))
                .header("X-User-Id", staff.getId().toString())
                .header("X-User-Role", staff.getRole().name())
                .header("X-Tenant-Id", staff.getTenantId().toString())
                .header("X-User-Tenant-Id", staff.getTenantId().toString())
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }
}
