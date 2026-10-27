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

/**
 * Promoter plan addendum B: delete always succeeds — what nothing references is
 * deleted, what has history is archived (hidden everywhere, stats kept), and an
 * admin can restore it.
 */
class PromotionArchiveIT extends AbstractPromoterIT {

    private UUID org;
    private User admin;
    private Property p1;
    private User renter;

    @BeforeEach
    void setUp() {
        org = org();
        admin = user(org, UserRole.TENANT_ADMIN);
        p1 = property(org, "Marina");
        renter = renterAt(org, p1);
    }

    // -------------------------------------------------------------------- ads

    @Test
    void anAdThatNeverRanIsDeletedAndOneWithHistoryArchived() {
        PromoBusiness b = business(org, "Wash");
        PromoAd unused = ad(org, b.getId(), APPROVED, null);
        PromoAd ran = ad(org, b.getId(), APPROVED, null);
        impression(ran);

        Resp deleted = delete(admin, "/api/v1/promotions/ads/" + unused.getId());
        assertThat(deleted.status()).isEqualTo(200);
        assertThat(deleted.text("outcome")).isEqualTo("DELETED");
        assertThat(adRepo.findById(unused.getId())).isEmpty();

        Resp archived = delete(admin, "/api/v1/promotions/ads/" + ran.getId());
        assertThat(archived.status()).isEqualTo(200);
        assertThat(archived.text("outcome")).isEqualTo("ARCHIVED");
        PromoAd stored = reload(ran.getId());
        assertThat(stored.getArchivedAt()).isNotNull();
        assertThat(stored.isActive()).isFalse();

        assertThat(adIds("/api/v1/promotions/ads")).doesNotContain(ran.getId().toString());
        assertThat(adIds("/api/v1/promotions/ads?archived=true")).containsExactly(ran.getId().toString());
        Resp stats = get(admin, "/api/v1/promotions/ads/" + ran.getId() + "/stats");
        assertThat(stats.status()).isEqualTo(200);
        assertThat(stats.body().get("impressions").asLong()).isEqualTo(1);
    }

    @Test
    void anArchivedAdIsReadOnlyUntilRestored() {
        PromoBusiness b = business(org, "Wash");
        User promoter = promoter(org, false, List.of(b.getId()), List.of(p1.getId()));
        PromoAd ad = ad(org, b.getId(), PENDING, promoter.getId(), p1.getId());
        impression(ad);
        delete(admin, "/api/v1/promotions/ads/" + ad.getId());

        Map<String, Object> edit = body("businessId", b.getId().toString(), "titleEn", "x", "ctaType", "NONE",
                "propertyIds", List.of(p1.getId().toString()));
        assertThat(put(admin, "/api/v1/promotions/ads/" + ad.getId(), edit).text("message")).contains("archived");
        assertThat(put(admin, "/api/v1/promotions/ads/" + ad.getId(), edit).status()).isEqualTo(400);
        assertThat(approve(admin, ad.getId()).status()).isEqualTo(400);
        assertThat(reject(admin, ad.getId(), "no").status())
                .isEqualTo(400);
        assertThat(get(admin, "/api/v1/promotions/ads/pending-count").body().get("count").asLong()).isZero();

        // For the promoter an archived ad is gone.
        assertThat(put(promoter, "/api/v1/promoter/ads/" + ad.getId(), edit).status()).isEqualTo(404);
        assertThat(get(promoter, "/api/v1/promoter/ads/" + ad.getId() + "/stats").status()).isEqualTo(404);
        assertThat(delete(promoter, "/api/v1/promoter/ads/" + ad.getId()).status()).isEqualTo(404);
        assertThat(get(promoter, "/api/v1/promoter/ads").body().get("content").findValuesAsText("id"))
                .doesNotContain(ad.getId().toString());

        Resp restored = post(admin, "/api/v1/promotions/ads/" + ad.getId() + "/restore", null);
        assertThat(restored.status()).isEqualTo(200);
        assertThat(restored.text("id")).isEqualTo(ad.getId().toString());
        assertThat(restored.body().get("active").asBoolean()).isFalse();
        assertThat(reload(ad.getId()).getArchivedAt()).isNull();
        assertThat(get(admin, "/api/v1/promotions/ads/pending-count").body().get("count").asLong()).isEqualTo(1);
        assertThat(post(admin, "/api/v1/promotions/ads/" + ad.getId() + "/restore", null).status()).isEqualTo(400);
    }

    @Test
    void aPromoterDeletesItsAdTheSameWay() {
        PromoBusiness b = business(org, "Wash");
        User promoter = promoter(org, false, List.of(b.getId()), List.of(p1.getId()));
        PromoAd unused = ad(org, b.getId(), PENDING, promoter.getId(), p1.getId());
        PromoAd ran = ad(org, b.getId(), APPROVED, promoter.getId(), p1.getId());
        impression(ran);

        assertThat(delete(promoter, "/api/v1/promoter/ads/" + unused.getId()).text("outcome")).isEqualTo("DELETED");
        Resp archived = delete(promoter, "/api/v1/promoter/ads/" + ran.getId());
        assertThat(archived.status()).isEqualTo(200);
        assertThat(archived.text("outcome")).isEqualTo("ARCHIVED");
        assertThat(reload(ran.getId()).getArchivedAt()).isNotNull();
    }

    // ------------------------------------------------------------- businesses

    @Test
    void aBusinessWithoutAdsIsDeletedAndOneWithAdsArchivedWithThem() {
        PromoBusiness empty = business(org, "Empty");
        PromoBusiness busy = business(org, "Busy");
        PromoAd neverRan = ad(org, busy.getId(), APPROVED, null);
        PromoAd ran = ad(org, busy.getId(), APPROVED, null);
        impression(ran);

        Resp deleted = delete(admin, "/api/v1/promotions/businesses/" + empty.getId());
        assertThat(deleted.status()).isEqualTo(200);
        assertThat(deleted.text("outcome")).isEqualTo("DELETED");
        assertThat(businessRepo.findById(empty.getId())).isEmpty();

        Resp archived = delete(admin, "/api/v1/promotions/businesses/" + busy.getId());
        assertThat(archived.status()).isEqualTo(200);
        assertThat(archived.text("outcome")).isEqualTo("ARCHIVED");
        PromoBusiness stored = businessRepo.findById(busy.getId()).orElseThrow();
        assertThat(stored.getArchivedAt()).isNotNull();
        assertThat(stored.isActive()).isFalse();
        // Every ad is archived with it, event history or not — none hard-deleted.
        for (PromoAd a : List.of(neverRan, ran)) {
            PromoAd now = reload(a.getId());
            assertThat(now.getArchivedAt()).isNotNull();
            assertThat(now.isActive()).isFalse();
        }

        assertThat(businessIds("/api/v1/promotions/businesses")).doesNotContain(busy.getId().toString());
        assertThat(businessIds("/api/v1/promotions/businesses?archived=true")).containsExactly(busy.getId().toString());
        assertThat(adIds("/api/v1/promotions/ads")).isEmpty();
        assertThat(adIds("/api/v1/promotions/ads?archived=true"))
                .containsExactlyInAnyOrder(neverRan.getId().toString(), ran.getId().toString());
    }

    @Test
    void anArchivedBusinessFreesItsNameAndCannotComeBackWhileItIsTaken() {
        String name = "QuickWash " + UUID.randomUUID().toString().substring(0, 6);
        String firstId = post(admin, "/api/v1/promotions/businesses", body("nameEn", name)).text("id");
        PromoAd ad = ad(org, UUID.fromString(firstId), APPROVED, null);
        assertThat(delete(admin, "/api/v1/promotions/businesses/" + firstId).text("outcome")).isEqualTo("ARCHIVED");

        Resp second = post(admin, "/api/v1/promotions/businesses", body("nameEn", name.toUpperCase()));
        assertThat(second.status()).isEqualTo(201);

        Resp blocked = post(admin, "/api/v1/promotions/businesses/" + firstId + "/restore", null);
        assertThat(blocked.status()).isEqualTo(400);
        assertThat(blocked.text("message")).contains("already exists");
        assertThat(businessRepo.findById(UUID.fromString(firstId)).orElseThrow().getArchivedAt()).isNotNull();

        // With the name free again, it comes back — inactive, its ads still archived.
        delete(admin, "/api/v1/promotions/businesses/" + second.text("id"));
        Resp restored = post(admin, "/api/v1/promotions/businesses/" + firstId + "/restore", null);
        assertThat(restored.status()).isEqualTo(200);
        assertThat(restored.body().get("active").asBoolean()).isFalse();
        assertThat(reload(ad.getId()).getArchivedAt()).isNotNull();
    }

    @Test
    void anAdUnderAnArchivedBusinessWaitsForTheBusiness() {
        PromoBusiness b = business(org, "Wash");
        PromoAd ad = ad(org, b.getId(), APPROVED, null);
        delete(admin, "/api/v1/promotions/businesses/" + b.getId());

        Resp early = post(admin, "/api/v1/promotions/ads/" + ad.getId() + "/restore", null);
        assertThat(early.status()).isEqualTo(400);
        assertThat(early.text("message")).contains("Restore the business first");

        // Nor can an ad be written onto it, or it be edited, while archived.
        assertThat(post(admin, "/api/v1/promotions/ads", body("businessId", b.getId().toString(),
                "titleEn", "x", "ctaType", "NONE")).status()).isEqualTo(400);
        assertThat(put(admin, "/api/v1/promotions/businesses/" + b.getId(), body("nameEn", "x")).status())
                .isEqualTo(400);

        assertThat(post(admin, "/api/v1/promotions/businesses/" + b.getId() + "/restore", null).status())
                .isEqualTo(200);
        assertThat(post(admin, "/api/v1/promotions/ads/" + ad.getId() + "/restore", null).status()).isEqualTo(200);
    }

    @Test
    void aPromoterArchivesABusinessOnlyWithTheSwitchAndTheGrant() {
        PromoBusiness b = business(org, "Wash");
        PromoBusiness other = business(org, "Other");
        PromoAd ad = ad(org, b.getId(), APPROVED, null, p1.getId());
        User noSwitch = promoter(org, false, List.of(b.getId()), List.of(p1.getId()));
        User withSwitch = promoter(org, true, List.of(b.getId()), List.of(p1.getId()));

        assertThat(delete(noSwitch, "/api/v1/promoter/businesses/" + b.getId()).status()).isEqualTo(403);
        assertThat(delete(withSwitch, "/api/v1/promoter/businesses/" + other.getId()).status()).isEqualTo(404);

        Resp res = delete(withSwitch, "/api/v1/promoter/businesses/" + b.getId());
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.text("outcome")).isEqualTo("ARCHIVED");
        // Gone from the promoter's portal, and its ads with it.
        assertThat(get(withSwitch, "/api/v1/promoter/businesses").body().findValuesAsText("id"))
                .doesNotContain(b.getId().toString());
        assertThat(get(withSwitch, "/api/v1/promoter/ads?businessId=" + b.getId()).status()).isEqualTo(404);
        assertThat(get(withSwitch, "/api/v1/promoter/ads/" + ad.getId() + "/stats").status()).isEqualTo(404);
        assertThat(delete(withSwitch, "/api/v1/promoter/businesses/" + b.getId()).status()).isEqualTo(404);

        PromoBusiness fresh = business(org, "Fresh");
        User owner = promoter(org, true, List.of(fresh.getId()), List.of());
        assertThat(delete(owner, "/api/v1/promoter/businesses/" + fresh.getId()).text("outcome")).isEqualTo("DELETED");
    }

    // ------------------------------------------------------------------ feed

    @Test
    void archivedRowsNeverReachTheFeedEvenIfMarkedActive() {
        PromoBusiness b = business(org, "Wash");
        PromoAd archivedAd = ad(org, b.getId(), APPROVED, null);
        PromoBusiness archivedBiz = business(org, "Gone");
        PromoAd underArchivedBiz = ad(org, archivedBiz.getId(), APPROVED, null);
        // Archive, then force active back on: archived_at alone must keep them out.
        jdbc.update("UPDATE promo_ads SET archived_at = now(), active = true WHERE id = ?", archivedAd.getId());
        jdbc.update("UPDATE promo_businesses SET archived_at = now(), active = true WHERE id = ?", archivedBiz.getId());

        List<String> offers = offerIds(renter);
        assertThat(offers).doesNotContain(archivedAd.getId().toString(), underArchivedBiz.getId().toString());
        assertThat(get(renter, "/api/v1/promotions/feed").body().findValuesAsText("id"))
                .doesNotContain(archivedAd.getId().toString(), underArchivedBiz.getId().toString());

        post(renter, "/api/v1/promotions/events", Map.of("events", List.of(
                Map.of("adId", archivedAd.getId().toString(), "type", "IMPRESSION"),
                Map.of("adId", underArchivedBiz.getId().toString(), "type", "CLICK"))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM promo_ad_events WHERE ad_id IN (?, ?)",
                Integer.class, archivedAd.getId(), underArchivedBiz.getId())).isZero();
    }

    // ---------------------------------------------------------- cross-tenant

    @Test
    void anotherOrganisationCannotDeleteOrRestoreOurs() {
        PromoBusiness b = business(org, "Wash");
        PromoAd ad = ad(org, b.getId(), APPROVED, null);
        impression(ad);
        UUID orgB = org();
        User adminB = user(orgB, UserRole.TENANT_ADMIN);
        User promoterB = promoter(orgB, true, List.of(), List.of());

        assertThat(delete(adminB, "/api/v1/promotions/ads/" + ad.getId()).status()).isEqualTo(404);
        assertThat(delete(adminB, "/api/v1/promotions/businesses/" + b.getId()).status()).isEqualTo(404);
        assertThat(delete(promoterB, "/api/v1/promoter/businesses/" + b.getId()).status()).isEqualTo(404);
        assertThat(delete(promoterB, "/api/v1/promoter/ads/" + ad.getId()).status()).isEqualTo(404);
        assertThat(reload(ad.getId()).getArchivedAt()).isNull();

        delete(admin, "/api/v1/promotions/businesses/" + b.getId());
        assertThat(post(adminB, "/api/v1/promotions/businesses/" + b.getId() + "/restore", null).status())
                .isEqualTo(404);
        assertThat(post(adminB, "/api/v1/promotions/ads/" + ad.getId() + "/restore", null).status()).isEqualTo(404);
        assertThat(businessIds("/api/v1/promotions/businesses?archived=true", adminB)).isEmpty();
    }

    @Test
    void onlyAdminsRestore() {
        PromoBusiness b = business(org, "Wash");
        PromoAd ad = ad(org, b.getId(), APPROVED, null);
        delete(admin, "/api/v1/promotions/businesses/" + b.getId());
        User promoter = promoter(org, true, List.of(b.getId()), List.of());
        User manager = user(org, UserRole.PROPERTY_MANAGER);

        assertThat(post(promoter, "/api/v1/promotions/businesses/" + b.getId() + "/restore", null).status())
                .isEqualTo(403);
        assertThat(post(manager, "/api/v1/promotions/businesses/" + b.getId() + "/restore", null).status())
                .isEqualTo(403);
        assertThat(post(manager, "/api/v1/promotions/ads/" + ad.getId() + "/restore", null).status()).isEqualTo(403);
    }

    // -------------------------------------------------------------- helpers

    private void impression(PromoAd ad) {
        jdbc.update("INSERT INTO promo_ad_events (id, tenant_id, ad_id, renter_user_id, event_type, occurred_at, day) "
                        + "VALUES (?, ?, ?, ?, 'IMPRESSION', now(), (now() AT TIME ZONE 'Asia/Dubai')::date)",
                UUID.randomUUID(), org, ad.getId(), renter.getId());
    }

    private List<String> adIds(String path) {
        Resp res = get(admin, path + (path.contains("?") ? "&" : "?") + "size=100");
        assertThat(res.status()).isEqualTo(200);
        return topLevelIds(res);
    }

    private List<String> businessIds(String path) {
        return businessIds(path, admin);
    }

    private List<String> businessIds(String path, User caller) {
        Resp res = get(caller, path + (path.contains("?") ? "&" : "?") + "size=100");
        assertThat(res.status()).isEqualTo(200);
        return topLevelIds(res);
    }

    private static List<String> topLevelIds(Resp res) {
        List<String> out = new java.util.ArrayList<>();
        res.body().get("content").forEach(n -> out.add(n.get("id").asText()));
        return out;
    }
}
