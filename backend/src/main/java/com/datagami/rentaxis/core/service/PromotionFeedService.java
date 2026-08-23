package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PromoAdCardDTO;
import com.datagami.rentaxis.api.dto.PromoBusinessRefDTO;
import com.datagami.rentaxis.api.dto.PromoEventBatchRequest;
import com.datagami.rentaxis.core.service.PromotionSlate.Candidate;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoAdEvent;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.enums.PromoCategory;
import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;
import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import com.datagami.rentaxis.domain.entity.enums.PromoPlacement;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PromoAdEventRepository;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What a renter actually sees. Resolves eligibility in SQL, then hands the
 * surviving ads to {@link PromotionSlate} for the home strip.
 */
@Service
@Transactional
public class PromotionFeedService {

    static final int HOME_SLATE_SIZE = 6;

    /**
     * Per ad, per renter, per day. Impressions are deduped by a unique index;
     * clicks deliberately are not, because a second tap is a real second tap.
     * Ten is far above any honest number of taps on one carousel card in a day.
     *
     * <p>This cap is <b>exact</b>, and the mechanism is worth knowing. It used
     * to be check-then-act under READ COMMITTED — concurrent requests all read
     * the same count, all passed, all inserted, so parallelism multiplied it.
     * A unique index, the way {@code uq_promo_impression_per_day} does it for
     * impressions, cannot fix that here: it would collapse the repeat clicks
     * the analytics exist to count. So the cap is a counter instead. Every
     * click spends one slot from a per (ad, renter, day) row in
     * {@code promo_ad_click_budgets}, claimed by {@link #RESERVE_CLICK_SQL} —
     * two upserts on one key serialize on that row inside Postgres, so the
     * eleventh claim re-reads the committed count and updates nothing. Counter
     * and events are written in the same transaction, so a rollback returns the
     * slots with the clicks.
     *
     * <p>The 20/min bucket for {@code POST /api/v1/promotions/events} in
     * {@code PublicRateLimitFilter} is still worth keeping — it bounds how much
     * work an abusive client can make the server do — but the cap no longer
     * leans on it for correctness, so its own comment now overstates its job.
     */
    static final int MAX_CLICKS_PER_AD_PER_DAY = 10;

    /**
     * Claims one click slot, atomically. Returns 1 row affected if the slot was
     * granted and 0 if the day's allowance is already spent.
     *
     * <p>The {@code WHERE} on {@code DO UPDATE} is the whole enforcement: a
     * refusal is zero rows updated, not an error, so a capped click costs the
     * batch nothing — unlike the impression index, which fails at commit and
     * would take the batch's other rows down with it. Postgres re-checks that
     * predicate against the row as it stands after any conflicting transaction
     * commits, which is exactly the read the old in-Java count could not make.
     *
     * <p>The tenant predicate is belt and braces. {@code ad_id} already resolves
     * to one tenant, and {@code recordEvents} only ever passes ads that survived
     * a tenant-scoped eligibility query, but this statement is native SQL and so
     * runs outside Hibernate's {@code tenantFilter} — the isolation has to be
     * written out rather than inherited.
     */
    private static final String RESERVE_CLICK_SQL = """
            INSERT INTO promo_ad_click_budgets (tenant_id, ad_id, renter_user_id, day, clicks)
            VALUES (:tenantId, :adId, :renterUserId, :day, 1)
            ON CONFLICT (ad_id, renter_user_id, day) DO UPDATE
               SET clicks = promo_ad_click_budgets.clicks + 1,
                   updated_at = now()
             WHERE promo_ad_click_budgets.clicks < :cap
               AND promo_ad_click_budgets.tenant_id = :tenantId
            """;

    /** The product's timezone. The database's is not necessarily the same. */
    static final ZoneId DUBAI = ZoneId.of("Asia/Dubai");

    /**
     * Stand-in property id for a renter with no active lease.
     *
     * <p>Not a workaround for a provider limitation: Hibernate 6+ renders an
     * empty {@code IN} as {@code 1=0}, which would behave correctly here. It is
     * for readability at the call site — the untargeted {@code NOT EXISTS} arm
     * still has to evaluate for these renters, and a named sentinel says so
     * where an empty list would just look like a missing guard.
     */
    private static final UUID NO_PROPERTY = new UUID(0L, 0L);

    private static final List<PromoPlacement> HOME_ONLY = List.of(PromoPlacement.HOME_AND_OFFERS);
    private static final List<PromoPlacement> ALL_PLACEMENTS =
            List.of(PromoPlacement.HOME_AND_OFFERS, PromoPlacement.OFFERS_ONLY);

    private final PromoAdRepository adRepository;
    private final PromoBusinessRepository businessRepository;
    private final PromoAdEventRepository eventRepository;
    private final LeaseRepository leaseRepository;
    private final EntityManager entityManager;

    public PromotionFeedService(PromoAdRepository adRepository,
                                PromoBusinessRepository businessRepository,
                                PromoAdEventRepository eventRepository,
                                LeaseRepository leaseRepository,
                                EntityManager entityManager) {
        this.adRepository = adRepository;
        this.businessRepository = businessRepository;
        this.eventRepository = eventRepository;
        this.leaseRepository = leaseRepository;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public List<PromoAdCardDTO> homeFeed(UUID tenantId, UUID renterUserId) {
        List<PromoAd> eligible = eligible(tenantId, renterUserId, HOME_ONLY);
        if (eligible.isEmpty()) {
            return List.of();
        }
        List<UUID> slate = PromotionSlate.pick(
                eligible.stream().map(a -> new Candidate(a.getId(), a.getPriority())).toList(),
                renterUserId, LocalDate.now(DUBAI), HOME_SLATE_SIZE);

        Map<UUID, PromoAd> byId = eligible.stream()
                .collect(Collectors.toMap(PromoAd::getId, Function.identity()));
        // requireNonNull, not filter(nonNull): pick() only ever returns ids from
        // the candidates we just built the map from, so a miss is a broken
        // invariant, not a case to silently serve a five-card slate for.
        List<PromoAd> ordered = slate.stream()
                .map(id -> java.util.Objects.requireNonNull(
                        byId.get(id), "slate returned an unknown ad id"))
                .toList();
        return toCards(tenantId, ordered);
    }

    /** Every eligible ad, newest business content last, optionally category-filtered. */
    @Transactional(readOnly = true)
    public List<PromoAdCardDTO> offers(UUID tenantId, UUID renterUserId, PromoCategory category) {
        List<PromoAd> eligible = eligible(tenantId, renterUserId, ALL_PLACEMENTS);
        List<PromoAdCardDTO> cards = toCards(tenantId, eligible);
        if (category == null) {
            return cards;
        }
        return cards.stream().filter(c -> c.business().category() == category).toList();
    }

    /**
     * Ignores unknown or ineligible ad ids rather than rejecting the batch — a
     * stale flush from a backgrounded app must never surface an error to the
     * renter. Ad ids are re-derived from {@link #eligible} rather than trusted,
     * so a renter cannot record an event against an ad they were never served.
     *
     * <p>Impressions this renter already has today are filtered out BEFORE the
     * insert: the partial unique index only fires at commit, where the
     * transaction is already rollback-only and no catch could save the batch's
     * clicks. Clicks take the opposite route — each one claims a slot from the
     * counter row up front (see {@link #MAX_CLICKS_PER_AD_PER_DAY}), where a
     * refusal is zero rows updated rather than an error — so the cap is decided
     * by the database without ever putting the batch at risk. Two simultaneous
     * batches serialize on that counter row instead of racing past a read.
     */
    public void recordEvents(UUID tenantId, UUID renterUserId, PromoEventBatchRequest batch) {
        if (batch == null || batch.events() == null || batch.events().isEmpty()) {
            return;
        }
        // ONE instant for both fields. ck_promo_ad_event_day asserts
        // day = (occurred_at AT TIME ZONE 'Asia/Dubai')::date, so computing them
        // separately with a DB round trip in between lets a batch straddling
        // Dubai midnight violate the CHECK and 500 the whole request.
        Instant now = Instant.now();
        LocalDate day = LocalDate.ofInstant(now, DUBAI);

        Set<UUID> allowed = eligible(tenantId, renterUserId, ALL_PLACEMENTS).stream()
                .map(PromoAd::getId)
                .collect(Collectors.toSet());

        List<UUID> touched = batch.events().stream()
                .filter(e -> e != null && e.adId() != null && allowed.contains(e.adId()))
                .map(PromoEventBatchRequest.Event::adId)
                .distinct()
                .toList();
        if (touched.isEmpty()) {
            return;
        }

        // Only impressions are read back. The click count that used to be taken
        // from this query is now the counter row's business — reading it here as
        // well would put a second, staler answer next to the authoritative one,
        // and the first thing a future reader would ask is which one wins.
        Map<UUID, Long> impressionsToday = new HashMap<>();
        for (Object[] row : eventRepository.countTodaysEventsByAd(
                tenantId, touched, renterUserId, day)) {
            if ((PromoEventType) row[1] == PromoEventType.IMPRESSION) {
                impressionsToday.put((UUID) row[0], (Long) row[2]);
            }
        }

        // Written as a loop rather than a stream: the click cap needs running
        // per-ad state, and a stream whose filter mutates a map is the kind of
        // clever that hides an off-by-one.
        List<PromoAdEvent> rows = new ArrayList<>();
        Set<UUID> impressionsInBatch = new HashSet<>();
        Set<UUID> exhausted = new HashSet<>();

        for (PromoEventBatchRequest.Event e : batch.events()) {
            if (e == null || e.adId() == null || e.type() == null
                    || !allowed.contains(e.adId())) {
                continue;
            }
            if (e.type() == PromoEventType.IMPRESSION) {
                // add() first, so a repeat within the batch short-circuits too.
                if (!impressionsInBatch.add(e.adId())
                        || impressionsToday.getOrDefault(e.adId(), 0L) > 0) {
                    continue;
                }
            } else {
                // One statement per click, and `exhausted` is what keeps that
                // honest: the counter only ever climbs inside this transaction,
                // so once an ad is refused every later click for it in the same
                // batch is refused too. A 50-event batch (the DTO's @Size cap)
                // aimed at one ad therefore costs at most eleven round trips,
                // not fifty — ten grants and the refusal that ends them.
                if (exhausted.contains(e.adId())) {
                    continue;
                }
                if (!reserveClick(tenantId, e.adId(), renterUserId, day)) {
                    exhausted.add(e.adId());
                    continue;
                }
            }
            PromoAdEvent row = new PromoAdEvent();
            row.setTenantId(tenantId);
            row.setAdId(e.adId());
            row.setRenterUserId(renterUserId);
            row.setEventType(e.type());
            row.setOccurredAt(now);
            row.setDay(day);
            rows.add(row);
        }

        if (!rows.isEmpty()) {
            eventRepository.saveAll(rows);
        }
    }

    /**
     * Spends one of today's click slots for this ad and renter, and reports
     * whether the database granted it. See {@link #RESERVE_CLICK_SQL}.
     *
     * <p>{@code day} is passed in rather than read from the clock so the counter
     * key and {@code PromoAdEvent.day} are stamped from the same instant — a
     * batch straddling Dubai midnight must not spend one day's budget and be
     * filed under the next, which is the same hazard {@code ck_promo_ad_event_day}
     * exists to catch.
     */
    private boolean reserveClick(UUID tenantId, UUID adId, UUID renterUserId, LocalDate day) {
        return entityManager.createNativeQuery(RESERVE_CLICK_SQL)
                .setParameter("tenantId", tenantId)
                .setParameter("adId", adId)
                .setParameter("renterUserId", renterUserId)
                .setParameter("day", day)
                .setParameter("cap", MAX_CLICKS_PER_AD_PER_DAY)
                .executeUpdate() > 0;
    }

    private List<PromoAd> eligible(UUID tenantId, UUID renterUserId, List<PromoPlacement> placements) {
        List<UUID> propertyIds =
                leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterUserId);
        if (propertyIds.isEmpty()) {
            propertyIds = List.of(NO_PROPERTY);
        }
        return adRepository.findEligible(tenantId, Instant.now(), placements, propertyIds);
    }

    /**
     * Scoped by the CALLER's tenant, not by the tenant on the rows. Reading it
     * off {@code ads.get(0)} would scope the lookup to whatever tenant the data
     * claims, which is precisely the property
     * {@code findByTenantIdAndIdIn} exists to prevent — the boundary belongs in
     * the signature, answerable to who is asking.
     */
    private List<PromoAdCardDTO> toCards(UUID tenantId, List<PromoAd> ads) {
        if (ads.isEmpty()) {
            return List.of();
        }
        List<UUID> businessIds = ads.stream().map(PromoAd::getBusinessId).distinct().toList();
        Map<UUID, PromoBusiness> businesses = new LinkedHashMap<>();
        for (PromoBusiness b : businessRepository.findByTenantIdAndIdIn(
                tenantId, businessIds)) {
            businesses.put(b.getId(), b);
        }
        return ads.stream()
                .map(a -> toCard(a, businesses.get(a.getBusinessId())))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private PromoAdCardDTO toCard(PromoAd a, PromoBusiness b) {
        if (b == null) {
            return null;
        }
        String phone = switch (a.getCtaType()) {
            case CALL -> b.getPhoneE164();
            case WHATSAPP -> b.getWhatsappE164();
            default -> null;
        };
        return new PromoAdCardDTO(
                a.getId(),
                new PromoBusinessRefDTO(b.getId(), b.getNameEn(), b.getNameAr(),
                        b.getLogoUrl(), b.getCategory()),
                a.getTitleEn(), a.getTitleAr(),
                a.getSubtitleEn(), a.getSubtitleAr(),
                a.getBackgroundImageUrl(), a.getAccentColor(),
                a.getCtaType(), a.getCtaLabelEn(), a.getCtaLabelAr(),
                a.getCtaType() == PromoCtaType.WEBSITE ? a.getCtaUrl() : null,
                phone,
                a.getCouponCode(), a.getCouponTermsEn(), a.getCouponTermsAr(),
                a.getEndsAt());
    }
}
