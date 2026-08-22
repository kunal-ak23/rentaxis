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
        List<PromoAd> ordered = slate.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
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
     * renter. Impressions this renter already has on record today are filtered
     * out BEFORE the insert in one batched lookup: the partial unique index
     * only fires at commit,
     * where the transaction is already rollback-only and no catch could save
     * the batch (its clicks included). Two simultaneous batches can still race
     * past the exists-check; that lone failed request is accepted — the client
     * fires and forgets.
     */
    public void recordEvents(UUID tenantId, UUID renterUserId, PromoEventBatchRequest batch) {
        List<UUID> allowed = eligible(tenantId, renterUserId, ALL_PLACEMENTS).stream()
                .map(PromoAd::getId).toList();
        LocalDate day = LocalDate.now(DUBAI);

        // One query for the whole batch rather than one per ad. This runs on
        // every home-screen load with up to six impressions, so a per-ad check
        // would be six round trips each time, all day, for a result that is
        // already-seen every time after the first load.
        List<UUID> candidates = batch.events().stream()
                .filter(e -> e.type() == PromoEventType.IMPRESSION && allowed.contains(e.adId()))
                .map(PromoEventBatchRequest.Event::adId)
                .distinct()
                .toList();
        Set<UUID> seenToday = candidates.isEmpty()
                ? Set.of()
                : new HashSet<>(eventRepository.findAdIdsWithImpressionOn(
                        tenantId, candidates, renterUserId, day));

        // Also guards the same ad appearing twice within one batch — seenToday
        // only knows about committed rows.
        Set<UUID> impressionsInBatch = new HashSet<>();

        List<PromoAdEvent> rows = batch.events().stream()
                .filter(e -> allowed.contains(e.adId()))
                .filter(e -> e.type() != PromoEventType.IMPRESSION
                        || (impressionsInBatch.add(e.adId()) && !seenToday.contains(e.adId())))
                .map(e -> {
                    PromoAdEvent row = new PromoAdEvent();
                    row.setTenantId(tenantId);
                    row.setAdId(e.adId());
                    row.setRenterUserId(renterUserId);
                    row.setEventType(e.type());
                    row.setOccurredAt(Instant.now());
                    row.setDay(day);
                    return row;
                })
                .toList();

        if (rows.isEmpty()) {
            return;
        }
        eventRepository.saveAll(rows);
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
        for (PromoBusiness b : businessRepository.findByIdIn(businessIds)) {
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
