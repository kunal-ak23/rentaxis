package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PromoEventBatchRequest;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import com.datagami.rentaxis.domain.entity.enums.PromoPlacement;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PromoAdEventRepository;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link PromotionFeedService#recordEvents} against a real
 * Postgres — the property the mocked unit tests structurally cannot see, because
 * it lives in the database rather than in the service's control flow: that
 * {@link PromotionFeedService#MAX_CLICKS_PER_AD_PER_DAY} holds when the requests
 * arrive at the same instant.
 *
 * <p>The mocked test {@code recordEvents_capsClicksPerAdPerDay} exercises one
 * thread against a stubbed count and passes on the old check-then-act code as
 * happily as on the new one. It has to: under READ COMMITTED the old read saw a
 * consistent snapshot per request, so the single-threaded path was never the
 * broken one. Only concurrency separates "the service counts before it writes"
 * from "the database refuses the eleventh slot".
 *
 * <p>The pool is sized above the thread count on purpose. Hikari's default 20
 * would throttle the burst into batches and let a check-then-act implementation
 * land a plausible-looking total by accident; with a connection each, all 32
 * transactions really are in flight together.
 *
 * <p>Requires Docker on the host.
 */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=40")
class PromotionClickCapConcurrencyIT extends AbstractPostgresIT {

    private static final ZoneId DUBAI = ZoneId.of("Asia/Dubai");
    private static final int CONCURRENT_CLICKS = 32;

    @Autowired PromotionFeedService feedService;
    @Autowired PromoAdRepository adRepository;
    @Autowired PromoBusinessRepository businessRepository;
    @Autowired PromoAdEventRepository eventRepository;
    @Autowired LandlordOrgRepository landlordOrgRepository;
    @Autowired UserRepository userRepository;

    private UUID tenantId;
    private UUID renterId;
    private UUID adId;

    @BeforeEach
    void setUp() {
        // Each test gets its own tenant; multi-tenant isolation means no cleanup
        // between tests is needed.
        LandlordOrg org = new LandlordOrg();
        org.setName("IT-Tenant-" + UUID.randomUUID());
        org = landlordOrgRepository.save(org);
        this.tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);

        // promo_ad_events.renter_user_id is a real FK to users.
        renterId = seedRenter();
        adId = seedAd();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    /**
     * The test this task rests on: 32 renters' worth of simultaneous taps on one
     * card must leave exactly {@link PromotionFeedService#MAX_CLICKS_PER_AD_PER_DAY}
     * click rows, not 32.
     *
     * <p>Under the old check-then-act read, every transaction saw a count of zero
     * before any of them committed, every one passed the guard, and every one
     * inserted — so the cap scaled with parallelism instead of bounding it. With
     * the counter row the conflicting upserts serialize inside Postgres and the
     * eleventh claim updates nothing.
     */
    @Test
    void concurrentClicksOnOneAd_exactlyTheCapLands() throws Exception {
        int landed = fireConcurrentClicks(renterId, CONCURRENT_CLICKS);

        assertThat(landed)
                .as("%d simultaneous clicks on one ad must leave exactly %d rows",
                        CONCURRENT_CLICKS, PromotionFeedService.MAX_CLICKS_PER_AD_PER_DAY)
                .isEqualTo(PromotionFeedService.MAX_CLICKS_PER_AD_PER_DAY);
        assertThat(clicksToday(adId, renterId))
                .isEqualTo(PromotionFeedService.MAX_CLICKS_PER_AD_PER_DAY);
    }

    /**
     * The cap must bound clicks, not collapse them. A unique index would have
     * been the obvious way to make the count exact and is exactly the wrong
     * instrument here — {@code uq_promo_impression_per_day} deliberately excludes
     * clicks, because a second tap is a real second tap and the tap rate the
     * client is billed against depends on it.
     */
    @Test
    void repeatClicksBelowTheCapAreNotDeduplicated() {
        feedService.recordEvents(tenantId, renterId, clicks(3));

        assertThat(clicksToday(adId, renterId))
                .as("three taps are three events; the cap bounds them, it must not dedupe them")
                .isEqualTo(3);
    }

    /**
     * The budget is keyed per (ad, renter, day), so one renter exhausting an ad
     * must not silence anybody else's taps on it. A counter keyed on the ad alone
     * would pass the concurrency test above and break this one.
     */
    @Test
    void oneRenterExhaustingTheCapDoesNotSpendAnotherRentersBudget() throws Exception {
        UUID otherRenter = seedRenter();

        fireConcurrentClicks(renterId, CONCURRENT_CLICKS);
        feedService.recordEvents(tenantId, otherRenter, clicks(2));

        assertThat(clicksToday(adId, renterId))
                .isEqualTo(PromotionFeedService.MAX_CLICKS_PER_AD_PER_DAY);
        assertThat(clicksToday(adId, otherRenter)).isEqualTo(2);
    }

    /**
     * Clicks and impressions draw on separate budgets. Spending the click cap
     * must not stop the day's impression from being recorded — the tap rate's
     * denominator would quietly go missing.
     */
    @Test
    void exhaustingTheClickCapStillLeavesTheImpressionRecordable() throws Exception {
        fireConcurrentClicks(renterId, CONCURRENT_CLICKS);

        feedService.recordEvents(tenantId, renterId, new PromoEventBatchRequest(
                List.of(new PromoEventBatchRequest.Event(adId, PromoEventType.IMPRESSION))));

        assertThat(countToday(adId, renterId, PromoEventType.IMPRESSION)).isEqualTo(1);
        assertThat(clicksToday(adId, renterId))
                .isEqualTo(PromotionFeedService.MAX_CLICKS_PER_AD_PER_DAY);
    }

    @Test
    void opposingBatchOrdersFromOneRenterDoNotDeadlock() throws Exception {
        // Every click locks its (ad, renter, day) counter row until commit. If
        // the slots are claimed in the order the CLIENT listed them, a renter
        // with the app open on two devices can send [adA, adB] and [adB, adA]
        // at once, each hold one row, and Postgres breaks the cycle by killing
        // one transaction -- taking that batch's impressions with it, which are
        // the denominator the tap rate is billed against. The renter app clears
        // its queue before the POST and swallows the error, so the batch is
        // gone silently.
        //
        // The other tests in this class all hammer a single ad, which is
        // exactly why none of them can see this.
        UUID adB = seedAd();
        int pairs = 8;

        CountDownLatch ready = new CountDownLatch(pairs * 2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(pairs * 2);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < pairs * 2; i++) {
                boolean forward = i % 2 == 0;
                UUID first = forward ? adId : adB;
                UUID second = forward ? adB : adId;
                futures.add(pool.submit(() -> {
                    TenantContextHolder.setTenantId(tenantId);
                    try {
                        ready.countDown();
                        go.await(20, TimeUnit.SECONDS);
                        feedService.recordEvents(tenantId, renterId, new PromoEventBatchRequest(
                                List.of(new PromoEventBatchRequest.Event(first, PromoEventType.CLICK),
                                        new PromoEventBatchRequest.Event(second, PromoEventType.CLICK))));
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        TenantContextHolder.clear();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<Void> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        assertThat(failures)
                .describedAs("no batch may be lost to a lock cycle; got: %s", failures)
                .isEmpty();

        // And the cap still holds on both ads despite the contention.
        assertThat(clicksToday(adId, renterId)).isEqualTo(PromotionFeedService.MAX_CLICKS_PER_AD_PER_DAY);
        assertThat(clicksToday(adB, renterId)).isEqualTo(PromotionFeedService.MAX_CLICKS_PER_AD_PER_DAY);
    }

    /** Fires {@code count} single-click batches from {@code count} threads at once. */
    private int fireConcurrentClicks(UUID renterUserId, int count) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(clickTask(ready, go, renterUserId)));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<Void> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }
        return (int) clicksToday(adId, renterUserId);
    }

    private Callable<Void> clickTask(CountDownLatch ready, CountDownLatch go, UUID renterUserId) {
        return () -> {
            TenantContextHolder.setTenantId(tenantId);
            try {
                ready.countDown();
                go.await(20, TimeUnit.SECONDS);
                feedService.recordEvents(tenantId, renterUserId, clicks(1));
                return null;
            } finally {
                TenantContextHolder.clear();
            }
        };
    }

    private PromoEventBatchRequest clicks(int howMany) {
        List<PromoEventBatchRequest.Event> events = new ArrayList<>();
        for (int i = 0; i < howMany; i++) {
            events.add(new PromoEventBatchRequest.Event(adId, PromoEventType.CLICK));
        }
        return new PromoEventBatchRequest(events);
    }

    private long clicksToday(UUID ad, UUID renterUserId) {
        return countToday(ad, renterUserId, PromoEventType.CLICK);
    }

    private long countToday(UUID ad, UUID renterUserId, PromoEventType type) {
        return eventRepository
                .countTodaysEventsByAd(tenantId, List.of(ad), renterUserId, LocalDate.now(DUBAI))
                .stream()
                .filter(row -> row[1] == type)
                .mapToLong(row -> (Long) row[2])
                .sum();
    }

    private UUID seedRenter() {
        User user = new User();
        user.setTenantId(tenantId);
        user.setEmail("renter-" + UUID.randomUUID() + "@it.test");
        user.setPasswordHash("x");
        user.setName("IT Renter");
        user.setRole(UserRole.RENTER);
        return userRepository.save(user).getId();
    }

    /** An ad with no promo_ad_properties rows targets every property, so no lease is needed. */
    private UUID seedAd() {
        PromoBusiness business = new PromoBusiness();
        business.setTenantId(tenantId);
        business.setNameEn("IT Business " + UUID.randomUUID());
        business = businessRepository.save(business);

        PromoAd ad = new PromoAd();
        ad.setTenantId(tenantId);
        ad.setBusinessId(business.getId());
        ad.setTitleEn("IT Offer");
        ad.setPriority(1);
        ad.setPlacement(PromoPlacement.HOME_AND_OFFERS);
        return adRepository.save(ad).getId();
    }
}
