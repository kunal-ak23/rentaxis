package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PromoAdStatsDTO;
import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import com.datagami.rentaxis.domain.repository.PromoAdEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionStatsServiceTest {

    @Mock PromoAdEventRepository eventRepository;
    @InjectMocks PromotionStatsService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID adId = UUID.randomUUID();

    @Test
    void totals_foldsGroupedRowsIntoImpressionsAndClicks() {
        when(eventRepository.countByAdIdIn(tenantId, List.of(adId))).thenReturn(List.of(
                new Object[]{adId, PromoEventType.IMPRESSION, 1240L},
                new Object[]{adId, PromoEventType.CLICK, 87L}));

        Map<UUID, PromotionStatsService.Totals> totals = service.totals(tenantId, List.of(adId));

        assertThat(totals.get(adId).impressions()).isEqualTo(1240L);
        assertThat(totals.get(adId).clicks()).isEqualTo(87L);
    }

    @Test
    void totals_skipsTheQueryForAnEmptyPage() {
        assertThat(service.totals(tenantId, List.of())).isEmpty();
        verify(eventRepository, never()).countByAdIdIn(any(), anyList());
    }

    @Test
    void totals_defaultsToZeroForAnAdWithNoEvents() {
        when(eventRepository.countByAdIdIn(tenantId, List.of(adId))).thenReturn(List.of());

        Map<UUID, PromotionStatsService.Totals> totals = service.totals(tenantId, List.of(adId));

        assertThat(totals.getOrDefault(adId, new PromotionStatsService.Totals(0, 0)).impressions())
                .isZero();
    }

    @Test
    void stats_computesTapThroughRate() {
        when(eventRepository.countByAdIdIn(tenantId, List.of(adId))).thenReturn(List.of(
                new Object[]{adId, PromoEventType.IMPRESSION, 200L},
                new Object[]{adId, PromoEventType.CLICK, 50L}));
        when(eventRepository.dailySeries(tenantId, adId)).thenReturn(List.of());

        PromoAdStatsDTO stats = service.stats(tenantId, adId);

        assertThat(stats.tapThroughRate()).isEqualTo(0.25);
    }

    @Test
    void stats_tapThroughRateIsZeroWithNoImpressions() {
        // List.<Object[]>of — a bare List.of with ONE array varargs-expands
        // into List<Object> and does not compile against List<Object[]>.
        when(eventRepository.countByAdIdIn(tenantId, List.of(adId)))
                .thenReturn(List.<Object[]>of(new Object[]{adId, PromoEventType.CLICK, 3L}));
        when(eventRepository.dailySeries(tenantId, adId)).thenReturn(List.of());

        // No division by zero, and no misleading "300%".
        assertThat(service.stats(tenantId, adId).tapThroughRate()).isZero();
    }

    @Test
    void stats_mergesDailyRowsIntoOnePointPerDay() {
        LocalDate day = LocalDate.of(2026, 8, 20);
        when(eventRepository.countByAdIdIn(tenantId, List.of(adId))).thenReturn(List.of());
        when(eventRepository.dailySeries(tenantId, adId)).thenReturn(List.of(
                new Object[]{day, PromoEventType.IMPRESSION, 10L},
                new Object[]{day, PromoEventType.CLICK, 2L},
                new Object[]{day.plusDays(1), PromoEventType.IMPRESSION, 7L}));

        List<PromoAdStatsDTO.DayPoint> series = service.stats(tenantId, adId).series();

        assertThat(series).hasSize(2);
        assertThat(series.get(0)).isEqualTo(new PromoAdStatsDTO.DayPoint(day, 10L, 2L));
        assertThat(series.get(1)).isEqualTo(new PromoAdStatsDTO.DayPoint(day.plusDays(1), 7L, 0L));
    }
}
