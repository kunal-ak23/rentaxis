package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus.APPROVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Review round 1, I1: a promoter's artwork and logo are this organisation's own
 * uploads or nothing. An https link to a host the promoter runs would let it swap
 * the picture after approval and see every renter who loads it. Admins keep their
 * https links.
 *
 * <p>Same configuration as {@code PromotionImageEndpointIT} (a mocked blob store,
 * no token secret), so the two share one application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PromoterImageRulesIT extends AbstractPromoterIT {

    private static final String EXTERNAL = "https://cdn.promoter.example/a.png";

    @MockitoBean BlobStorageService blob;

    private UUID org;
    private Property p1;
    private PromoBusiness wash;
    private User promoter;
    private User admin;

    @BeforeEach
    void setUp() {
        org = org();
        p1 = property(org, "Marina");
        wash = business(org, "QuickWash");
        promoter = promoter(org, true, List.of(wash.getId()), List.of(p1.getId()));
        admin = user(org, UserRole.TENANT_ADMIN);
        // This organisation's container holds every promotions file; any other holds none.
        when(blob.exists(eq(org), anyString())).thenReturn(true);
    }

    @Test
    void aPromoterCannotPointArtworkOrALogoAtAnotherHost() {
        Resp ad = post(promoter, "/api/v1/promoter/ads", ad("backgroundImageUrl", EXTERNAL));
        assertThat(ad.status()).isEqualTo(400);
        assertThat(ad.text("message")).contains("uploaded");

        PromoAd existing = ad(org, wash.getId(), APPROVED, promoter.getId(), p1.getId());
        assertThat(put(promoter, "/api/v1/promoter/ads/" + existing.getId(), ad("backgroundImageUrl", EXTERNAL))
                .status()).isEqualTo(400);

        assertThat(post(promoter, "/api/v1/promoter/businesses",
                body("nameEn", "Sparkle " + UUID.randomUUID(), "logoUrl", EXTERNAL)).status()).isEqualTo(400);
        assertThat(put(promoter, "/api/v1/promoter/businesses/" + wash.getId(),
                body("nameEn", wash.getNameEn(), "logoUrl", EXTERNAL)).status()).isEqualTo(400);
        assertThat(reload(existing.getId()).getBackgroundImageUrl()).isNull();
    }

    @Test
    void aPromoterCannotUseAnotherOrganisationsUpload() {
        UUID other = org();
        String file = UUID.randomUUID() + ".png";
        String foreignRoute = "/api/v1/public/promo-images/" + file;
        when(blob.exists(eq(other), anyString())).thenReturn(true);
        when(blob.exists(org, "promotions/" + file)).thenReturn(false);

        assertThat(post(promoter, "/api/v1/promoter/ads", ad("backgroundImageUrl", foreignRoute)).status())
                .isEqualTo(400);
    }

    @Test
    void ownUploadsAndNoImageAreFine() {
        String own = "/api/v1/public/promo-images/" + UUID.randomUUID() + ".png";

        Resp ad = post(promoter, "/api/v1/promoter/ads", ad("backgroundImageUrl", own));
        assertThat(ad.status()).isEqualTo(201);
        assertThat(ad.text("backgroundImageUrl")).endsWith(own);
        assertThat(post(promoter, "/api/v1/promoter/ads", ad("backgroundImageUrl", null)).status()).isEqualTo(201);
        assertThat(post(promoter, "/api/v1/promoter/businesses",
                body("nameEn", "Sparkle " + UUID.randomUUID(), "logoUrl", own)).status()).isEqualTo(201);
    }

    @Test
    void anAdminKeepsHttpsLinks() {
        assertThat(post(admin, "/api/v1/promotions/ads", body("businessId", wash.getId().toString(),
                "titleEn", "Admin art", "ctaType", "NONE", "backgroundImageUrl", EXTERNAL)).status()).isEqualTo(201);
        assertThat(post(admin, "/api/v1/promotions/businesses",
                body("nameEn", "Admin " + UUID.randomUUID(), "logoUrl", EXTERNAL)).status()).isEqualTo(201);
    }

    /** M6: a promoter's logo is public only once one of the business's ads is approved and live. */
    @Test
    void m6_aPromotersLogoIsPublicOnlyOnceAnAdIsApproved() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        when(blob.download(eq(org), anyString())).thenReturn(new BlobStorageService.DownloadResult(png, null));
        String own = "/api/v1/public/promo-images/" + UUID.randomUUID() + ".png";
        String bizId = post(promoter, "/api/v1/promoter/businesses",
                body("nameEn", "Sparkle " + UUID.randomUUID(), "logoUrl", own)).text("id");

        assertThat(anonStatus(own)).isEqualTo(404);
        PromoAd ad = ad(org, UUID.fromString(bizId), APPROVED, promoter.getId(), p1.getId());
        assertThat(anonStatus(own)).isEqualTo(200);
        jdbc.update("UPDATE promo_ads SET archived_at = now() WHERE id = ?", ad.getId());
        assertThat(anonStatus(own)).isEqualTo(404);

        // An admin's business (no promoter grants) keeps its logo public as before.
        String adminLogo = "/api/v1/public/promo-images/" + UUID.randomUUID() + ".png";
        assertThat(post(admin, "/api/v1/promotions/businesses",
                body("nameEn", "Admin " + UUID.randomUUID(), "logoUrl", adminLogo)).status()).isEqualTo(201);
        assertThat(anonStatus(adminLogo)).isEqualTo(200);
    }

    private int anonStatus(String path) {
        return client().get().uri(path).exchange((req, res) -> res.getStatusCode().value());
    }

    private Map<String, Object> ad(String field, Object value) {
        Map<String, Object> m = body("businessId", wash.getId().toString(), "titleEn", "Wash day", "ctaType", "NONE",
                "propertyIds", List.of(p1.getId().toString()), "redirectUrl", null, "redirectParams", List.of());
        m.put(field, value);
        return m;
    }
}
