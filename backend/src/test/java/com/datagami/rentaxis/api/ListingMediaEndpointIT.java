package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.UnitListingMedia;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.ListingMediaType;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitListingMediaRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bug 26/27: listing photos sit in the organisation's private container, so the
 * API names them by backend routes instead of their (403) blob URLs.
 *
 * <ul>
 *   <li>{@code GET /api/v1/public/listing-media/{id}} — anonymous, only while the
 *       listing is live on the marketplace; a draft, an unlisted listing, an
 *       organisation with listings off, an external link: 404, container never read.</li>
 *   <li>{@code GET /api/listings/{id}/media/{mediaId}/file} — the organisation's
 *       staff (a property manager for their buildings), any status.</li>
 *   <li>The admin, marketplace and public DTOs carry those routes, never the blob URL.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ListingMediaEndpointIT extends AbstractPostgresIT {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
    private static final byte[] SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
            .getBytes(StandardCharsets.UTF_8);

    @LocalServerPort int port;

    @MockitoBean BlobStorageService blob;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;
    @Autowired UnitListingRepository listingRepo;
    @Autowired UnitListingMediaRepository mediaRepo;
    @Autowired TenantFeatureService features;
    @Autowired ObjectMapper json;

    private UUID tenantA;
    private UUID tenantB;
    private String slugA;
    private Property marina;
    private UnitListing published;
    private UnitListing draft;
    private UnitListingMedia publishedPhoto;
    private UnitListingMedia legacyPhoto;
    private UnitListingMedia draftPhoto;
    private UnitListingMedia externalPhoto;

    @BeforeEach
    void setUp() {
        tenantB = fixtures().bootstrap().tenantId();
        features.setEnabled(tenantB, TenantFeature.LISTINGS, true);

        LeaseTestFixtures a = fixtures().bootstrap();
        tenantA = a.tenantId();
        marina = a.property();
        LandlordOrg org = orgRepo.findById(tenantA).orElseThrow();
        slugA = "media-it-" + UUID.randomUUID().toString().substring(0, 8);
        org.setSlug(slugA);
        orgRepo.save(org);
        features.setEnabled(tenantA, TenantFeature.LISTINGS, true);

        published = listing(a.createUnit(marina, "LM-1"), ListingStatus.PUBLISHED, "sea-view");
        draft = listing(a.createUnit(marina, "LM-2"), ListingStatus.DRAFT, "draft-flat");

        publishedPhoto = media(published, "listings/" + published.getId() + "/" + UUID.randomUUID() + ".jpg", true);
        // A row written before blob_path was recorded: only the (owned) URL names the file.
        legacyPhoto = media(published, null, false);
        draftPhoto = media(draft, "listings/" + draft.getId() + "/" + UUID.randomUUID() + ".png", true);
        externalPhoto = media(published, null, false);
        externalPhoto.setUrl("https://images.example.com/flat.jpg");
        mediaRepo.save(externalPhoto);

        String legacyPath = "listings/" + published.getId() + "/" + UUID.randomUUID() + ".jpg";
        legacyPhoto.setUrl("https://acct.blob.core.windows.net/tenant-" + tenantA + "/" + legacyPath);
        mediaRepo.save(legacyPhoto);
        when(blob.parseOwnedBlobUrl(any())).thenReturn(java.util.Optional.empty());
        when(blob.parseOwnedBlobUrl(legacyPhoto.getUrl())).thenReturn(java.util.Optional.of(
                new BlobStorageService.BlobLocation("tenant-" + tenantA, legacyPath)));

        when(blob.download(eq(tenantA), any())).thenReturn(new BlobStorageService.DownloadResult(PNG, null));
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ------------------------------------------------------------ anonymous

    @Test
    void anyoneLoadsALiveListingsPhotoAsACacheableImage() {
        ResponseEntity<byte[]> res = anon("/api/v1/public/listing-media/" + publishedPhoto.getId());
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo(PNG);
        assertThat(res.getHeaders().getContentType()).hasToString("image/png");
        assertThat(res.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(res.getHeaders().getCacheControl()).contains("public");
        verify(blob).download(tenantA, publishedPhoto.getBlobPath());

        // A row from before blob_path: the path comes from its own account's URL.
        assertThat(anon("/api/v1/public/listing-media/" + legacyPhoto.getId()).getStatusCode().value()).isEqualTo(200);

        // An unchanged image is a 304 on revalidation.
        String etag = res.getHeaders().getETag();
        ResponseEntity<byte[]> again = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri("/api/v1/public/listing-media/" + publishedPhoto.getId())
                .header("If-None-Match", etag)
                .retrieve().onStatus(s -> true, (req, r) -> { }).toEntity(byte[].class);
        assertThat(again.getStatusCode().value()).isEqualTo(304);
    }

    @Test
    void anUpcomingListingsPhotoIsPublicToo() {
        published.setStatus(ListingStatus.UPCOMING);
        save(published);
        assertThat(anon("/api/v1/public/listing-media/" + publishedPhoto.getId()).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void aDraftUnlistedOrSwitchedOffListingsPhotoIsNotFoundAndNeverRead() {
        assertThat(anon("/api/v1/public/listing-media/" + draftPhoto.getId()).getStatusCode().value()).isEqualTo(404);

        published.setStatus(ListingStatus.UNLISTED);
        save(published);
        assertThat(anon("/api/v1/public/listing-media/" + publishedPhoto.getId()).getStatusCode().value()).isEqualTo(404);

        published.setStatus(ListingStatus.PUBLISHED);
        save(published);
        features.setEnabled(tenantA, TenantFeature.LISTINGS, false);
        assertThat(anon("/api/v1/public/listing-media/" + publishedPhoto.getId()).getStatusCode().value()).isEqualTo(404);

        assertThat(anon("/api/v1/public/listing-media/" + UUID.randomUUID()).getStatusCode().value()).isEqualTo(404);
        verify(blob, never()).download(any(), any());
    }

    @Test
    void anExternalLinkIsNotProxied() {
        assertThat(anon("/api/v1/public/listing-media/" + externalPhoto.getId()).getStatusCode().value()).isEqualTo(404);
        verify(blob, never()).download(any(), any());
    }

    @Test
    void bytesThatAreNotAnAllowedImageAreNeverServed() {
        when(blob.download(eq(tenantA), any())).thenReturn(new BlobStorageService.DownloadResult(SVG, "image/svg+xml"));
        assertThat(anon("/api/v1/public/listing-media/" + publishedPhoto.getId()).getStatusCode().value()).isEqualTo(404);
        User admin = user(tenantA, UserRole.TENANT_ADMIN);
        assertThat(staff(admin, published.getId(), publishedPhoto.getId()).getStatusCode().value()).isEqualTo(404);

        byte[] pdf = "%PDF-1.4 floor plan".getBytes(StandardCharsets.UTF_8);
        when(blob.download(eq(tenantA), any())).thenReturn(new BlobStorageService.DownloadResult(pdf, null));
        ResponseEntity<byte[]> plan = anon("/api/v1/public/listing-media/" + publishedPhoto.getId());
        assertThat(plan.getHeaders().getContentType()).hasToString("application/pdf");
    }

    // ---------------------------------------------------------------- staff

    @Test
    void staffSeeEveryStatusPrivately() {
        User admin = user(tenantA, UserRole.TENANT_ADMIN);
        ResponseEntity<byte[]> res = staff(admin, draft.getId(), draftPhoto.getId());
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getContentType()).hasToString("image/png");
        assertThat(res.getHeaders().getCacheControl()).contains("private");
        assertThat(res.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void anotherOrganisationAWrongListingAndAnUnassignedManagerAreNotFound() {
        clearInvocations(blob);
        assertThat(staff(user(tenantB, UserRole.TENANT_ADMIN), draft.getId(), draftPhoto.getId())
                .getStatusCode().value()).isEqualTo(404);
        // Another listing's photo under this listing's id.
        assertThat(staff(user(tenantA, UserRole.TENANT_ADMIN), draft.getId(), publishedPhoto.getId())
                .getStatusCode().value()).isEqualTo(404);
        User pm = user(tenantA, UserRole.PROPERTY_MANAGER);
        assertThat(staff(pm, draft.getId(), draftPhoto.getId()).getStatusCode().value()).isEqualTo(404);
        verify(blob, never()).download(any(), any());

        UserPropertyAssignment link = new UserPropertyAssignment();
        link.setUserId(pm.getId());
        link.setPropertyId(marina.getId());
        assignmentRepo.save(link);
        assertThat(staff(pm, draft.getId(), draftPhoto.getId()).getStatusCode().value()).isEqualTo(200);

        // A renter has no staff route.
        assertThat(staff(user(tenantA, UserRole.RENTER), draft.getId(), draftPhoto.getId())
                .getStatusCode().value()).isIn(403, 404);
    }

    // ----------------------------------------------------------------- DTOs

    @Test
    void theDtosNameTheRoutesNeverTheBlobUrl() throws Exception {
        User admin = user(tenantA, UserRole.TENANT_ADMIN);
        JsonNode detail = json.readTree(call(admin, "/api/listings/" + draft.getId()).getBody());
        assertThat(detail.at("/media/0/url").asText())
                .isEqualTo("/api/listings/" + draft.getId() + "/media/" + draftPhoto.getId() + "/file");

        JsonNode list = json.readTree(call(admin, "/api/listings?size=50").getBody());
        String cover = null;
        for (JsonNode row : list.get("content")) {
            if (row.get("id").asText().equals(published.getId().toString())) cover = row.get("coverPhotoUrl").asText();
        }
        assertThat(cover).isEqualTo("/api/listings/" + published.getId() + "/media/" + publishedPhoto.getId() + "/file");

        JsonNode pub = json.readTree(anonText("/public/l/" + slugA + "/" + published.getSlug()));
        assertThat(pub.get("coverPhotoUrl").asText()).isEqualTo("/api/v1/public/listing-media/" + publishedPhoto.getId());
        String body = pub.toString();
        assertThat(body).doesNotContain("blob.core.windows.net");
        // An external link is passed through as it is.
        assertThat(body).contains("https://images.example.com/flat.jpg");
        assertThat(body).contains("/api/v1/public/listing-media/" + legacyPhoto.getId());
    }

    // -------------------------------------------------------------- helpers

    private UnitListing listing(Unit unit, ListingStatus status, String slug) {
        TenantContextHolder.setTenantId(tenantA);
        UnitListing l = new UnitListing();
        l.setTenantId(tenantA);
        l.setUnitId(unit.getId());
        l.setStatus(status);
        l.setSlug(slug + "-" + UUID.randomUUID().toString().substring(0, 6));
        l.setTitleEn("Listing " + slug);
        return listingRepo.save(l);
    }

    private void save(UnitListing l) {
        TenantContextHolder.setTenantId(tenantA);
        listingRepo.save(l);
        TenantContextHolder.clear();
    }

    private UnitListingMedia media(UnitListing l, String blobPath, boolean cover) {
        UnitListingMedia m = new UnitListingMedia();
        m.setListingId(l.getId());
        m.setMediaType(ListingMediaType.PHOTO);
        m.setUrl("https://acct.blob.core.windows.net/tenant-" + tenantA + "/" + (blobPath == null ? "x" : blobPath));
        m.setBlobPath(blobPath);
        m.setIsCover(cover);
        m.setSortOrder(cover ? 0 : 1);
        return mediaRepo.save(m);
    }

    private LeaseTestFixtures fixtures() {
        return new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService);
    }

    private User user(UUID tenantId, UserRole role) {
        User u = new User();
        u.setEmail("lm-" + role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        return userRepo.save(u);
    }

    private ResponseEntity<byte[]> anon(String path) {
        return RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri(path)
                .retrieve().onStatus(s -> true, (req, res) -> { })
                .toEntity(byte[].class);
    }

    private String anonText(String path) {
        return RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri(path).retrieve().body(String.class);
    }

    private ResponseEntity<byte[]> staff(User caller, UUID listingId, UUID mediaId) {
        return call(caller, "/api/listings/" + listingId + "/media/" + mediaId + "/file");
    }

    private ResponseEntity<byte[]> call(User caller, String path) {
        return RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString())
                .retrieve().onStatus(s -> true, (req, res) -> { })
                .toEntity(byte[].class);
    }
}
