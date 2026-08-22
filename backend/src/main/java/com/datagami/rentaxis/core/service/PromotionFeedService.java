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
     * clicks deliberately are not, because a second tap is a real second tap —
     * which leaves nothing bounding them. The endpoint is authenticated but
     * unthrottled (PublicRateLimitFilter does not cover /api/v1/promotions),
     * so without a ceiling one renter could write clicks in a loop and set the
     * tap count the client is shown to justify an ad slot. Ten is far above
     * any honest number of taps on one carousel card in a day.
     */
    static final int MAX_CLICKS_PER_AD_PER_DAY = 10;

    /** The product's timezone. The database's is not necessarily the same. */
    static final ZoneId DUBAI = ZoneId.of("Asia/Dubai");

    /**
     * Stand-in property id for a renter with no active lease. An empty
     * {@code IN} list is invalid JPQL on PostgreSQL, and the untargeted arm of
     * the eligibility query still has to run for these renters.
     */
    private static final UUID NO_PROPERTY = new UUID(0L, 0L);

    private static final List<PromoPlacement> HOME_ONLY = List.of(PromoPlacement.HOME_AND_OFFERS);
    private static final List<PromoPlacement> ALL_PLACEMENTS =
            List.of(PromoPlacement.HOME_AND_OFFERS, PromoPlacement.OFFERS_ONLY);

    private final PromoAdRepository adRepository;
    private final PromoBusinessRepository businessRepository;
    private final PromoAdEventRepository eventRepository;
    private final LeaseRepository leaseRepository;

    public PromotionFeedService(PromoAdRepository adRepository,
                                PromoBusinessRepository businessRepository,
                                PromoAdEventRepository eventRepository,
                                LeaseRepository leaseRepository) {
        this.adRepository = adRepository;
        this.businessRepository = businessRepository;
        this.eventRepository = eventRepository;
        this.leaseRepository = leaseRepository;
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
        return toCards(ordered);
    }

    /** Every eligible ad, newest business content last, optionally category-filtered. */
    @Transactional(readOnly = true)
    public List<PromoAdCardDTO> offers(UUID tenantId, UUID renterUserId, PromoCategory category) {
        List<PromoAd> eligible = eligible(tenantId, renterUserId, ALL_PLACEMENTS);
        List<PromoAdCardDTO> cards = toCards(eligible);
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
     * clicks. Clicks are capped at {@link #MAX_CLICKS_PER_AD_PER_DAY}. Both
     * numbers come from one grouped query. Two simultaneous batches can still
     * race past the check; that lone failed request is accepted — the client
     * fires and forgets.
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

        Map<UUID, Long> impressionsToday = new HashMap<>();
        Map<UUID, Long> clicksToday = new HashMap<>();
        for (Object[] row : eventRepository.countTodaysEventsByAd(
                tenantId, touched, renterUserId, day)) {
            UUID adId = (UUID) row[0];
            PromoEventType type = (PromoEventType) row[1];
            long count = (Long) row[2];
            if (type == PromoEventType.IMPRESSION) {
                impressionsToday.put(adId, count);
            } else {
                clicksToday.put(adId, count);
            }
        }

        // Written as a loop rather than a stream: the click cap needs running
        // per-ad state, and a stream whose filter mutates a map is the kind of
        // clever that hides an off-by-one.
        List<PromoAdEvent> rows = new ArrayList<>();
        Set<UUID> impressionsInBatch = new HashSet<>();
        Map<UUID, Long> clicksInBatch = new HashMap<>();

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
                long already = clicksToday.getOrDefault(e.adId(), 0L)
                        + clicksInBatch.getOrDefault(e.adId(), 0L);
                if (already >= MAX_CLICKS_PER_AD_PER_DAY) {
                    continue;
                }
                clicksInBatch.merge(e.adId(), 1L, Long::sum);
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

    private List<PromoAd> eligible(UUID tenantId, UUID renterUserId, List<PromoPlacement> placements) {
        List<UUID> propertyIds =
                leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterUserId);
        if (propertyIds.isEmpty()) {
            propertyIds = List.of(NO_PROPERTY);
        }
        return adRepository.findEligible(tenantId, Instant.now(), placements, propertyIds);
    }

    private List<PromoAdCardDTO> toCards(List<PromoAd> ads) {
        if (ads.isEmpty()) {
            return List.of();
        }
        List<UUID> businessIds = ads.stream().map(PromoAd::getBusinessId).distinct().toList();
        Map<UUID, PromoBusiness> businesses = new LinkedHashMap<>();
        for (PromoBusiness b : businessRepository.findByTenantIdAndIdIn(
                ads.get(0).getTenantId(), businessIds)) {
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
