package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.PromoterProperty;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus.APPROVED;
import static com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus.PENDING;
import static com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Promoter plan, "Feed": an ad reaches a renter only when APPROVED, and a
 * promoter-written ad only at properties it both targets and that are currently
 * in that promoter's allowed set — through the home slate, the offers screen and
 * the impression/click endpoint alike.
 */
class PromoterFeedIT extends AbstractPromoterIT {

    private UUID org;
    private Property p1;
    private Property p2;
    private Property p3;
    private User renterAtP1;
    private User renterAtP2;
    private User renterAtP3;
    private PromoBusiness laundry;

    @BeforeEach
    void setUp() {
        org = org();
        p1 = property(org, "Marina");
        p2 = property(org, "Creek");
        p3 = property(org, "Hills");
        renterAtP1 = renterAt(org, p1);
        renterAtP2 = renterAt(org, p2);
        renterAtP3 = renterAt(org, p3);
        laundry = business(org, "QuickWash");
    }

    @Test
    void removingAPropertyFromThePromoterTakesItsAdOffThatPropertyAtOnce() {
        User promoter = promoter(org, false, List.of(laundry.getId()), List.of(p1.getId(), p2.getId()));
        PromoAd ad = ad(org, laundry.getId(), APPROVED, promoter.getId(), p1.getId(), p2.getId());

        assertThat(offerIds(renterAtP1)).contains(ad.getId().toString());
        assertThat(offerIds(renterAtP2)).contains(ad.getId().toString());

        TenantContextHolder.setTenantId(org);
        promoterPropertyRepo.delete(PromoterProperty.of(org, promoter.getId(), p2.getId()));
        TenantContextHolder.clear();

        assertThat(offerIds(renterAtP1)).contains(ad.getId().toString());
        assertThat(offerIds(renterAtP2)).doesNotContain(ad.getId().toString());
        assertThat(get(renterAtP2, "/api/v1/promotions/feed").body().findValuesAsText("id"))
                .doesNotContain(ad.getId().toString());
    }

    @Test
    void onlyApprovedAdsReachRenters() {
        PromoAd approved = ad(org, laundry.getId(), APPROVED, null);
        PromoAd pending = ad(org, laundry.getId(), PENDING, null);
        PromoAd rejected = ad(org, laundry.getId(), REJECTED, null);

        List<String> offers = offerIds(renterAtP1);
        assertThat(offers).contains(approved.getId().toString())
                .doesNotContain(pending.getId().toString(), rejected.getId().toString());
        List<String> slate = get(renterAtP1, "/api/v1/promotions/feed").body().findValuesAsText("id");
        assertThat(slate).doesNotContain(pending.getId().toString(), rejected.getId().toString());
    }

    @Test
    void aNonApprovedAdTakesNoImpressionOrClick() {
        PromoAd pending = ad(org, laundry.getId(), PENDING, null);
        PromoAd approved = ad(org, laundry.getId(), APPROVED, null);

        Resp res = post(renterAtP1, "/api/v1/promotions/events", Map.of("events", List.of(
                Map.of("adId", pending.getId().toString(), "type", "IMPRESSION"),
                Map.of("adId", pending.getId().toString(), "type", "CLICK"),
                Map.of("adId", approved.getId().toString(), "type", "IMPRESSION"))));

        assertThat(res.status()).isEqualTo(202);
        assertThat(events(pending.getId())).isZero();
        assertThat(events(approved.getId())).isEqualTo(1);
    }

    @Test
    void aPromoterAdShowsOnlyWhereTargetedAndAllowed() {
        // Allowed P1 and P2; the ad targets P2 and P3 (P3 slipped in, e.g. by an admin edit).
        User promoter = promoter(org, false, List.of(laundry.getId()), List.of(p1.getId(), p2.getId()));
        PromoAd ad = ad(org, laundry.getId(), APPROVED, promoter.getId(), p2.getId(), p3.getId());

        assertThat(offerIds(renterAtP1)).doesNotContain(ad.getId().toString()); // allowed, not targeted
        assertThat(offerIds(renterAtP2)).contains(ad.getId().toString());       // both
        assertThat(offerIds(renterAtP3)).doesNotContain(ad.getId().toString()); // targeted, not allowed
    }

    @Test
    void zeroTargetingNeverMeansEveryPropertyForAPromoterAd() {
        User promoter = promoter(org, false, List.of(laundry.getId()), List.of(p1.getId()));
        PromoAd promoterAd = ad(org, laundry.getId(), APPROVED, promoter.getId());
        PromoAd adminAd = ad(org, laundry.getId(), APPROVED, null);

        for (User renter : List.of(renterAtP1, renterAtP2, renterAtP3)) {
            List<String> offers = offerIds(renter);
            assertThat(offers).doesNotContain(promoterAd.getId().toString());
            // An admin's untargeted ad still runs everywhere, as before.
            assertThat(offers).contains(adminAd.getId().toString());
        }
    }

    @Test
    void anotherOrganisationsPromoterGrantsCountForNothing() {
        // A promoter of org B allowed (somehow) a row naming org A's property must not
        // make org A's ad attributed to it visible — rows are matched within the tenant.
        UUID orgB = org();
        User promoterB = promoter(orgB, false, List.of(), List.of());
        jdbc.update("INSERT INTO promoter_property (tenant_id, user_id, property_id) VALUES (?, ?, ?)",
                orgB, promoterB.getId(), p1.getId());
        PromoAd ad = ad(org, laundry.getId(), APPROVED, promoterB.getId(), p1.getId());

        assertThat(offerIds(renterAtP1)).doesNotContain(ad.getId().toString());
    }

    private int events(UUID adId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM promo_ad_events WHERE ad_id = ?", Integer.class, adId);
    }
}
