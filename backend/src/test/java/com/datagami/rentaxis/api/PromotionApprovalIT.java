package com.datagami.rentaxis.api;

import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.UserTenantMembershipRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus.APPROVED;
import static com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus.PENDING;
import static com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Promoter plan, "Admin API" and the approval tests: nothing a promoter writes
 * reaches a renter without an admin's approval after the write; admins approve or
 * reject (with a reason) only PENDING ads of their own organisation; and
 * Promotions → Promoters invites and re-scopes promoters.
 */
class PromotionApprovalIT extends AbstractPromoterIT {

    @Autowired UserTenantMembershipRepository membershipRepo;

    private UUID org;
    private User admin;
    private Property p1;
    private PromoBusiness wash;
    private User promoter;
    private User renter;

    @BeforeEach
    void setUp() {
        org = org();
        admin = user(org, UserRole.TENANT_ADMIN);
        p1 = property(org, "Marina");
        wash = business(org, "QuickWash");
        promoter = promoter(org, false, List.of(wash.getId()), List.of(p1.getId()));
        renter = renterAt(org, p1);
    }

    // --------------------------------------------------------------- approval

    @Test
    void nothingAPromoterWritesReachesARenterUntilApprovedAfterTheWrite() {
        String adId = post(promoter, "/api/v1/promoter/ads", promoterAd("First")).text("id");
        assertThat(offerIds(renter)).doesNotContain(adId);

        assertThat(approve(admin, adId).status()).isEqualTo(204);
        PromoAd approved = reload(UUID.fromString(adId));
        assertThat(approved.getApprovalStatus()).isEqualTo(APPROVED);
        assertThat(approved.getReviewedBy()).isEqualTo(admin.getId());
        assertThat(approved.getReviewedAt()).isNotNull();
        assertThat(offerIds(renter)).contains(adId);

        assertThat(put(promoter, "/api/v1/promoter/ads/" + adId, promoterAd("Edited")).status()).isEqualTo(200);
        assertThat(reload(UUID.fromString(adId)).getApprovalStatus()).isEqualTo(PENDING);
        assertThat(offerIds(renter)).doesNotContain(adId);
    }

    @Test
    void aRejectionNeedsAReasonAndThePromoterSeesIt() {
        String adId = post(promoter, "/api/v1/promoter/ads", promoterAd("Wash")).text("id");

        assertThat(reject(admin, adId, "   ").status())
                .isEqualTo(400);
        assertThat(post(admin, "/api/v1/promotions/ads/" + adId + "/reject", null).status()).isEqualTo(400);
        assertThat(reject(admin, adId, "x".repeat(501))
                .status()).isEqualTo(400);
        assertThat(reload(UUID.fromString(adId)).getApprovalStatus()).isEqualTo(PENDING);

        assertThat(reject(admin, adId, "  Logo is blurry  ").status()).isEqualTo(204);

        JsonNode mine = find(get(promoter, "/api/v1/promoter/ads").body().get("content"), adId);
        assertThat(mine.get("approvalStatus").asText()).isEqualTo("REJECTED");
        assertThat(mine.get("rejectionReason").asText()).isEqualTo("Logo is blurry");
        assertThat(mine.get("reviewedAt").isNull()).isFalse();
        assertThat(offerIds(renter)).doesNotContain(adId);

        // Fixing it sends it back; the old verdict goes.
        put(promoter, "/api/v1/promoter/ads/" + adId, promoterAd("Sharper"));
        PromoAd resubmitted = reload(UUID.fromString(adId));
        assertThat(resubmitted.getApprovalStatus()).isEqualTo(PENDING);
        assertThat(resubmitted.getRejectionReason()).isNull();
        // Approving clears no reason that is not there, and the ad goes live.
        approve(admin, adId);
        assertThat(reload(UUID.fromString(adId)).getRejectionReason()).isNull();
    }

    @Test
    void onlyAPendingAdCanBeDecided() {
        PromoAd live = ad(org, wash.getId(), APPROVED, null);
        PromoAd rejected = ad(org, wash.getId(), REJECTED, promoter.getId(), p1.getId());

        assertThat(approve(admin, live.getId()).status()).isEqualTo(400);
        assertThat(reject(admin, live.getId(), "no")
                .status()).isEqualTo(400);
        assertThat(approve(admin, rejected.getId()).status())
                .isEqualTo(400);
        assertThat(approve(admin, UUID.randomUUID()).status())
                .isEqualTo(404);
    }

    @Test
    void anAdminsAdIsLiveOnCreateAndAnAdminEditKeepsTheApprovalState() {
        Resp created = post(admin, "/api/v1/promotions/ads", body("businessId", wash.getId().toString(),
                "titleEn", "Admin offer", "ctaType", "NONE"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("approvalStatus")).isEqualTo("APPROVED");
        assertThat(created.body().get("submittedByName").isNull()).isTrue();
        assertThat(offerIds(renter)).contains(created.text("id"));

        String pendingId = post(promoter, "/api/v1/promoter/ads", promoterAd("Promoter offer")).text("id");
        Resp edited = put(admin, "/api/v1/promotions/ads/" + pendingId, body("businessId", wash.getId().toString(),
                "titleEn", "Tidied by admin", "ctaType", "NONE", "priority", 5,
                "propertyIds", List.of(p1.getId().toString())));
        assertThat(edited.status()).isEqualTo(200);
        assertThat(edited.text("approvalStatus")).isEqualTo("PENDING");
        assertThat(edited.text("submittedByName")).isEqualTo(promoter.getName());
        assertThat(reload(UUID.fromString(pendingId)).getPriority()).isEqualTo(5);
    }

    @Test
    void aPromotersAdNeverGoesLiveUntargeted() {
        String adId = post(promoter, "/api/v1/promoter/ads", promoterAd("Wash")).text("id");

        Resp untarget = put(admin, "/api/v1/promotions/ads/" + adId, body("businessId", wash.getId().toString(),
                "titleEn", "Wash", "ctaType", "NONE", "propertyIds", List.of()));
        assertThat(untarget.status()).isEqualTo(400);

        jdbc.update("DELETE FROM promo_ad_properties WHERE ad_id = ?::uuid", adId);
        assertThat(approve(admin, adId).status()).isEqualTo(400);
    }

    @Test
    void theQueueCountAndFilterShowWhatWaits() {
        String pendingId = post(promoter, "/api/v1/promoter/ads", promoterAd("Waiting")).text("id");
        PromoAd live = ad(org, wash.getId(), APPROVED, null);
        PromoAd rejected = ad(org, wash.getId(), REJECTED, promoter.getId(), p1.getId());

        assertThat(get(admin, "/api/v1/promotions/ads/pending-count").body().get("count").asLong()).isEqualTo(1);
        JsonNode pending = get(admin, "/api/v1/promotions/ads?approvalStatus=PENDING").body().get("content");
        assertThat(ids(pending)).containsExactly(pendingId);
        assertThat(find(pending, pendingId).get("submittedByName").asText()).isEqualTo(promoter.getName());
        assertThat(ids(get(admin, "/api/v1/promotions/ads?approvalStatus=REJECTED").body().get("content")))
                .containsExactly(rejected.getId().toString());
        assertThat(ids(get(admin, "/api/v1/promotions/ads").body().get("content")))
                .contains(pendingId, live.getId().toString(), rejected.getId().toString());
        assertThat(get(admin, "/api/v1/promotions/ads?approvalStatus=LIVE").status()).isEqualTo(400);
    }

    @Test
    void anotherOrganisationsAdminCannotDecideOrCountOurAds() {
        String adId = post(promoter, "/api/v1/promoter/ads", promoterAd("Ours")).text("id");
        UUID orgB = org();
        User adminB = user(orgB, UserRole.TENANT_ADMIN);

        assertThat(approve(adminB, adId).status()).isEqualTo(404);
        assertThat(reject(adminB, adId, "no").status())
                .isEqualTo(404);
        assertThat(get(adminB, "/api/v1/promotions/ads/pending-count").body().get("count").asLong()).isZero();
        assertThat(reload(UUID.fromString(adId)).getApprovalStatus()).isEqualTo(PENDING);
    }

    @Test
    void onlyAdminsDecide() {
        String adId = post(promoter, "/api/v1/promoter/ads", promoterAd("Ours")).text("id");
        User manager = user(org, UserRole.PROPERTY_MANAGER);

        assertThat(approve(manager, adId).status()).isEqualTo(403);
        assertThat(approve(promoter, adId).status()).isEqualTo(403);
        assertThat(get(manager, "/api/v1/promotions/promoters").status()).isEqualTo(403);
    }

    // -------------------------------------------------------------- promoters

    @Test
    void anInviteCreatesAPromoterLoginWithItsAccess() {
        String email = "promoter-" + UUID.randomUUID() + "@example.test";
        Resp res = post(admin, "/api/v1/promotions/promoters", body("name", "QuickWash Team", "email", email,
                "phoneNumber", "+971 50 000 0000", "canCreateBusinesses", true,
                "businessIds", List.of(wash.getId().toString()), "propertyIds", List.of(p1.getId().toString())));

        assertThat(res.status()).isEqualTo(201);
        UUID userId = UUID.fromString(res.text("userId"));
        User created = userRepo.findById(userId).orElseThrow();
        assertThat(created.getRole()).isEqualTo(UserRole.PROMOTER);
        assertThat(created.getTenantId()).isEqualTo(org);
        assertThat(created.getEmail()).isEqualTo(email);
        assertThat(created.getInviteToken()).isNotNull();
        assertThat(membershipRepo.existsByUserIdAndTenantId(userId, org)).isTrue();

        JsonNode listed = find(get(admin, "/api/v1/promotions/promoters").body(), userId.toString(), "userId");
        assertThat(listed.get("name").asText()).isEqualTo("QuickWash Team");
        assertThat(listed.get("email").asText()).isEqualTo(email);
        assertThat(listed.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(listed.get("canCreateBusinesses").asBoolean()).isTrue();
        assertThat(texts(listed.get("businessIds"))).containsExactly(wash.getId().toString());
        assertThat(texts(listed.get("propertyIds"))).containsExactly(p1.getId().toString());
    }

    @Test
    void anEmailThatAlreadySignsInAnywhereIsRefused() {
        UUID elsewhere = org();
        User existing = user(elsewhere, UserRole.TENANT_USER);

        Resp res = post(admin, "/api/v1/promotions/promoters", body("name", "Someone",
                "email", "  " + existing.getEmail().toUpperCase() + " ", "canCreateBusinesses", false,
                "businessIds", List.of(), "propertyIds", List.of()));

        assertThat(res.status()).isEqualTo(400);
        assertThat(res.text("code")).isEqualTo("promoter.existingLogin");
    }

    @Test
    void foreignOrUnknownIdsAre404() {
        UUID orgB = org();
        PromoBusiness bizB = business(orgB, "Other");
        Property propB = property(orgB, "Other");
        String email = "promoter-" + UUID.randomUUID() + "@example.test";

        assertThat(post(admin, "/api/v1/promotions/promoters", body("name", "P", "email", email,
                "businessIds", List.of(bizB.getId().toString()), "propertyIds", List.of())).status()).isEqualTo(404);
        assertThat(post(admin, "/api/v1/promotions/promoters", body("name", "P", "email", email,
                "businessIds", List.of(), "propertyIds", List.of(propB.getId().toString()))).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email)).isZero();

        assertThat(put(admin, "/api/v1/promotions/promoters/" + promoter.getId(), body(
                "canCreateBusinesses", false, "businessIds", List.of(), "propertyIds",
                List.of(propB.getId().toString()))).status()).isEqualTo(404);
    }

    @Test
    void accessIsReplacedInFull() {
        Property p2 = property(org, "Creek");
        PromoBusiness other = business(org, "Spice");

        Resp res = put(admin, "/api/v1/promotions/promoters/" + promoter.getId(), body("canCreateBusinesses", true,
                "businessIds", List.of(other.getId().toString()), "propertyIds", List.of(p2.getId().toString())));

        assertThat(res.status()).isEqualTo(200);
        assertThat(get(promoter, "/api/v1/promoter/me").body().get("canCreateBusinesses").asBoolean()).isTrue();
        assertThat(get(promoter, "/api/v1/promoter/businesses").body().findValuesAsText("id"))
                .containsExactly(other.getId().toString());
        assertThat(get(promoter, "/api/v1/promoter/properties").body().findValuesAsText("id"))
                .containsExactly(p2.getId().toString());

        // Twice in a row (same keys re-inserted) is fine too.
        assertThat(put(admin, "/api/v1/promotions/promoters/" + promoter.getId(), body("canCreateBusinesses", true,
                "businessIds", List.of(other.getId().toString()), "propertyIds", List.of(p2.getId().toString())))
                .status()).isEqualTo(200);
    }

    @Test
    void onlyThisOrganisationsPromotersCanBeRescoped() {
        User staff = user(org, UserRole.TENANT_USER);
        UUID orgB = org();
        User promoterB = promoter(orgB, false, List.of(), List.of());
        Map<String, Object> none = body("canCreateBusinesses", false, "businessIds", List.of(), "propertyIds", List.of());

        assertThat(put(admin, "/api/v1/promotions/promoters/" + staff.getId(), none).status()).isEqualTo(404);
        assertThat(put(admin, "/api/v1/promotions/promoters/" + promoterB.getId(), none).status()).isEqualTo(404);
        assertThat(put(admin, "/api/v1/promotions/promoters/" + UUID.randomUUID(), none).status()).isEqualTo(404);
        assertThat(get(admin, "/api/v1/promotions/promoters").body().findValuesAsText("userId"))
                .contains(promoter.getId().toString())
                .doesNotContain(promoterB.getId().toString(), staff.getId().toString());
    }

    @Test
    void aDeactivatedPromoterIsListedInactive() {
        post(admin, "/api/admin/users/" + promoter.getId() + "/deactivate", null);

        JsonNode listed = find(get(admin, "/api/v1/promotions/promoters").body(), promoter.getId().toString(), "userId");
        assertThat(listed.get("status").asText()).isEqualTo("INACTIVE");
    }

    // -------------------------------------------------------------- helpers

    private Map<String, Object> promoterAd(String title) {
        return body("businessId", wash.getId().toString(), "titleEn", title, "ctaType", "NONE",
                "propertyIds", List.of(p1.getId().toString()), "redirectUrl", null, "redirectParams", List.of());
    }

    private static JsonNode find(JsonNode array, String id) {
        return find(array, id, "id");
    }

    private static JsonNode find(JsonNode array, String id, String field) {
        return StreamSupport.stream(array.spliterator(), false)
                .filter(n -> n.get(field).asText().equals(id)).findFirst().orElseThrow();
    }

    private static List<String> ids(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(n -> n.get("id").asText()).toList();
    }

    private static List<String> texts(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }
}
