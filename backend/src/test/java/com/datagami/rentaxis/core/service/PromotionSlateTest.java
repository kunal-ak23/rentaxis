package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.service.PromotionSlate.Candidate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionSlateTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 23);

    private List<Candidate> candidates(int count, int priority) {
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // Deterministic ids so a failure is reproducible.
            out.add(new Candidate(new UUID(1000L + i, 7L), priority));
        }
        return out;
    }

    @Test
    void pick_isDeterministicForTheSameRenterAndDay() {
        List<Candidate> pool = candidates(40, 1);
        UUID renter = new UUID(42L, 42L);

        List<UUID> first = PromotionSlate.pick(pool, renter, DAY, 6);
        List<UUID> second = PromotionSlate.pick(pool, renter, DAY, 6);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void pick_returnsAtMostTheRequestedSize() {
        assertThat(PromotionSlate.pick(candidates(40, 1), new UUID(1L, 1L), DAY, 6)).hasSize(6);
    }

    @Test
    void pick_returnsEverythingWhenPoolIsSmallerThanSize() {
        assertThat(PromotionSlate.pick(candidates(3, 1), new UUID(1L, 1L), DAY, 6)).hasSize(3);
    }

    @Test
    void pick_returnsEmptyForAnEmptyPool() {
        assertThat(PromotionSlate.pick(List.of(), new UUID(1L, 1L), DAY, 6)).isEmpty();
    }

    @Test
    void pick_neverRepeatsAnAd() {
        List<UUID> slate = PromotionSlate.pick(candidates(40, 1), new UUID(9L, 9L), DAY, 6);
        assertThat(slate).doesNotHaveDuplicates();
    }

    @Test
    void pick_rotatesAcrossDays() {
        List<Candidate> pool = candidates(40, 1);
        UUID renter = new UUID(42L, 42L);

        List<UUID> monday = PromotionSlate.pick(pool, renter, DAY, 6);
        List<UUID> tuesday = PromotionSlate.pick(pool, renter, DAY.plusDays(1), 6);

        assertThat(monday).isNotEqualTo(tuesday);
    }

    @Test
    void pick_differsBetweenRenters() {
        List<Candidate> pool = candidates(40, 1);

        assertThat(PromotionSlate.pick(pool, new UUID(1L, 1L), DAY, 6))
                .isNotEqualTo(PromotionSlate.pick(pool, new UUID(2L, 2L), DAY, 6));
    }

    @Test
    void pick_givesEveryAdAirtimeOverAMonth() {
        List<Candidate> pool = candidates(40, 1);
        UUID renter = new UUID(7L, 7L);

        List<UUID> seen = new ArrayList<>();
        for (int d = 0; d < 30; d++) {
            seen.addAll(PromotionSlate.pick(pool, renter, DAY.plusDays(d), 6));
        }

        // 30 days x 6 slots over a 40-ad pool: every ad should surface at least once.
        assertThat(seen.stream().distinct().toList()).hasSize(40);
    }

    @Test
    void pick_weightsHigherPriorityHigher() {
        // One heavy ad among 39 light ones, sampled across 2000 renters.
        List<Candidate> pool = new ArrayList<>(candidates(39, 1));
        UUID heavy = new UUID(999L, 999L);
        pool.add(new Candidate(heavy, 10));

        int heavyHits = 0;
        int lightHits = 0;
        UUID firstLight = pool.get(0).adId();
        for (int r = 0; r < 2000; r++) {
            List<UUID> slate = PromotionSlate.pick(pool, new UUID(r, 5L), DAY, 6);
            if (slate.contains(heavy)) heavyHits++;
            if (slate.contains(firstLight)) lightHits++;
        }

        // Priority 10 vs 1: the heavy ad should appear far more often. The bound
        // is loose on purpose — this asserts the weighting works, not an exact rate.
        assertThat(heavyHits).isGreaterThan(lightHits * 3);
    }

    @Test
    void pick_toleratesNonPositivePriority() {
        // The DB CHECK forbids it, but a bad backfill must not divide by zero.
        List<Candidate> pool = List.of(
                new Candidate(new UUID(1L, 1L), 0),
                new Candidate(new UUID(2L, 2L), -5));

        assertThat(PromotionSlate.pick(pool, new UUID(3L, 3L), DAY, 6)).hasSize(2);
    }
}
