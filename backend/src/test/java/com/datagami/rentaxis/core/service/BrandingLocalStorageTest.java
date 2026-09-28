package com.datagami.rentaxis.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branding without Azure (local-disk deployments, R2): never in the public
 * {@code assets/} folder the serve route hands out unauthenticated, but under
 * {@code private/branding/<orgId>/} (which that route never serves) and read only
 * for its own organisation. A new organisation's upload is staged privately and
 * moved into the organisation's folder when it is created.
 */
class BrandingLocalStorageTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    @TempDir Path root;
    private final BlobStorageService blobs = new BlobStorageService();
    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        Field f = BlobStorageService.class.getDeclaredField("localAssetsPath");
        f.setAccessible(true);
        f.set(blobs, root.toString());
    }

    @Test
    void storedPrivatelyAndReadOnlyForItsOwnOrganisation() {
        String url = blobs.uploadBranding(orgA, PNG, "image/png");
        assertThat(url).startsWith("/api/v1/assets/serve/private/branding/" + orgA + "/").endsWith(".png");
        assertThat(Files.exists(root.resolve(url.substring("/api/v1/assets/serve/".length())))).isTrue();
        assertThat(Files.exists(root.resolve("assets"))).isFalse(); // nothing in the public folder
        assertThat(blobs.readLocalAsset(orgA, url, OrgBrandImages.MAX_BYTES)).isPresent();
        assertThat(blobs.readLocalAsset(orgB, url, OrgBrandImages.MAX_BYTES)).isEmpty();
        assertThat(blobs.readLocalAsset(null, url, OrgBrandImages.MAX_BYTES)).isEmpty();
        assertThat(OrgBrandImages.load(blobs, orgA, url)).isPresent();
        assertThat(OrgBrandImages.load(blobs, orgB, url)).isEmpty();
    }

    @Test
    void aNewOrganisationsUploadIsStagedThenMovedIntoItsFolder() throws Exception {
        String staged = blobs.uploadBranding(null, PNG, "image/png");
        assertThat(staged).startsWith("/api/v1/assets/serve/private/branding/staging/");
        assertThat(blobs.isStagedBranding(staged)).isTrue();
        assertThat(blobs.readLocalAsset(orgA, staged, OrgBrandImages.MAX_BYTES)).isEmpty();

        String moved = blobs.adoptStagedBranding(orgA, staged);

        assertThat(moved).startsWith("/api/v1/assets/serve/private/branding/" + orgA + "/");
        assertThat(Files.exists(root.resolve(staged.substring("/api/v1/assets/serve/".length())))).isFalse();
        assertThat(OrgBrandImages.load(blobs, orgA, moved)).get().satisfies(i -> assertThat(i.bytes()).isEqualTo(PNG));
        // Anything that is not staged is kept as it is.
        assertThat(blobs.adoptStagedBranding(orgA, moved)).isEqualTo(moved);
        assertThat(blobs.adoptStagedBranding(orgA, "https://elsewhere.example/x.png")).isEqualTo("https://elsewhere.example/x.png");
    }

    @Test
    void traversalAndOtherFoldersAreRefused() {
        blobs.uploadBranding(orgA, PNG, "image/png");
        for (String url : new String[]{
                "/api/v1/assets/serve/private/branding/" + orgA + "/../" + orgB + "/x.png",
                "/api/v1/assets/serve/private/branding/staging/x.png",
                "/api/v1/assets/serve/private/other/x.png",
                "/api/v1/assets/serve/private/branding/" + orgA + "/x.png?y",
                "/api/v1/assets/serve/%2e%2e/etc/passwd"}) {
            assertThat(blobs.readLocalAsset(orgA, url, OrgBrandImages.MAX_BYTES)).as(url).isEmpty();
        }
    }
}
