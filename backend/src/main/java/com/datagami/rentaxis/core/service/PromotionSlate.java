package com.datagami.rentaxis.core.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Picks which ads a given renter sees today.
 *
 * <p>Weighted sampling without replacement by the exponential-race method: each
 * candidate gets {@code key = -ln(u) / priority} where {@code u} is a uniform
 * draw in {@code (0, 1]} derived from a stable hash of
 * {@code (adId, renterId, day)}; the smallest keys win. Priority is a relative
 * airtime weight — a priority-2 ad is drawn about twice as often as a
 * priority-1 ad.
 *
 * <p>Because the draw is hashed rather than random, the same renter gets the
 * same slate in the same order all day. That is deliberate: pull-to-refresh
 * must not reshuffle the strip, and impressions must not inflate with refreshes.
 * The day is part of the seed, so the slate rotates at midnight Dubai time.
 *
 * <p>Pure and static by design — no clock, no database, no Spring. Everything
 * it needs is passed in, which is what makes the fairness claim testable.
 */
public final class PromotionSlate {

    private PromotionSlate() {
    }

    public record Candidate(UUID adId, int priority) {
    }

    public static List<UUID> pick(List<Candidate> candidates, UUID renterId, LocalDate day, int size) {
        if (candidates == null || candidates.isEmpty() || size <= 0) {
            return List.of();
        }
        return candidates.stream()
                .sorted(Comparator.comparingDouble(c -> key(c, renterId, day)))
                .limit(size)
                .map(Candidate::adId)
                .toList();
    }

    private static double key(Candidate c, UUID renterId, LocalDate day) {
        double u = uniform(seed(c.adId(), renterId, day));
        // A non-positive priority would divide by zero or invert the ordering.
        // The DB CHECK forbids it; this guards against a bad backfill anyway.
        int weight = Math.max(1, c.priority());
        return -Math.log(u) / weight;
    }

    /** Maps a 64-bit seed onto (0, 1] — never 0, which would make -ln(u) infinite. */
    private static double uniform(long seed) {
        return ((seed >>> 11) + 1) * 0x1.0p-53;
    }

    /**
     * FNV-1a over the two longs of each UUID plus the epoch day. Chosen over
     * {@code Objects.hash} because this value must stay stable across JVM
     * versions and restarts — a renter's slate changing mid-day because the
     * app redeployed would double-count impressions.
     */
    private static long seed(UUID adId, UUID renterId, LocalDate day) {
        long h = 0xcbf29ce484222325L;
        h = mix(h, adId.getMostSignificantBits());
        h = mix(h, adId.getLeastSignificantBits());
        h = mix(h, renterId.getMostSignificantBits());
        h = mix(h, renterId.getLeastSignificantBits());
        h = mix(h, day.toEpochDay());
        return h;
    }

    private static long mix(long h, long value) {
        long result = h;
        for (int i = 0; i < 8; i++) {
            result ^= (value >>> (i * 8)) & 0xFF;
            result *= 0x100000001b3L;
        }
        return result;
    }
}
