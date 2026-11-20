package com.datagami.rentaxis.core.service.rating;

import com.datagami.rentaxis.config.AppTimeZone;
import com.datagami.rentaxis.core.service.rating.RenterHistory.*;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.RenterRatingAdjustment;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.MeetingStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyAssessmentStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import com.datagami.rentaxis.domain.entity.enums.RenewalOutcome;
import com.datagami.rentaxis.domain.repository.RenterRatingAdjustmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static com.datagami.rentaxis.core.service.rating.RenterRatingPolicy.LONG_HOLD_MAX_DAYS;

/**
 * Turns database rows into {@link RenterHistory} for many renters at once (about eight statements per
 * batch, independent of batch size) so the nightly job scales. Leases load whole (the NEW rule needs the
 * earliest start); every other source is bounded by its own event date >= {@code since}.
 */
@Service
public class RenterHistoryLoader {

    /** Margin so an ON_HOLD that began just before the window, and tipped over 14 days inside it, is seen. */
    private static final int HOLD_MARGIN_DAYS = LONG_HOLD_MAX_DAYS + 1;

    private final RenterRatingQueries queries;
    private final RenterRatingAdjustmentRepository adjustments;

    public RenterHistoryLoader(RenterRatingQueries queries, RenterRatingAdjustmentRepository adjustments) {
        this.queries = queries;
        this.adjustments = adjustments;
    }

    @Transactional(readOnly = true)
    public Map<UUID, RenterHistory> load(UUID tenantId, Collection<UUID> renterIds, LocalDate since) {
        Map<UUID, RenterHistory> out = new LinkedHashMap<>();
        if (renterIds == null || renterIds.isEmpty()) return out;
        Collection<UUID> ids = new LinkedHashSet<>(renterIds);

        UUID previous = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(tenantId);
        try {
            Instant sinceInstant = since.atStartOfDay(AppTimeZone.DEFAULT).toInstant();
            Instant eventsSince = since.minusDays(HOLD_MARGIN_DAYS).atStartOfDay(AppTimeZone.DEFAULT).toInstant();

            Map<UUID, List<LeaseRow>> leases = group(queries.leases(tenantId, ids), r -> new LeaseRow(
                    uuid(r[0]), LeaseStatus.valueOf((String) r[2]), date(r[3]), date(r[4]), uuid(r[5])));
            Map<UUID, List<ChequeRow>> cheques = group(queries.cheques(tenantId, ids, since), r -> new ChequeRow(
                    uuid(r[0]), uuid(r[2]), ChequeStatus.valueOf((String) r[3]), date(r[4]), date(r[5]),
                    date(r[6]), date(r[7]), r[8] == null ? null : ChequeFailureReason.valueOf((String) r[8])));
            Map<UUID, List<PenaltyRow>> penalties = group(queries.penalties(tenantId, ids, since), r -> new PenaltyRow(
                    uuid(r[0]), uuid(r[2]), PenaltyReason.valueOf((String) r[3]),
                    PenaltyAssessmentStatus.valueOf((String) r[4]), date(r[5]), uuid(r[6])));
            Map<UUID, List<WriteOffRow>> writeOffs = group(queries.writeOffs(tenantId, ids, since), r -> new WriteOffRow(
                    uuid(r[0]), uuid(r[2]), (String) r[3], date(r[4])));

            // Lease-derived sources are fanned to the owning renter through the loaded leases.
            Map<UUID, UUID> renterOfLease = new HashMap<>();
            leases.forEach((renter, ls) -> ls.forEach(l -> renterOfLease.put(l.id(), renter)));

            Map<UUID, List<LeaseEventRow>> events = new HashMap<>();
            Map<UUID, List<OpportunityRow>> opps = new HashMap<>();
            Map<UUID, List<MeetingRow>> meetings = new HashMap<>();
            if (!renterOfLease.isEmpty()) {
                for (Object[] r : queries.leaseEvents(tenantId, ids, eventsSince)) {
                    put(events, renterOfLease, uuid(r[0]), new LeaseEventRow(uuid(r[0]), (String) r[1], (String) r[2],
                            instant(r[3])));
                }
                for (Object[] r : queries.opportunities(tenantId, ids, sinceInstant)) {
                    put(opps, renterOfLease, uuid(r[1]), new OpportunityRow(uuid(r[0]), uuid(r[1]),
                            r[2] == null ? null : RenewalIntent.valueOf((String) r[2]), instant(r[3]),
                            r[4] == null ? null : RenewalOutcome.valueOf((String) r[4]), instant(r[5])));
                }
                for (Object[] r : queries.meetings(tenantId, ids, sinceInstant)) {
                    put(meetings, renterOfLease, uuid(r[1]), new MeetingRow(uuid(r[0]), uuid(r[1]),
                            MeetingStatus.valueOf((String) r[2]), instant(r[3])));
                }
            }

            Map<UUID, List<AdjustmentRow>> adj = new HashMap<>();
            for (RenterRatingAdjustment a : adjustments.findByTenantIdAndRenterIdInAndRemovedAtIsNull(tenantId, ids)) {
                adj.computeIfAbsent(a.getRenterId(), k -> new ArrayList<>()).add(
                        new AdjustmentRow(a.getId(), a.getPoints(), a.getReason(), a.getCreatedBy(), a.getCreatedAt()));
            }

            for (UUID id : ids) {
                out.put(id, new RenterHistory(id,
                        leases.getOrDefault(id, List.of()), cheques.getOrDefault(id, List.of()),
                        penalties.getOrDefault(id, List.of()), writeOffs.getOrDefault(id, List.of()),
                        events.getOrDefault(id, List.of()), opps.getOrDefault(id, List.of()),
                        meetings.getOrDefault(id, List.of()), adj.getOrDefault(id, List.of())));
            }
            return out;
        } finally {
            restore(previous);
        }
    }

    /** Renters with at least one started lease, ordered by id, strictly after {@code afterId} (keyset paging). */
    @Transactional(readOnly = true)
    public List<UUID> ratedRenterIds(UUID tenantId, UUID afterId, int limit) {
        UUID previous = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(tenantId);
        try {
            return queries.ratedRenterIds(tenantId, afterId, limit);
        } finally {
            restore(previous);
        }
    }

    private static void restore(UUID previous) {
        if (previous == null) TenantContextHolder.clear(); else TenantContextHolder.setTenantId(previous);
    }

    /** Groups rows by their renter id, always column 1 of every renter-keyed query. */
    private static <T> Map<UUID, List<T>> group(List<Object[]> rows, Function<Object[], T> mapper) {
        Map<UUID, List<T>> m = new HashMap<>();
        for (Object[] r : rows) {
            UUID renter = uuid(r[1]);
            if (renter == null) continue;
            m.computeIfAbsent(renter, k -> new ArrayList<>()).add(mapper.apply(r));
        }
        return m;
    }

    private static <T> void put(Map<UUID, List<T>> m, Map<UUID, UUID> renterOfLease, UUID leaseId, T row) {
        UUID renter = renterOfLease.get(leaseId);
        if (renter != null) m.computeIfAbsent(renter, k -> new ArrayList<>()).add(row);
    }

    private static UUID uuid(Object o) {
        if (o == null) return null;
        return o instanceof UUID u ? u : UUID.fromString(o.toString());
    }

    private static LocalDate date(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(o.toString());
    }

    private static Instant instant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant i) return i;
        if (o instanceof OffsetDateTime t) return t.toInstant();
        if (o instanceof java.sql.Timestamp t) return t.toInstant();
        if (o instanceof java.time.LocalDateTime t) return t.atZone(AppTimeZone.DEFAULT).toInstant();
        throw new IllegalStateException("Unexpected timestamp type " + o.getClass());
    }
}
