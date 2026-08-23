package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PromoAdStatsDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import com.datagami.rentaxis.domain.repository.PromoAdEventRepository;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionStatsServiceTest {

    @Mock PromoAdEventRepository eventRepository;
    @Mock PromoAdRepository adRepository;

    PromotionStatsService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID adId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PromotionStatsService(eventRepository, adRepository);
    }

    private PromoAd ad(UUID id) {
        PromoAd a = new PromoAd();
        a.setId(id);
        a.setTenantId(tenantId);
        return a;
    }

    /** {@code [adId, eventType, count, distinctRenters]} */
    private Object[] row(UUID id, PromoEventType type, long count, long distinct) {
        return new Object[]{id, type, count, distinct};
    }

    // ------------------------------------------------------------- totals

    @Test
    void totals_foldsGroupedRowsIntoImpressionsAndClicks() {
        when(eventRepository.countByAdIdIn(tenantId, List.of(adId))).thenReturn(List.of(
                row(adId, PromoEventType.IMPRESSION, 1240L, 800L),
                row(adId, PromoEventType.CLICK, 87L, 60L)));

        Map<UUID, PromotionStatsService.Totals> totals = service.totals(tenantId, List.of(adId));

        assertThat(totals.get(adId).impressions()).isEqualTo(1240L);
        assertThat(totals.get(adId).clicks()).isEqualTo(87L);
    }

    @Test
    void totals_keepsEachAdsCountsSeparate() {
        // The mutation this kills: one shared accumulator sprayed across every
        // requested id, which puts one business's numbers on another's row in
        // the admin table — cross-client mis-attribution on the exact figure a
        // client is sold on. Asymmetric counts so a swap cannot look right.
        UUID other = UUID.randomUUID();
        when(eventRepository.countByAdIdIn(tenantId, List.of(adId, other))).thenReturn(List.of(
                row(adId, PromoEventType.IMPRESSION, 1000L, 900L),
                row(adId, PromoEventType.CLICK, 10L, 9L),
                row(other, PromoEventType.IMPRESSION, 5L, 4L),
                row(other, PromoEventType.CLICK, 4L, 3L)));

        Map<UUID, PromotionStatsService.Totals> totals =
                service.totals(tenantId, List.of(adId, other));

        assertThat(totals.get(adId).impressions()).isEqualTo(1000L);
        assertThat(totals.get(adId).clicks()).isEqualTo(10L);
        assertThat(totals.get(other).impressions()).isEqualTo(5L);
        assertThat(totals.get(other).clicks()).isEqualTo(4L);
    }

    @Test
    void totals_skipsTheQueryForAnEmptyPage() {
        assertThat(service.totals(tenantId, List.of())).isEmpty();
        verify(eventRepository, never()).countByAdIdIn(any(), anyList());
    }

    @Test
    void totals_defaultsToZeroForAnAdWithNoEvents() {
        when(eventRepository.countByAdIdIn(tenantId, List.of(adId))).thenReturn(List.of());

        assertThat(service.totals(tenantId, List.of(adId))
                .getOrDefault(adId, PromotionStatsService.Totals.EMPTY).impressions())
                .isZero();
    }

    @Test
    void totals_ignoresAnUnknownEventType() {
        // Guards the `else if (CLICK)` rather than a bare `else`: adding a
        // DISMISS event later must not silently inflate the tap count.
        when(eventRepository.countByAdIdIn(tenantId, List.of(adId))).thenReturn(
                List.<Object[]>of(row(adId, PromoEventType.IMPRESSION, 100L, 100L)));

        assertThat(service.totals(tenantId, List.of(adId)).get(adId).clicks()).isZero();
    }

    // -------------------------------------------------------------- stats

    @Test
    void stats_computesTapThroughRateFromDistinctRenters() {
        when(adRepository.findById(adId)).thenReturn(Optional.of(ad(adId)));
        when(eventRepository.countByAdIdIn(tenantId, List.of(adId))).thenReturn(List.of(
                row(adId, PromoEventType.IMPRESSION, 400L, 200L),
                row(adId, PromoEventType.CLICK, 120L, 50L)));
        when(eventRepository.dailySeries(tenantId, adId)).thenReturn(List.of());

        // 50 distinct clickers over 200 distinct viewers, not 120/400.
        assertThat(service.stats(tenantId, adId).tapThroughRate()).isEqualTo(0.25);
    }

    @Test
    void stats_neverReportsARateAboveOne() {
        // Raw clicks can exceed raw impressions — impressions are deduped per
        // renter-day, clicks are not — and a lost impression flush can leave a
        // clicker with no view on record. "333.3%" reads as a broken dashboard.
        when(adRepository.findById(adId)).thenReturn(Optional.of(ad(adId)));
        when(eventRepository.countByAdIdIn(tenantId, List.of(adId))).thenReturn(List.of(
                row(adId, PromoEventType.IMPRESSION, 3L, 3L),
                row(adId, PromoEventType.CLICK, 10L, 5L)));
        when(eventRepository.dailySeries(tenantId, adId)).thenReturn(List.of());

        assertThat(service.stats(tenantId, adId).tapThroughRate()).isEqualTo(1.0);
    }

    @Test
    void stats_tapThroughRateIsZeroWithNoImpressions() {
        when(adRepository.findById(adId)).thenReturn(Optional.of(ad(adId)));
        when(eventRepository.countByAdIdIn(tenantId, List.of(adId))).thenReturn(
                List.<Object[]>of(row(adId, PromoEventType.CLICK, 3L, 3L)));
        when(eventRepository.dailySeries(tenantId, adId)).thenReturn(List.of());

        // No division by zero, and no NaN — NaN is not valid JSON.
        assertThat(service.stats(tenantId, adId).tapThroughRate()).isZero();
    }

    @Test
    void stats_404sForAnAdThatIsNotThisTenants() {
        PromoAd foreign = ad(adId);
        foreign.setTenantId(UUID.randomUUID());
        when(adRepository.findById(adId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.stats(tenantId, adId))
                .isInstanceOf(NotFoundException.class);
        verify(eventRepository, never()).countByAdIdIn(any(), anyList());
    }

    @Test
    void stats_404sForAnAdThatDoesNotExist() {
        when(adRepository.findById(adId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.stats(tenantId, adId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void stats_mergesDailyRowsIntoOnePointPerDayInDayOrder() {
        LocalDate day = LocalDate.of(2026, 8, 20);
        when(adRepository.findById(adId)).thenReturn(Optional.of(ad(adId)));
        when(eventRepository.countByAdIdIn(tenantId, List.of(adId))).thenReturn(List.of());
        // Deliberately out of order, so the ordering is actually asserted rather
        // than inherited from the fixture.
        when(eventRepository.dailySeries(tenantId, adId)).thenReturn(List.of(
                new Object[]{day.plusDays(1), PromoEventType.IMPRESSION, 7L},
                new Object[]{day, PromoEventType.CLICK, 2L},
                new Object[]{day, PromoEventType.IMPRESSION, 10L}));

        List<PromoAdStatsDTO.DayPoint> series = service.stats(tenantId, adId).series();

        assertThat(series).containsExactly(
                new PromoAdStatsDTO.DayPoint(day, 10L, 2L),
                new PromoAdStatsDTO.DayPoint(day.plusDays(1), 7L, 0L));
    }

    @Test
    void stats_boundsTheSeriesRatherThanScalingWithAdAge() {
        // Zero-filling from the first event to the last would make an ad that
        // ran for a week and then took one impression a year later return ~370
        // points, nearly all zeros, on every load of its detail view.
        LocalDate old = LocalDate.of(2025, 1, 1);
        LocalDate recent = old.plusDays(400);
        when(adRepository.findById(adId)).thenReturn(Optional.of(ad(adId)));
        when(eventRepository.countByAdIdIn(tenantId, List.of(adId))).thenReturn(List.of());
        when(eventRepository.dailySeries(tenantId, adId)).thenReturn(List.of(
                new Object[]{old, PromoEventType.IMPRESSION, 5L},
                new Object[]{recent, PromoEventType.IMPRESSION, 1L}));

        List<PromoAdStatsDTO.DayPoint> series = service.stats(tenantId, adId).series();

        assertThat(series).hasSize(PromotionStatsService.SERIES_WINDOW_DAYS);
        assertThat(series.get(series.size() - 1).day()).isEqualTo(recent);
        assertThat(series.get(0).day())
                .isEqualTo(recent.minusDays(PromotionStatsService.SERIES_WINDOW_DAYS - 1L));
    }

    @Test
    void stats_zeroFillsDaysWithNoEvents() {
        // A gap would make a line chart interpolate straight across a dead
        // week, visually inflating a period where the ad served nothing.
        LocalDate day = LocalDate.of(2026, 8, 20);
        when(adRepository.findById(adId)).thenReturn(Optional.of(ad(adId)));
        when(eventRepository.countByAdIdIn(tenantId, List.of(adId))).thenReturn(List.of());
        when(eventRepository.dailySeries(tenantId, adId)).thenReturn(List.of(
                new Object[]{day, PromoEventType.IMPRESSION, 10L},
                new Object[]{day.plusDays(3), PromoEventType.IMPRESSION, 4L}));

        List<PromoAdStatsDTO.DayPoint> series = service.stats(tenantId, adId).series();

        assertThat(series).hasSize(4);
        assertThat(series.get(1)).isEqualTo(new PromoAdStatsDTO.DayPoint(day.plusDays(1), 0L, 0L));
        assertThat(series.get(2)).isEqualTo(new PromoAdStatsDTO.DayPoint(day.plusDays(2), 0L, 0L));
    }
}
