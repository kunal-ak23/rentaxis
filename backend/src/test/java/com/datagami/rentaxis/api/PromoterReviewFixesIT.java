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

    @org.springframework.beans.factory.annotation.Autowired
    com.datagami.rentaxis.core.service.PromoAdApprovalService approvals;
    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.transaction.PlatformTransactionManager txManager;
    @org.springframework.beans.factory.annotation.Autowired
    com.datagami.rentaxis.core.service.PromoterAdminService promoterAdmin;

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

    // ------------------------------------------------------------------- I3

    /**
     * The reviewer's probe: an approval held open while the promoter edits the
     * business. The edit must queue behind the approval on the business row (not slip
     * past it), and once both commit the ad is back to PENDING — the business change
     * never goes live unreviewed.
     */
    @Test
    void i3_aBusinessEditWaitsForAnApprovalInFlightAndResubmitsAfterIt() throws Exception {
        PromoAd ad = ad(org, wash.getId(), PENDING, promoter.getId(), p1.getId());
        jdbc.update("UPDATE promo_ads SET submitted_at = now() - interval '1 minute' WHERE id = ?", ad.getId());
        java.time.Instant expected = java.time.Instant.parse(submittedAt(ad.getId()));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Resp>> edit =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean queuedOnBusiness = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            com.datagami.rentaxis.core.tenant.TenantContextHolder.setTenantId(org);
            new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(tx -> {
                approvals.approve(org, ad.getId(), admin.getId(), expected);
                edit.set(pool.submit(() -> put(promoter, "/api/v1/promoter/businesses/" + wash.getId(),
                        body("nameEn", "QuickWash v2 " + UUID.randomUUID()))));
                queuedOnBusiness.set(waitForLockWaitOn("promo_businesses"));
                assertThat(edit.get().isDone()).as("the edit must wait for the approval").isFalse();
            });
        } finally {
            com.datagami.rentaxis.core.tenant.TenantContextHolder.clear();
        }
        Resp res = edit.get().get(20, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(queuedOnBusiness.get()).as("the edit queued on the business row").isTrue();
        assertThat(res.status()).isEqualTo(200);
        PromoAd after = reload(ad.getId());
        assertThat(after.getApprovalStatus()).isEqualTo(PENDING);
        assertThat(after.getSubmittedAt()).isAfter(expected);
    }

    /** Whether, within 5 s, some session is waiting for a lock in a statement on {@code table}. */
    private boolean waitForLockWaitOn(String table) {
        long until = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < until) {
            // pg_stat_activity is snapshotted per transaction, and this runs inside the
            // approval's: drop the snapshot so each poll sees the sessions as they are now.
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            Integer waiting = jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND wait_event_type = 'Lock' AND query ILIKE ?", Integer.class, "%" + table + "%");
            if (waiting != null && waiting > 0) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------- M1

    /** M1: an archived ad of the business is re-submitted by a business edit too, so a restore brings it back PENDING. */
    @Test
    void m1_aRestoredAdDoesNotCarryUnreviewedBusinessChangesLive() {
        PromoAd ad = ad(org, wash.getId(), APPROVED, promoter.getId(), p1.getId());
        User renter = renterAt(org, p1);
        jdbc.update("INSERT INTO promo_ad_events (id, tenant_id, ad_id, renter_user_id, event_type, occurred_at, day) "
                + "VALUES (?, ?, ?, ?, 'IMPRESSION', now(), (now() AT TIME ZONE 'Asia/Dubai')::date)",
                UUID.randomUUID(), org, ad.getId(), renter.getId());
        assertThat(delete(admin, "/api/v1/promotions/ads/" + ad.getId()).text("outcome")).isEqualTo("ARCHIVED");

        put(promoter, "/api/v1/promoter/businesses/" + wash.getId(), body("nameEn", "QuickWash new " + UUID.randomUUID()));
        assertThat(post(admin, "/api/v1/promotions/ads/" + ad.getId() + "/restore", null).status()).isEqualTo(200);

        assertThat(reload(ad.getId()).getApprovalStatus()).isEqualTo(PENDING);
    }

    // ------------------------------------------------------------------- M2

    @Test
    void m2_aPromoterLearnsNothingFromATakenBusinessName() {
        String secret = "Secret Spa " + UUID.randomUUID().toString().substring(0, 6);
        assertThat(post(admin, "/api/v1/promotions/businesses", body("nameEn", secret)).status()).isEqualTo(201);

        Resp create = post(promoter, "/api/v1/promoter/businesses", body("nameEn", secret.toLowerCase()));
        assertThat(create.status()).isEqualTo(400);
        assertThat(create.text("message")).isEqualTo("That business name can't be used — choose another.");
        Resp rename = put(promoter, "/api/v1/promoter/businesses/" + wash.getId(), body("nameEn", secret));
        assertThat(rename.status()).isEqualTo(400);
        assertThat(rename.text("message")).doesNotContainIgnoringCase(secret);

        // An admin still gets the specific message.
        assertThat(post(admin, "/api/v1/promotions/businesses", body("nameEn", secret)).text("message"))
                .contains("already exists");
    }

    // ------------------------------------------------------------------- M3

    @Test
    void m3_oneBusinessEditIsOneNotificationPerAdmin() {
        ad(org, wash.getId(), APPROVED, null, p1.getId());
        ad(org, wash.getId(), APPROVED, null, p1.getId());
        ad(org, wash.getId(), PENDING, promoter.getId(), p1.getId());

        put(promoter, "/api/v1/promoter/businesses/" + wash.getId(), body("nameEn", "QuickWash m3 " + UUID.randomUUID()));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT message_key, params::text AS params FROM notifications WHERE user_id = ? AND type = 'PROMO_AD_SUBMITTED'",
                admin.getId());
        assertThat(rows).hasSize(1);
        assertThat(String.valueOf(rows.get(0).get("params"))).contains("\"adCount\": \"3\"");
    }

    // -------------------------------------------------------------- helpers

    private Map<String, Object> ad(String title) {
        return body("businessId", wash.getId().toString(), "titleEn", title, "ctaType", "NONE",
                "propertyIds", List.of(p1.getId().toString()), "redirectUrl", null, "redirectParams", List.of());
    }
}
