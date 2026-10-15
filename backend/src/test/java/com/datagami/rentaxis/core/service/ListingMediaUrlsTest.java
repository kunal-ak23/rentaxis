package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.UnitListingMedia;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * PR #401 R1 P2-1: installed renter apps pass a photo URL straight to
 * Image.network, so with a public base configured the DTO URL is absolute —
 * built from configuration, never from a request header.
 */
class ListingMediaUrlsTest {

    private static UnitListingMedia stored() {
        UnitListingMedia m = new UnitListingMedia();
        m.setId(UUID.randomUUID());
        m.setListingId(UUID.randomUUID());
        m.setUrl("https://acct.blob.core.windows.net/tenant-x/listings/a.jpg");
        m.setBlobPath("listings/" + m.getListingId() + "/a.jpg");
        return m;
    }

    @Test
    void withABaseTheRoutesAreAbsolute() {
        ListingMediaUrls urls = new ListingMediaUrls(mock(BlobStorageService.class), "https://app.rentaxis.com/");
        UnitListingMedia m = stored();
        assertThat(urls.publicUrl(m)).isEqualTo("https://app.rentaxis.com/api/v1/public/listing-media/" + m.getId());
        assertThat(urls.adminUrl(m))
                .isEqualTo("https://app.rentaxis.com/api/listings/" + m.getListingId() + "/media/" + m.getId() + "/file");
    }

    @Test
    void withoutABaseTheyStayRelativeAndExternalLinksPassThrough() {
        ListingMediaUrls urls = new ListingMediaUrls(mock(BlobStorageService.class), "");
        UnitListingMedia m = stored();
        assertThat(urls.publicUrl(m)).isEqualTo("/api/v1/public/listing-media/" + m.getId());
        UnitListingMedia ext = stored();
        ext.setBlobPath(null);
        ext.setUrl("https://cdn.example.com/x.jpg");
        assertThat(new ListingMediaUrls(mock(BlobStorageService.class), "https://app.rentaxis.com").publicUrl(ext))
                .isEqualTo("https://cdn.example.com/x.jpg");
    }

    @Test
    void aBaseThatIsNotAnHttpOriginIsIgnored() {
        assertThat(ListingMediaUrls.normaliseBase("javascript:alert(1)")).isEmpty();
        assertThat(ListingMediaUrls.normaliseBase("  ")).isEmpty();
        assertThat(ListingMediaUrls.normaliseBase("http://localhost:3000//")).isEqualTo("http://localhost:3000");
    }

    @Test
    void promotionImagesAreAbsoluteInDtosAndStoredRelative() {
        PromotionImageService images = new PromotionImageService(mock(BlobStorageService.class), null, null,
                "https://app.rentaxis.com");
        String route = "/api/v1/public/promo-images/0b0f2c1e-1111-4222-8333-944455556666.png";
        assertThat(images.forDto(route)).isEqualTo("https://app.rentaxis.com" + route);
        assertThat(images.forDto("https://cdn.example.com/a.png")).isEqualTo("https://cdn.example.com/a.png");
        assertThat(images.normaliseForStorage("https://app.rentaxis.com" + route)).isEqualTo(route);
        assertThat(images.normaliseForStorage("https://cdn.example.com/a.png")).isEqualTo("https://cdn.example.com/a.png");
    }
}
