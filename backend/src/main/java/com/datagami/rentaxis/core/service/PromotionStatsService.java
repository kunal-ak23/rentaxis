package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PromoAdStatsDTO;
import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import com.datagami.rentaxis.domain.repository.PromoAdEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Impression and click aggregates for the admin panel. */
@Service
@Transactional(readOnly = true)
public class PromotionStatsService {

    public record Totals(long impressions, long clicks) {
    }

    private final PromoAdEventRepository eventRepository;

    public PromotionStatsService(PromoAdEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /** One query for a whole page of ads, not two per row. */
    public Map<UUID, Totals> totals(UUID tenantId, List<UUID> adIds) {
        if (adIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, long[]> acc = new HashMap<>();
        for (Object[] row : eventRepository.countByAdIdIn(tenantId, adIds)) {
            UUID adId = (UUID) row[0];
            PromoEventType type = (PromoEventType) row[1];
            long count = (Long) row[2];
            long[] pair = acc.computeIfAbsent(adId, k -> new long[2]);
            if (type == PromoEventType.IMPRESSION) {
                pair[0] += count;
            } else {
                pair[1] += count;
            }
        }
        Map<UUID, Totals> out = new HashMap<>();
        acc.forEach((adId, pair) -> out.put(adId, new Totals(pair[0], pair[1])));
        return out;
    }

    public PromoAdStatsDTO stats(UUID tenantId, UUID adId) {
        Totals t = totals(tenantId, List.of(adId)).getOrDefault(adId, new Totals(0, 0));
        // Guarded so an ad with clicks but no recorded impressions reports 0,
        // not a misleading rate above 1.
        double rate = t.impressions() == 0 ? 0d : (double) t.clicks() / t.impressions();

        Map<LocalDate, long[]> byDay = new TreeMap<>();
        for (Object[] row : eventRepository.dailySeries(tenantId, adId)) {
            LocalDate day = (LocalDate) row[0];
            PromoEventType type = (PromoEventType) row[1];
            long count = (Long) row[2];
            long[] pair = byDay.computeIfAbsent(day, k -> new long[2]);
            if (type == PromoEventType.IMPRESSION) {
                pair[0] += count;
            } else {
                pair[1] += count;
            }
        }
        List<PromoAdStatsDTO.DayPoint> series = new ArrayList<>();
        byDay.forEach((day, pair) -> series.add(new PromoAdStatsDTO.DayPoint(day, pair[0], pair[1])));

        return new PromoAdStatsDTO(adId, t.impressions(), t.clicks(), rate, series);
    }
}
