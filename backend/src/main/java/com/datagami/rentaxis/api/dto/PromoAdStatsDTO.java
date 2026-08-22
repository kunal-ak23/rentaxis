package com.datagami.rentaxis.api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PromoAdStatsDTO(
        UUID adId,
        long impressions,
        long clicks,
        double tapThroughRate,
        List<DayPoint> series) {

    public record DayPoint(LocalDate day, long impressions, long clicks) {
    }
}
