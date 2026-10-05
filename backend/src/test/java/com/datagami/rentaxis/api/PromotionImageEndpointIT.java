package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ux6 item 2: promotion artwork and business logos are uploaded (they were bare
 * URL boxes) into the organisation's private container and named by a backend
 * route. The renter app loads them anonymously, so the anonymous route serves
 * an image only while a live business or ad shows it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PromotionImageEndpointIT extends AbstractPostgresIT {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private static final byte[] SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);

    @LocalServerPort int port;
    @MockitoBean BlobStorageService blob;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired ObjectMapper json;

    private UUID tenantA;
    private UUID tenantB;
    private User adminA;
    private User adminB;

    @BeforeEach
    void setUp() {
        tenantA = org();
        tenantB = org();
        adminA = user(tenantA, UserRole.TENANT_ADMIN);
        adminB = user(tenantB, UserRole.TENANT_ADMIN);
        when(blob.download(eq(tenantA), anyString())).thenReturn(new BlobStorageService.DownloadResult(PNG, null));
        // Tenant B's container holds no such image; what it would answer is not an image.
        when(blob.download(eq(tenantB), anyString())).thenReturn(new BlobStorageService.DownloadResult(SVG, null));
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void anUploadIsStoredPrivatelyAndPreviewedByItsOwnAdminsOnly() throws Exception {
        ResponseEntity<byte[]> up = upload(adminA, PNG, "logo.png");
        assertThat(up.getStatusCode().value()).isEqualTo(201);
        String url = json.readTree(up.getBody()).get("url").asText();
        assertThat(url).matches("/api/v1/public/promo-images/[0-9a-f-]{36}\\.png");
        String file = url.substring(url.lastIndexOf('/') + 1);
        verify(blob).uploadPromotionImage(eq(tenantA), eq(file), any(), eq("image/png"));

        ResponseEntity<byte[]> preview = call(adminA, HttpMethod.GET, "/api/v1/promotions/images/" + file, null);
        assertThat(preview.getStatusCode().value()).isEqualTo(200);
        assertThat(preview.getHeaders().getContentType()).hasToString("image/png");
        assertThat(preview.getHeaders().getCacheControl()).contains("private");
        assertThat(preview.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");

        // Another organisation's admin reads their own container, never A's.
        assertThat(call(adminB, HttpMethod.GET, "/api/v1/promotions/images/" + file, null)
                .getStatusCode().value()).isEqualTo(404);
        // Not yet on anything live: not public.
        assertThat(anon(url).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void markupDisguisedAsAnImageIsRefused() {
        ResponseEntity<byte[]> up = upload(adminA, SVG, "logo.png");
        assertThat(up.getStatusCode().is4xxClientError()).isTrue();
        verify(blob, never()).uploadPromotionImage(any(), any(), any(), any());
    }

    @Test
    void anActiveBusinessLogoIsPublicUntilTheBusinessIsPaused() throws Exception {
        String url = json.readTree(upload(adminA, PNG, "logo.jpg").getBody()).get("url").asText();
        JsonNode business = json.readTree(call(adminA, HttpMethod.POST, "/api/v1/promotions/businesses",
                Map.of("nameEn", "Spice Bazaar", "logoUrl", url)).getBody());
        assertThat(business.get("logoUrl").asText()).isEqualTo(url);

        ResponseEntity<byte[]> pub = anon(url);
        assertThat(pub.getStatusCode().value()).isEqualTo(200);
        assertThat(pub.getHeaders().getCacheControl()).contains("public");
        assertThat(pub.getBody()).isEqualTo(PNG);

        call(adminA, HttpMethod.PUT, "/api/v1/promotions/businesses/" + business.get("id").asText(),
                Map.of("nameEn", "Spice Bazaar", "logoUrl", url, "active", false));
        assertThat(anon(url).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aLiveAdsArtworkIsPublicAndAPausedAdsIsNot() throws Exception {
        String url = json.readTree(upload(adminA, PNG, "art.png").getBody()).get("url").asText();
        JsonNode business = json.readTree(call(adminA, HttpMethod.POST, "/api/v1/promotions/businesses",
                Map.of("nameEn", "Spice Bazaar")).getBody());
        JsonNode ad = json.readTree(call(adminA, HttpMethod.POST, "/api/v1/promotions/ads", Map.of(
                "businessId", business.get("id").asText(), "titleEn", "Brunch",
                "backgroundImageUrl", url, "ctaType", "NONE")).getBody());
        assertThat(ad.get("backgroundImageUrl").asText()).isEqualTo(url);
        assertThat(anon(url).getStatusCode().value()).isEqualTo(200);

        call(adminA, HttpMethod.PUT, "/api/v1/promotions/ads/" + ad.get("id").asText(), Map.of(
                "businessId", business.get("id").asText(), "titleEn", "Brunch",
                "backgroundImageUrl", url, "ctaType", "NONE", "active", false));
        assertThat(anon(url).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aMalformedRouteOrAPlainHttpLinkIsRefusedAndAnHttpsLinkStillAccepted() {
        assertThat(call(adminA, HttpMethod.POST, "/api/v1/promotions/businesses",
                Map.of("nameEn", "X", "logoUrl", "/api/v1/public/promo-images/../../etc/passwd"))
                .getStatusCode().is4xxClientError()).isTrue();
        assertThat(call(adminA, HttpMethod.POST, "/api/v1/promotions/businesses",
                Map.of("nameEn", "X", "logoUrl", "http://cdn.example.com/a.png"))
                .getStatusCode().is4xxClientError()).isTrue();
        assertThat(call(adminA, HttpMethod.POST, "/api/v1/promotions/businesses",
                Map.of("nameEn", "X", "logoUrl", "https://cdn.example.com/a.png"))
                .getStatusCode().value()).isIn(200, 201);
        assertThat(anon("/api/v1/public/promo-images/not-a-file.svg").getStatusCode().value()).isEqualTo(404);
    }

    // -------------------------------------------------------------- helpers

    private UUID org() {
        LandlordOrg o = new LandlordOrg();
        o.setName("Promo-IT-" + UUID.randomUUID());
        return orgRepo.save(o).getId();
    }

    private User user(UUID tenantId, UserRole role) {
        User u = new User();
        u.setEmail("promo-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        return userRepo.save(u);
    }

    private ResponseEntity<byte[]> upload(User caller, byte[] bytes, String name) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return name;
            }
        });
        return headers(caller, RestClient.builder().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/v1/promotions/images").contentType(MediaType.MULTIPART_FORM_DATA).body(form))
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(byte[].class);
    }

    private ResponseEntity<byte[]> call(User caller, HttpMethod method, String path, Object body) {
        RestClient.RequestBodySpec spec = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .method(method).uri(path);
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return headers(caller, spec).retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(byte[].class);
    }

    private <S extends RestClient.RequestHeadersSpec<?>> S headers(User caller, S spec) {
        spec.header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString());
        return spec;
    }

    private ResponseEntity<byte[]> anon(String path) {
        return RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri(path).retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(byte[].class);
    }
}
