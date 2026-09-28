package com.datagami.rentaxis.api;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.core.service.OrgBrandImages;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.TenantArtifactCleanupService;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Organisation branding against real (Azurite) blob storage, where tenant
 * containers are PRIVATE — as they are in prod.
 *
 * <ul>
 *   <li>A SUPER_ADMIN acting in org A who uploads org B's logo/stamp puts them in
 *       B's own container (review I1), B's contract can read them, A cannot, and
 *       purging A leaves them alone.</li>
 *   <li>The browser cannot load the stored blob URL (private), so the header's
 *       logo is streamed by the app (review I3): the caller's own organisation
 *       only, stamp for admins only, private cache with an ETag.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrgBrandingStorageIT extends AbstractPostgresIT {

    private static final String KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    @SuppressWarnings("resource")
    static final GenericContainer<?> AZURITE = new GenericContainer<>(
            DockerImageName.parse("mcr.microsoft.com/azure-storage/azurite:latest"))
            .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--skipApiVersionCheck", "--loose")
            .withExposedPorts(10000);

    static String connectionString() {
        if (!AZURITE.isRunning()) AZURITE.start();
        return "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=" + KEY
                + ";BlobEndpoint=http://" + AZURITE.getHost() + ":" + AZURITE.getMappedPort(10000) + "/devstoreaccount1;";
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("azure.storage.connection-string", OrgBrandingStorageIT::connectionString);
        registry.add("AZURE_STORAGE_CONNECTION_STRING", OrgBrandingStorageIT::connectionString);
    }

    @LocalServerPort int port;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired PropertyService propertyService;
    @Autowired JdbcTemplate jdbc;
    @Autowired BlobStorageService blobs;
    @Autowired TenantArtifactCleanupService cleanup;

    private CrossTenantHttp http;
    private UUID orgA;
    private UUID orgB;
    private User saInA;

    @BeforeEach
    void setUp() {
        http = new CrossTenantHttp(port, orgRepo, userRepo, accountService, propertyAccountService, propertyService);
        orgA = http.tenant("Brand-store A ");
        orgB = http.tenant("Brand-store B ");
        // A super admin whose active organisation is A.
        saInA = http.user(orgA, UserRole.SUPER_ADMIN);
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private static byte[] png(int rgb) throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, rgb);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResponseEntity<Map> upload(User caller, String path, byte[] bytes, String filename) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        });
        return http.request(caller, HttpMethod.POST, path).contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(Map.class);
    }

    private ResponseEntity<byte[]> get(User caller, String path, String ifNoneMatch) {
        RestClient.RequestBodySpec spec = http.request(caller, HttpMethod.GET, path);
        if (ifNoneMatch != null) spec = spec.header("If-None-Match", ifNoneMatch);
        return spec.retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(byte[].class);
    }

    private BlobContainerClient container(String name) {
        BlobServiceClient svc = new BlobServiceClientBuilder().connectionString(connectionString()).buildClient();
        return svc.getBlobContainerClient(name);
    }

    @Test
    void aSuperAdminActingInAnotherOrgUploadsIntoTheTargetOrgsPrivateContainer() throws Exception {
        byte[] logo = png(0xEEC046);
        byte[] stamp = png(0x1F3A93);

        var up = upload(saInA, "/api/admin/tenants/" + orgB + "/branding", logo, "logo.png");
        assertThat(up.getStatusCode().value()).isEqualTo(200);
        String logoUrl = (String) up.getBody().get("url");
        String stampUrl = (String) upload(saInA, "/api/admin/tenants/" + orgB + "/branding", stamp, "stamp.png")
                .getBody().get("url");

        // In B's container, not A's (the active organisation) and not shared.
        assertThat(logoUrl).contains("/tenant-" + orgB + "/branding/").doesNotContain(orgA.toString());
        assertThat(stampUrl).contains("/tenant-" + orgB + "/branding/");
        assertThat(container("tenant-" + orgB).getBlobClient(logoUrl.substring(logoUrl.indexOf("/branding/") + 1))
                .exists()).isTrue();
        // The container is private: the browser could never load the stored URL.
        assertThat(container("tenant-" + orgB).getAccessPolicy().getBlobAccessType()).isNull();
        HttpResponse<byte[]> anonymous = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(logoUrl)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(anonymous.statusCode()).isNotEqualTo(200);

        assertThat(http.call(saInA, HttpMethod.PUT, "/api/admin/tenants/" + orgB,
                Map.of("logoUrl", logoUrl, "stampImageUrl", stampUrl)).getStatusCode().value()).isEqualTo(200);

        // B's documents can read them; A (another tenant's container) cannot.
        assertThat(OrgBrandImages.load(blobs, orgB, stampUrl)).get()
                .satisfies(i -> assertThat(i.bytes()).isEqualTo(stamp));
        assertThat(OrgBrandImages.load(blobs, orgA, stampUrl)).isEmpty();

        // Purging A captures nothing of B's, and B's files survive it.
        jdbc.update("delete from tenant_artifact_cleanup_queue where deleted_tenant_id = ?", orgA);
        cleanup.captureAndEnqueue(orgA);
        List<String> planned = jdbc.queryForList(
                "select container_name || '/' || object_path from tenant_artifact_cleanup_queue where deleted_tenant_id = ?",
                String.class, orgA);
        assertThat(planned).noneMatch(p -> p.contains(orgB.toString()));
        cleanup.processPending(orgA);
        assertThat(OrgBrandImages.load(blobs, orgB, logoUrl)).isPresent();
    }

    @Test
    void theHeaderLogoIsStreamedByTheAppForTheCallersOwnOrganisationOnly() throws Exception {
        byte[] logo = png(0xEEC046);
        byte[] stamp = png(0x1F3A93);
        String logoUrl = (String) upload(saInA, "/api/admin/tenants/" + orgB + "/branding", logo, "l.png").getBody().get("url");
        String stampUrl = (String) upload(saInA, "/api/admin/tenants/" + orgB + "/branding", stamp, "s.png").getBody().get("url");
        http.call(saInA, HttpMethod.PUT, "/api/admin/tenants/" + orgB, Map.of("logoUrl", logoUrl, "stampImageUrl", stampUrl));

        User adminB = http.user(orgB, UserRole.TENANT_ADMIN);
        User managerB = http.user(orgB, UserRole.PROPERTY_MANAGER);
        User adminA = http.user(orgA, UserRole.TENANT_ADMIN);

        ResponseEntity<byte[]> res = get(adminB, "/api/v1/org/branding/logo", null);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo(logo);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(res.getHeaders().getCacheControl()).contains("private");
        assertThat(res.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        String etag = res.getHeaders().getETag();
        assertThat(etag).isNotBlank();
        assertThat(get(adminB, "/api/v1/org/branding/logo", etag).getStatusCode().value()).isEqualTo(304);

        // The stamp: the organisation's admins only.
        assertThat(get(adminB, "/api/v1/org/branding/stamp", null).getBody()).isEqualTo(stamp);
        assertThat(get(managerB, "/api/v1/org/branding/stamp", null).getStatusCode().value()).isEqualTo(404);
        assertThat(get(managerB, "/api/v1/org/branding/logo", null).getStatusCode().value()).isEqualTo(200);

        // A's admin gets A's (none), never B's — even naming B.
        assertThat(get(adminA, "/api/v1/org/branding/logo", null).getStatusCode().value()).isEqualTo(404);
        assertThat(get(adminA, "/api/v1/org/branding/logo?org=" + orgB, null).getStatusCode().value()).isEqualTo(404);
        // The address names the organisation (R2): another org's id is refused even for
        // its own member when the session is elsewhere, and the right one is served.
        assertThat(get(adminB, "/api/v1/org/branding/logo?org=" + orgA, null).getStatusCode().value()).isEqualTo(404);
        assertThat(get(adminB, "/api/v1/org/branding/logo?org=" + orgB, null).getBody()).isEqualTo(logo);

        assertThat(get(adminA, "/api/admin/tenants/" + orgB + "/branding/logo", null).getStatusCode().value()).isEqualTo(403);

        // The super admin previews any organisation's image by id.
        assertThat(get(saInA, "/api/admin/tenants/" + orgB + "/branding/stamp", null).getBody()).isEqualTo(stamp);
        // A super admin acting in B sees B's logo in the header.
        assertThat(get(http.user(orgB, UserRole.SUPER_ADMIN), "/api/v1/org/branding/logo", null).getBody()).isEqualTo(logo);

        // The ETag is checked before storage is read: with the blob gone, a
        // revalidation still answers 304 without touching storage; a fresh load 404s.
        container("tenant-" + orgB).getBlobClient(logoUrl.substring(logoUrl.indexOf("/branding/") + 1)).delete();
        assertThat(get(adminB, "/api/v1/org/branding/logo", etag).getStatusCode().value()).isEqualTo(304);
        assertThat(get(adminB, "/api/v1/org/branding/logo", null).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void onlyARealRasterImageIsAcceptedAndANewOrgsIsStagedPrivatelyThenAdopted() throws Exception {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>".getBytes(StandardCharsets.UTF_8);
        assertThat(upload(saInA, "/api/admin/tenants/" + orgB + "/branding", svg, "x.png").getStatusCode().value())
                .isEqualTo(400);
        assertThat(upload(saInA, "/api/admin/tenants/" + UUID.randomUUID() + "/branding", png(1), "x.png")
                .getStatusCode().value()).isEqualTo(404);
        User adminB = http.user(orgB, UserRole.TENANT_ADMIN);
        assertThat(upload(adminB, "/api/admin/tenants/" + orgB + "/branding", png(1), "x.png").getStatusCode().value())
                .isEqualTo(403);

        // A new organisation's upload is staged PRIVATELY (R2), unreadable until the
        // organisation exists; creating it moves the file into its own container.
        byte[] stamp = png(2);
        String staged = (String) upload(saInA, "/api/admin/tenants/branding", stamp, "new.png").getBody().get("url");
        assertThat(staged).contains("/" + BlobStorageService.BRANDING_STAGING_CONTAINER + "/branding/");
        assertThat(container(BlobStorageService.BRANDING_STAGING_CONTAINER).getAccessPolicy().getBlobAccessType()).isNull();
        assertThat(HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(staged)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isNotEqualTo(200);
        assertThat(OrgBrandImages.load(blobs, orgB, staged)).isEmpty();

        var created = http.call(saInA, HttpMethod.POST, "/api/admin/tenants",
                Map.of("name", "Brand-store new " + UUID.randomUUID(), "stampImageUrl", staged));
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        UUID newOrg = UUID.fromString((String) created.getBody().get("id"));
        String adopted = (String) created.getBody().get("stampImageUrl");
        assertThat(adopted).contains("/tenant-" + newOrg + "/branding/").isNotEqualTo(staged);
        assertThat(OrgBrandImages.load(blobs, newOrg, adopted)).get()
                .satisfies(i -> assertThat(i.bytes()).isEqualTo(stamp));
        assertThat(container(BlobStorageService.BRANDING_STAGING_CONTAINER)
                .getBlobClient(staged.substring(staged.indexOf("/branding/") + 1)).exists()).isFalse();
        // ... and is purged with it.
        cleanup.captureAndEnqueue(newOrg);
        assertThat(jdbc.queryForList("select object_path from tenant_artifact_cleanup_queue where deleted_tenant_id = ?",
                String.class, newOrg)).anyMatch(p -> adopted.endsWith(p));
        // Leave no pending purge work behind for other tests' recovery runs.
        jdbc.update("delete from tenant_artifact_cleanup_queue where deleted_tenant_id = ?", newOrg);
    }
}
