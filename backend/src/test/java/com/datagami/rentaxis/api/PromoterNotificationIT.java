package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Promoter plan, "Notifications": every active admin of the organisation hears of
 * a promoter's submission; the promoter hears of the decision, with the reason on
 * a rejection. In-app rows, and outbox email when the organisation has email on.
 */
class PromoterNotificationIT extends AbstractPromoterIT {

    @Autowired TenantFeatureService features;

    private UUID org;
    private User admin;
    private User otherAdmin;
    private User inactiveAdmin;
    private User manager;
    private User adminElsewhere;
    private User promoter;
    private Property p1;
    private PromoBusiness wash;

    @BeforeEach
    void setUp() {
        org = org();
        admin = user(org, UserRole.TENANT_ADMIN);
        otherAdmin = user(org, UserRole.TENANT_ADMIN);
        inactiveAdmin = user(org, UserRole.TENANT_ADMIN);
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", UserStatus.INACTIVE.name(), inactiveAdmin.getId());
        manager = user(org, UserRole.PROPERTY_MANAGER);
        adminElsewhere = user(org(), UserRole.TENANT_ADMIN);
        p1 = property(org, "Marina");
        wash = business(org, "QuickWash");
        promoter = promoter(org, false, List.of(wash.getId()), List.of(p1.getId()));
    }

    @Test
    void everyActiveAdminHearsOfASubmission() {
        String adId = post(promoter, "/api/v1/promoter/ads", ad("Wash day")).text("id");

        for (User u : List.of(admin, otherAdmin)) {
            List<Map<String, Object>> rows = notifications(u, adId);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("type")).isEqualTo("PROMO_AD_SUBMITTED");
            assertThat(rows.get(0).get("message_key")).isEqualTo("PROMO_AD_SUBMITTED");
            assertThat((String) rows.get(0).get("message")).contains(promoter.getName()).contains("Wash day");
        }
        for (User u : List.of(inactiveAdmin, manager, adminElsewhere, promoter)) {
            assertThat(notifications(u, adId)).isEmpty();
        }
        // What the admin's bell reads.
        assertThat(get(admin, "/api/v1/notifications?page=0&size=5").body().findValuesAsText("referenceType"))
                .contains("PROMO_AD");
    }

    @Test
    void thePromoterHearsTheDecisionWithTheReason() {
        String approvedId = post(promoter, "/api/v1/promoter/ads", ad("Approved one")).text("id");
        String rejectedId = post(promoter, "/api/v1/promoter/ads", ad("Rejected one")).text("id");

        approve(admin, approvedId);
        reject(admin, rejectedId, "Logo is blurry");

        List<Map<String, Object>> approved = notifications(promoter, approvedId);
        assertThat(approved).hasSize(1);
        assertThat(approved.get(0).get("type")).isEqualTo("PROMO_AD_APPROVED");
        List<Map<String, Object>> rejected = notifications(promoter, rejectedId);
        assertThat(rejected).hasSize(1);
        assertThat(rejected.get(0).get("type")).isEqualTo("PROMO_AD_REJECTED");
        assertThat((String) rejected.get(0).get("message")).contains("Logo is blurry");
        assertThat(String.valueOf(rejected.get(0).get("params"))).contains("Logo is blurry");
        // The promoter's own bell is past the fence.
        assertThat(get(promoter, "/api/v1/notifications/unread-count").body().get("count").asLong())
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void eachSubmissionIsMailedWhenTheOrganisationHasEmailOn() {
        features.setEnabled(org, TenantFeature.EMAIL_NOTIFICATIONS, true);
        String adId = post(promoter, "/api/v1/promoter/ads", ad("First")).text("id");
        reject(admin, adId, "Too dark");
        put(promoter, "/api/v1/promoter/ads/" + adId, ad("Second"));

        // Two submissions of one ad: two mails to each admin, not one swallowed as a duplicate.
        assertThat(outbox(admin, "PROMO_AD_SUBMITTED")).isEqualTo(2);
        assertThat(outbox(otherAdmin, "PROMO_AD_SUBMITTED")).isEqualTo(2);
        assertThat(outbox(promoter, "PROMO_AD_REJECTED")).isEqualTo(1);
        assertThat(outbox(inactiveAdmin, "PROMO_AD_SUBMITTED")).isZero();
    }

    // -------------------------------------------------------------- helpers

    private Map<String, Object> ad(String title) {
        return body("businessId", wash.getId().toString(), "titleEn", title, "ctaType", "NONE",
                "propertyIds", List.of(p1.getId().toString()), "redirectUrl", null, "redirectParams", List.of());
    }

    private List<Map<String, Object>> notifications(User user, String adId) {
        return jdbc.queryForList("SELECT type, message, message_key, params::text AS params FROM notifications "
                + "WHERE user_id = ? AND reference_id = ?::uuid", user.getId(), adId);
    }

    private int outbox(User user, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE recipient_user_id = ? AND event_type = ?",
                Integer.class, user.getId(), type);
    }
}
