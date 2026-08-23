package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PromoAdStatsDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import com.datagami.rentaxis.domain.repository.PromoAdEventRepository;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Impression and click aggregates for the admin panel — the numbers a client
 * reads to decide whether an ad slot is working.
 *
 * <p><b>Known scaling limit.</b> Neither query is windowed, so a page of the
 * admin ad list aggregates every event those ads have ever produced. At the
 * launch shape (~40 ads, a few thousand renters) that is a few hundred
 * thousand rows and fine. It grows without bound, and there is no retention
 * policy, so before this feature has been live a year it wants a date window
 * plus an index on {@code (tenant_id, ad_id, event_type)} — today only
 * {@code idx_pae_ad_day} exists and {@code tenant_id}/{@code event_type} are
 * heap filters.
 */
@Service
@Transactional(readOnly = true)
public class PromotionStatsService {

    /**
     * Raw counts plus the distinct-renter counts the rate is derived from.
     * {@code impressions} and {@code clicks} are what the panel displays;
     * {@code viewers} and {@code clickers} exist only to make the rate honest.
     */
    public record Totals(long impressions, long clicks, long viewers, long clickers) {

        /** Public: the admin controller in another package needs the zero value. */
        public static final Totals EMPTY = new Totals(0, 0, 0, 0);

        /**
         * Distinct clickers over distinct viewers. Clamped at 1: a renter whose
         * impression flush was lost but whose click landed would otherwise push
         * this above 100%, and a tap rate over 100% reads as a broken dashboard
         * rather than as the edge case it is.
         */
        public double tapThroughRate() {
            return viewers == 0 ? 0d : Math.min(1d, (double) clickers / viewers);
        }
    }

    /**
     * Days of daily series returned, counting back from the most recent event.
     * The headline totals are lifetime; this bounds only the chart.
     */
    static final int SERIES_WINDOW_DAYS = 90;

    private final PromoAdEventRepository eventRepository;
    private final PromoAdRepository adRepository;

    public PromotionStatsService(PromoAdEventRepository eventRepository,
                                 PromoAdRepository adRepository) {
        this.eventRepository = eventRepository;
        this.adRepository = adRepository;
    }

    /** One query for a whole page of ads, not two per row. */
    public Map<UUID, Totals> totals(UUID tenantId, List<UUID> adIds) {
        if (adIds.isEmpty()) {
            return Map.of();
        }
        // long[]{impressions, clicks, viewers, clickers}, accumulated per ad —
        // per ad, not shared, or one business's numbers land on another's row.
        Map<UUID, long[]> acc = new HashMap<>();
        for (Object[] row : eventRepository.countByAdIdIn(tenantId, adIds)) {
            UUID adId = (UUID) row[0];
            PromoEventType type = (PromoEventType) row[1];
            long count = (Long) row[2];
            long distinct = (Long) row[3];
            long[] slot = acc.computeIfAbsent(adId, k -> new long[4]);
            if (type == PromoEventType.IMPRESSION) {
                slot[0] += count;
                slot[2] += distinct;
            } else if (type == PromoEventType.CLICK) {
                // Explicitly CLICK, not `else`: a future event type must be
                // ignored rather than silently booked as a tap.
                slot[1] += count;
                slot[3] += distinct;
            }
        }
        Map<UUID, Totals> out = new HashMap<>();
        acc.forEach((adId, v) -> out.put(adId, new Totals(v[0], v[1], v[2], v[3])));
        return out;
    }

    /**
     * One ad's detail view.
     *
     * <p>The headline totals and the daily series come from two statements, so
     * under READ COMMITTED an event landing between them can make the headline
     * and the chart differ by one. Accepted: the alternative is deriving the
     * headline from the series, which would lose the distinct-renter counts the
     * rate needs, since distinct renters per day do not sum to distinct renters
     * overall.
     */
    public PromoAdStatsDTO stats(UUID tenantId, UUID adId) {
        // 404 rather than a convincing page of zeros. A stale bookmark or a
        // mistyped id should say "gone", not "this campaign performed terribly".
        PromoAd ad = adRepository.findById(adId)
                .filter(a -> Objects.equals(a.getTenantId(), tenantId))
                .orElseThrow(() -> new NotFoundException("Ad not found"));

        Totals t = totals(tenantId, List.of(ad.getId())).getOrDefault(ad.getId(), Totals.EMPTY);

        Map<LocalDate, long[]> byDay = new TreeMap<>();
        for (Object[] row : eventRepository.dailySeries(tenantId, ad.getId())) {
            LocalDate day = (LocalDate) row[0];
            PromoEventType type = (PromoEventType) row[1];
            long count = (Long) row[2];
            long[] pair = byDay.computeIfAbsent(day, k -> new long[2]);
            if (type == PromoEventType.IMPRESSION) {
                pair[0] += count;
            } else if (type == PromoEventType.CLICK) {
                pair[1] += count;
            }
        }

        return new PromoAdStatsDTO(ad.getId(), t.impressions(), t.clicks(),
                t.tapThroughRate(), zeroFilled(byDay));
    }

    /**
     * Days with no events produce no row, which would make a line chart
     * interpolate straight across a dead week instead of dipping to zero —
     * visually inflating a period where the ad served nothing. Same reasoning as
     * {@code DashboardService.getMonthlyCollections}, which zero-fills its
     * calendar for exactly this.
     */
    private List<PromoAdStatsDTO.DayPoint> zeroFilled(Map<LocalDate, long[]> byDay) {
        List<PromoAdStatsDTO.DayPoint> series = new ArrayList<>();
        if (byDay.isEmpty()) {
            return series;
        }
        TreeMap<LocalDate, long[]> sorted = new TreeMap<>(byDay);
        LocalDate last = sorted.lastKey();
        // Bounded, because zero-filling makes the series scale with the ad's AGE
        // rather than its activity: an ad that ran for a week and then took one
        // impression a year later would otherwise return ~370 points, nearly all
        // zeros, on every load of its detail view. The headline totals above stay
        // lifetime; only the chart is windowed.
        LocalDate first = sorted.firstKey();
        LocalDate windowStart = last.minusDays(SERIES_WINDOW_DAYS - 1L);
        LocalDate from = first.isAfter(windowStart) ? first : windowStart;
        for (LocalDate d = from; !d.isAfter(last); d = d.plusDays(1)) {
            long[] pair = sorted.getOrDefault(d, new long[2]);
            series.add(new PromoAdStatsDTO.DayPoint(d, pair[0], pair[1]));
        }
        return series;
    }
}
