package com.datagami.rentaxis.api;

import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus.APPROVED;
import static com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;

/** Review round 1 fixes to the promoter role, one test (or more) per finding. */
class PromoterReviewFixesIT extends AbstractPromoterIT {

    private static final String CHANGED =
            "This ad was changed after you opened it. Reload to review the latest version.";

    private UUID org;
    private User admin;
    private Property p1;
    private PromoBusiness wash;
    private User promoter;

    @BeforeEach
    void setUp() {
        org = org();
        admin = user(org, UserRole.TENANT_ADMIN);
        p1 = property(org, "Marina");
        wash = business(org, "QuickWash");
        promoter = promoter(org, true, List.of(wash.getId()), List.of(p1.getId()));
    }

    // ------------------------------------------------------------------- I2

    @Test
    void i2_aDecisionNamesTheSubmissionItIsAbout() {
        String adId = post(promoter, "/api/v1/promoter/ads", ad("First")).text("id");

        assertThat(post(admin, "/api/v1/promotions/ads/" + adId + "/approve", null).status()).isEqualTo(400);
        assertThat(post(admin, "/api/v1/promotions/ads/" + adId + "/approve", Map.of()).status()).isEqualTo(400);
        assertThat(post(admin, "/api/v1/promotions/ads/" + adId + "/reject", Map.of("reason", "no")).status())
                .isEqualTo(400);

        Resp stale = post(admin, "/api/v1/promotions/ads/" + adId + "/approve",
                body("expectedSubmittedAt", "2020-01-01T00:00:00Z"));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.text("code")).isEqualTo("promo.changed");
        assertThat(stale.text("message")).isEqualTo(CHANGED);
        assertThat(reload(UUID.fromString(adId)).getApprovalStatus()).isEqualTo(PENDING);

        assertThat(approve(admin, adId).status()).isEqualTo(204);
        assertThat(reload(UUID.fromString(adId)).getApprovalStatus()).isEqualTo(APPROVED);
    }

    @Test
    void i2_anEditAfterTheAdminOpenedTheAdIsNotApprovedUnseen() {
        Resp created = post(promoter, "/api/v1/promoter/ads", ad("What the admin saw"));
        String adId = created.text("id");
        String seen = get(admin, "/api/v1/promotions/ads?approvalStatus=PENDING").body().get("content")
                .findValuesAsText("submittedAt").get(0);

        put(promoter, "/api/v1/promoter/ads/" + adId, ad("What the promoter changed it to"));

        Resp approve = post(admin, "/api/v1/promotions/ads/" + adId + "/approve", body("expectedSubmittedAt", seen));
        assertThat(approve.status()).isEqualTo(409);
        assertThat(approve.text("code")).isEqualTo("promo.changed");
        Resp reject = post(admin, "/api/v1/promotions/ads/" + adId + "/reject",
                body("reason", "Blurry", "expectedSubmittedAt", seen));
        assertThat(reject.status()).isEqualTo(409);
        assertThat(reload(UUID.fromString(adId)).getApprovalStatus()).isEqualTo(PENDING);
        assertThat(reload(UUID.fromString(adId)).getTitleEn()).isEqualTo("What the promoter changed it to");
    }

    @Test
    void i2_aBusinessEditResubmitsItsPendingAdsToo() {
        User other = promoter(org, false, List.of(wash.getId()), List.of(p1.getId()));
        PromoAd pending = ad(org, wash.getId(), PENDING, other.getId(), p1.getId());
        jdbc.update("UPDATE promo_ads SET submitted_at = now() - interval '1 hour' WHERE id = ?", pending.getId());
        String seen = submittedAt(pending.getId());

        assertThat(put(promoter, "/api/v1/promoter/businesses/" + wash.getId(),
                body("nameEn", "QuickWash renamed " + UUID.randomUUID())).status()).isEqualTo(200);

        PromoAd now = reload(pending.getId());
        assertThat(now.getApprovalStatus()).isEqualTo(PENDING);
        assertThat(now.getPromoterUserId()).isEqualTo(promoter.getId());
        assertThat(now.getSubmittedAt().toString()).isNotEqualTo(seen);
        Resp approve = post(admin, "/api/v1/promotions/ads/" + pending.getId() + "/approve",
                body("expectedSubmittedAt", seen));
        assertThat(approve.status()).isEqualTo(409);
    }

    // -------------------------------------------------------------- helpers

    private Map<String, Object> ad(String title) {
        return body("businessId", wash.getId().toString(), "titleEn", title, "ctaType", "NONE",
                "propertyIds", List.of(p1.getId().toString()), "redirectUrl", null, "redirectParams", List.of());
    }
}
