package com.datagami.rentaxis.api;

import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.PromoPlacement;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus.APPROVED;
import static com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus.PENDING;
import static com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Promoter plan, "Promoter API": a promoter sees and writes only what it was
 * granted, in its own organisation; anything else is 404 (never 403 — no probing),
 * except a capability it visibly lacks (creating businesses: 403). Every write of
 * an ad, or of a business with live ads, sends those ads back to PENDING.
 */
class PromoterApiIT extends AbstractPromoterIT {

    private UUID org;
    private Property p1;
    private Property p2;
    private Property p3;
    private PromoBusiness granted;
    private PromoBusiness notGranted;
    private User promoter;

    @BeforeEach
    void setUp() {
        org = org();
        p1 = property(org, "Marina");
        p2 = property(org, "Creek");
        p3 = property(org, "Hills");
        granted = business(org, "QuickWash");
        notGranted = business(org, "Spice Bazaar");
        promoter = promoter(org, false, List.of(granted.getId()), List.of(p1.getId(), p2.getId()));
    }

    // ------------------------------------------------------------ reads

    @Test
    void meNamesTheSwitchAndTheOrganisation() {
        Resp me = get(promoter, "/api/v1/promoter/me");

        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().get("canCreateBusinesses").asBoolean()).isFalse();
        assertThat(me.text("organisationName")).isEqualTo(orgRepo.findById(org).orElseThrow().getName());
    }

    @Test
    void onlyTheGivenPropertiesAndGrantedBusinessesAreListed() {
        Resp props = get(promoter, "/api/v1/promoter/properties");
        assertThat(props.status()).isEqualTo(200);
        assertThat(props.body().findValuesAsText("id")).containsExactlyInAnyOrder(
                p1.getId().toString(), p2.getId().toString());
        JsonNode first = props.body().get(0);
        assertThat(fieldNames(first)).containsExactlyInAnyOrder("id", "nameEn", "nameAr");

        Resp businesses = get(promoter, "/api/v1/promoter/businesses");
        assertThat(businesses.status()).isEqualTo(200);
        assertThat(businesses.body().isArray()).isTrue();
        assertThat(businesses.body().findValuesAsText("id")).containsExactly(granted.getId().toString());
    }

    @Test
    void theAdListShowsGrantedBusinessesOnlyWithTargetingCutToTheOwnProperties() {
        PromoAd mine = ad(org, granted.getId(), APPROVED, null, p1.getId(), p3.getId());
        PromoAd other = ad(org, notGranted.getId(), APPROVED, null);

        Resp page = get(promoter, "/api/v1/promoter/ads");

        assertThat(page.status()).isEqualTo(200);
        JsonNode content = page.body().get("content");
        assertThat(content.findValuesAsText("id")).contains(mine.getId().toString())
                .doesNotContain(other.getId().toString());
        JsonNode dto = find(content, mine.getId());
        assertThat(texts(dto.get("propertyIds"))).containsExactly(p1.getId().toString());
        assertThat(dto.get("impressions").asLong()).isZero();
        assertThat(dto.get("clicks").asLong()).isZero();
        assertThat(dto.get("approvalStatus").asText()).isEqualTo("APPROVED");

        assertThat(get(promoter, "/api/v1/promoter/ads?businessId=" + notGranted.getId()).status()).isEqualTo(404);
    }

    // ------------------------------------------------------------ writes

    @Test
    void aCreatedAdIsPendingAttributedAndRankedByDefault() {
        Resp res = post(promoter, "/api/v1/promoter/ads", adBody(granted.getId(), List.of(p1.getId()),
                "priority", 9, "placement", "OFFERS_ONLY"));

        assertThat(res.status()).isEqualTo(201);
        assertThat(res.text("approvalStatus")).isEqualTo("PENDING");
        assertThat(res.text("submittedByName")).isEqualTo(promoter.getName());
        assertThat(res.text("submittedAt")).isNotNull();
        assertThat(res.body().get("priority").asInt()).isEqualTo(1);
        assertThat(res.text("placement")).isEqualTo("HOME_AND_OFFERS");
        PromoAd stored = reload(UUID.fromString(res.text("id")));
        assertThat(stored.getApprovalStatus()).isEqualTo(PENDING);
        assertThat(stored.getPromoterUserId()).isEqualTo(promoter.getId());
        assertThat(stored.getPriority()).isEqualTo(1);
        assertThat(stored.getPlacement()).isEqualTo(PromoPlacement.HOME_AND_OFFERS);
    }

    @Test
    void anEditSendsTheAdBackForApprovalAndKeepsTheAdminsRanking() {
        PromoAd ad = ad(org, granted.getId(), APPROVED, null, p1.getId());
        jdbc.update("UPDATE promo_ads SET priority = 7, placement = 'OFFERS_ONLY', reviewed_at = now(), "
                + "rejection_reason = NULL WHERE id = ?", ad.getId());

        Resp res = put(promoter, "/api/v1/promoter/ads/" + ad.getId(), adBody(granted.getId(), List.of(p2.getId()),
                "priority", 1, "placement", "HOME_AND_OFFERS", "titleEn", "New title"));

        assertThat(res.status()).isEqualTo(200);
        PromoAd stored = reload(ad.getId());
        assertThat(stored.getApprovalStatus()).isEqualTo(PENDING);
        assertThat(stored.getPromoterUserId()).isEqualTo(promoter.getId());
        assertThat(stored.getReviewedAt()).isNull();
        assertThat(stored.getTitleEn()).isEqualTo("New title");
        assertThat(stored.getPriority()).isEqualTo(7);
        assertThat(stored.getPlacement()).isEqualTo(PromoPlacement.OFFERS_ONLY);
    }

    @Test
    void anEditOfTheCouponRedirectAloneSendsTheAdBackToo() {
        jdbc.update("UPDATE promo_businesses SET allowed_domains = 'quickwash.example' WHERE id = ?", granted.getId());
        PromoAd ad = ad(org, granted.getId(), APPROVED, promoter.getId(), p1.getId());
        Map<String, Object> req = adBody(granted.getId(), List.of(p1.getId()),
                "ctaType", "COUPON", "couponCode", "SAVE10",
                "redirectUrl", "https://app.quickwash.example/r",
                "redirectParams", List.of(body("name", "n", "source", "RENTER_NAME", "value", null)));

        Resp res = put(promoter, "/api/v1/promoter/ads/" + ad.getId(), req);

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.text("redirectUrl")).isEqualTo("https://app.quickwash.example/r");
        assertThat(reload(ad.getId()).getApprovalStatus()).isEqualTo(PENDING);
    }

    @Test
    void anEditOfThePlatformLinksAloneSendsTheAdBackToo() {
        // Addendum C: the Android/iPhone links are part of the ad, so an edit that changes
        // nothing else still goes back in front of an admin; both round-trip for a promoter.
        jdbc.update("UPDATE promo_businesses SET allowed_domains = 'quickwash.example' WHERE id = ?", granted.getId());
        Map<String, Object> req = adBody(granted.getId(), List.of(p1.getId()),
                "ctaType", "COUPON", "couponCode", "SAVE10",
                "redirectUrl", "https://app.quickwash.example/r",
                "redirectParams", List.of(body("name", "n", "source", "RENTER_NAME", "value", null)));
        Resp created = post(promoter, "/api/v1/promoter/ads", req);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("redirectUrlAndroid").isNull()).isTrue();
        assertThat(created.body().get("redirectUrlIos").isNull()).isTrue();
        UUID id = UUID.fromString(created.text("id"));
        assertThat(approve(user(org, UserRole.TENANT_ADMIN), id).status()).isEqualTo(204);
        assertThat(reload(id).getApprovalStatus()).isEqualTo(APPROVED);

        req.put("redirectUrlAndroid", "https://android.quickwash.example/open");
        req.put("redirectUrlIos", "https://ios.quickwash.example/open");
        Resp res = put(promoter, "/api/v1/promoter/ads/" + id, req);

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.text("redirectUrlAndroid")).isEqualTo("https://android.quickwash.example/open");
        assertThat(res.text("redirectUrlIos")).isEqualTo("https://ios.quickwash.example/open");
        assertThat(res.text("approvalStatus")).isEqualTo("PENDING");
        PromoAd stored = reload(id);
        assertThat(stored.getApprovalStatus()).isEqualTo(PENDING);
        assertThat(stored.getRedirectUrlAndroid()).isEqualTo("https://android.quickwash.example/open");
        assertThat(stored.getRedirectUrlIos()).isEqualTo("https://ios.quickwash.example/open");
        JsonNode listed = find(get(promoter, "/api/v1/promoter/ads").body().get("content"), id);
        assertThat(listed.get("redirectUrlAndroid").asText()).isEqualTo("https://android.quickwash.example/open");
        assertThat(listed.get("redirectUrlIos").asText()).isEqualTo("https://ios.quickwash.example/open");
    }

    @Test
    void propertiesMustBeOwnAndAtLeastOne() {
        assertThat(post(promoter, "/api/v1/promoter/ads", adBody(granted.getId(), List.of())).status())
                .isEqualTo(400);
        Map<String, Object> noProps = adBody(granted.getId(), List.of());
        noProps.remove("propertyIds");
        assertThat(post(promoter, "/api/v1/promoter/ads", noProps).status()).isEqualTo(400);
        assertThat(post(promoter, "/api/v1/promoter/ads", adBody(granted.getId(), List.of(p1.getId(), p3.getId())))
                .status()).isEqualTo(400);
        assertThat(post(promoter, "/api/v1/promoter/ads", adBody(granted.getId(), List.of(UUID.randomUUID())))
                .status()).isEqualTo(400);

        PromoAd ad = ad(org, granted.getId(), APPROVED, null, p1.getId());
        assertThat(put(promoter, "/api/v1/promoter/ads/" + ad.getId(), adBody(granted.getId(), List.of(p3.getId())))
                .status()).isEqualTo(400);
        assertThat(reload(ad.getId()).getApprovalStatus()).isEqualTo(APPROVED);
    }

    @Test
    void anythingOutsideTheGrantsIs404() {
        PromoAd foreign = ad(org, notGranted.getId(), APPROVED, null);

        assertThat(post(promoter, "/api/v1/promoter/ads", adBody(notGranted.getId(), List.of(p1.getId())))
                .status()).isEqualTo(404);
        assertThat(put(promoter, "/api/v1/promoter/ads/" + foreign.getId(), adBody(granted.getId(), List.of(p1.getId())))
                .status()).isEqualTo(404);
        // Moving one's own ad onto a business one was not granted.
        PromoAd mine = ad(org, granted.getId(), APPROVED, null, p1.getId());
        assertThat(put(promoter, "/api/v1/promoter/ads/" + mine.getId(), adBody(notGranted.getId(), List.of(p1.getId())))
                .status()).isEqualTo(404);
        assertThat(delete(promoter, "/api/v1/promoter/ads/" + foreign.getId()).status()).isEqualTo(404);
        assertThat(get(promoter, "/api/v1/promoter/ads/" + foreign.getId() + "/stats").status()).isEqualTo(404);
        assertThat(get(promoter, "/api/v1/promoter/ads/" + UUID.randomUUID() + "/stats").status()).isEqualTo(404);
        assertThat(reload(foreign.getId()).getApprovalStatus()).isEqualTo(APPROVED);
    }

    @Test
    void businessesNeedTheSwitch() {
        Map<String, Object> biz = body("nameEn", "Sparkle " + UUID.randomUUID());
        assertThat(post(promoter, "/api/v1/promoter/businesses", biz).status()).isEqualTo(403);
        assertThat(put(promoter, "/api/v1/promoter/businesses/" + granted.getId(), biz).status()).isEqualTo(403);
        // Not granted: 404 before the switch is even considered.
        assertThat(put(promoter, "/api/v1/promoter/businesses/" + notGranted.getId(), biz).status()).isEqualTo(404);
    }

    @Test
    void aCreatedBusinessIsGrantedToItsCreator() {
        User creator = promoter(org, true, List.of(), List.of(p1.getId()));

        Resp res = post(creator, "/api/v1/promoter/businesses", body("nameEn", "Sparkle " + UUID.randomUUID()));

        assertThat(res.status()).isEqualTo(201);
        assertThat(get(creator, "/api/v1/promoter/businesses").body().findValuesAsText("id"))
                .containsExactly(res.text("id"));
        assertThat(post(creator, "/api/v1/promoter/ads", adBody(UUID.fromString(res.text("id")), List.of(p1.getId())))
                .status()).isEqualTo(201);
    }

    @Test
    void aBusinessEditSendsItsLiveAdsBackForApproval() {
        User editor = promoter(org, true, List.of(granted.getId()), List.of(p1.getId()));
        PromoAd live = ad(org, granted.getId(), APPROVED, null, p1.getId());
        PromoAd rejected = ad(org, granted.getId(), REJECTED, editor.getId(), p1.getId());
        PromoAd elsewhere = ad(org, notGranted.getId(), APPROVED, null);

        Resp res = put(editor, "/api/v1/promoter/businesses/" + granted.getId(),
                body("nameEn", "QuickWash Renamed " + UUID.randomUUID(), "phoneE164", "+971501234567"));

        assertThat(res.status()).isEqualTo(200);
        PromoAd liveNow = reload(live.getId());
        assertThat(liveNow.getApprovalStatus()).isEqualTo(PENDING);
        assertThat(liveNow.getPromoterUserId()).isEqualTo(editor.getId());
        assertThat(liveNow.getSubmittedAt()).isNotNull();
        assertThat(reload(rejected.getId()).getApprovalStatus()).isEqualTo(REJECTED);
        assertThat(reload(elsewhere.getId()).getApprovalStatus()).isEqualTo(APPROVED);
    }

    @Test
    void statsAndDeleteFollowTheAdminRule() {
        PromoAd unused = ad(org, granted.getId(), PENDING, promoter.getId(), p1.getId());
        Resp gone = delete(promoter, "/api/v1/promoter/ads/" + unused.getId());
        assertThat(gone.status()).isEqualTo(200);
        assertThat(gone.text("outcome")).isEqualTo("DELETED");
        assertThat(adRepo.findById(unused.getId())).isEmpty();

        PromoAd ran = ad(org, granted.getId(), APPROVED, promoter.getId(), p1.getId());
        User renter = renterAt(org, p1);
        jdbc.update("INSERT INTO promo_ad_events (id, tenant_id, ad_id, renter_user_id, event_type, occurred_at, day) "
                        + "VALUES (?, ?, ?, ?, 'IMPRESSION', now(), (now() AT TIME ZONE 'Asia/Dubai')::date)",
                UUID.randomUUID(), org, ran.getId(), renter.getId());
        Resp stats = get(promoter, "/api/v1/promoter/ads/" + ran.getId() + "/stats");
        assertThat(stats.status()).isEqualTo(200);
        assertThat(stats.body().get("impressions").asLong()).isEqualTo(1);
        assertThat(find(get(promoter, "/api/v1/promoter/ads?size=50").body().get("content"), ran.getId())
                .get("impressions").asLong()).isEqualTo(1);
        // With view history it is archived (addendum B), not refused.
        Resp archived = delete(promoter, "/api/v1/promoter/ads/" + ran.getId());
        assertThat(archived.status()).isEqualTo(200);
        assertThat(archived.text("outcome")).isEqualTo("ARCHIVED");
    }

    // ------------------------------------------------------------ cross-tenant

    @Test
    void anotherOrganisationsBusinessesAdsAndPropertiesAreOutOfReach() {
        UUID orgB = org();
        Property propB = property(orgB, "Other");
        PromoBusiness bizB = business(orgB, "Other Wash");
        PromoAd adB = ad(orgB, bizB.getId(), APPROVED, null, propB.getId());

        assertThat(get(promoter, "/api/v1/promoter/ads?businessId=" + bizB.getId()).status()).isEqualTo(404);
        assertThat(post(promoter, "/api/v1/promoter/ads", adBody(bizB.getId(), List.of(p1.getId()))).status())
                .isEqualTo(404);
        assertThat(post(promoter, "/api/v1/promoter/ads", adBody(granted.getId(), List.of(propB.getId()))).status())
                .isEqualTo(400);
        assertThat(put(promoter, "/api/v1/promoter/ads/" + adB.getId(), adBody(granted.getId(), List.of(p1.getId())))
                .status()).isEqualTo(404);
        assertThat(delete(promoter, "/api/v1/promoter/ads/" + adB.getId()).status()).isEqualTo(404);
        assertThat(get(promoter, "/api/v1/promoter/ads/" + adB.getId() + "/stats").status()).isEqualTo(404);
        assertThat(put(promoter, "/api/v1/promoter/businesses/" + bizB.getId(), body("nameEn", "x")).status())
                .isEqualTo(404);
        assertThat(get(promoter, "/api/v1/promoter/properties").body().findValuesAsText("id"))
                .doesNotContain(propB.getId().toString());
        assertThat(reload(adB.getId()).getApprovalStatus()).isEqualTo(APPROVED);

        // Naming org B in X-Tenant-Id gets the promoter nowhere: it belongs to org A only.
        Resp asB = client().get().uri("/api/v1/promoter/me")
                .header("X-User-Id", promoter.getId().toString()).header("X-User-Role", "PROMOTER")
                .header("X-Tenant-Id", orgB.toString()).header("X-User-Tenant-Id", orgB.toString())
                .exchange((req, r) -> new Resp(r.getStatusCode().value(), null));
        assertThat(asB.status()).isEqualTo(403);
    }

    @Test
    void anotherRoleCannotUseThePromoterApi() {
        User admin = user(org, UserRole.TENANT_ADMIN);
        assertThat(get(admin, "/api/v1/promoter/me").status()).isEqualTo(403);
        assertThat(get(admin, "/api/v1/promoter/ads").status()).isEqualTo(403);
    }

    @Test
    void aMalformedImageNameIs404NotTheFence() {
        assertThat(get(promoter, "/api/v1/promoter/images/not-a-file.svg").status()).isEqualTo(404);
    }

    // -------------------------------------------------------------- helpers

    private static Map<String, Object> adBody(UUID businessId, List<UUID> propertyIds, Object... extra) {
        Map<String, Object> m = body("businessId", businessId.toString(), "titleEn", "Wash day",
                "ctaType", "NONE", "propertyIds", propertyIds.stream().map(UUID::toString).toList(),
                "redirectUrl", null, "redirectParams", List.of());
        for (int i = 0; i < extra.length; i += 2) {
            m.put((String) extra[i], extra[i + 1]);
        }
        return m;
    }

    private static JsonNode find(JsonNode array, UUID id) {
        return StreamSupport.stream(array.spliterator(), false)
                .filter(n -> n.get("id").asText().equals(id.toString())).findFirst().orElseThrow();
    }

    private static List<String> texts(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> out = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }
}
