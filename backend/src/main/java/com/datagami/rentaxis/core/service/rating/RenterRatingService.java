package com.datagami.rentaxis.core.service.rating;

import com.datagami.rentaxis.api.dto.rating.RatingEventDTO;
import com.datagami.rentaxis.api.dto.rating.RatingTrendPointDTO;
import com.datagami.rentaxis.api.dto.rating.RenterRatingDetailDTO;
import com.datagami.rentaxis.api.dto.rating.RenterRatingRowDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.config.AppTimeZone;
import com.datagami.rentaxis.core.service.rating.RenterHistory.LeaseRow;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.RenterRating;
import com.datagami.rentaxis.domain.entity.RenterRatingAdjustment;
import com.datagami.rentaxis.domain.entity.RenterRatingSnapshot;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.RatingGrade;
import com.datagami.rentaxis.domain.repository.RenterRatingAdjustmentRepository;
import com.datagami.rentaxis.domain.repository.RenterRatingRepository;
import com.datagami.rentaxis.domain.repository.RenterRatingSnapshotRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Live scoring, stored ratings (current + monthly snapshots), the filtered list and audited adjustments. */
@Service
public class RenterRatingService {

    static final int WINDOW_DAYS = 730;
    private static final int MAX_ADJUSTMENT = 30;
    private static final int MAX_REASON = 500;

    private final RenterHistoryLoader loader;
    private final RenterRatingRepository ratings;
    private final RenterRatingSnapshotRepository snapshots;
    private final RenterRatingAdjustmentRepository adjustments;
    private final RenterRepository renters;
    private final UserRepository users;
    private final ObjectMapper mapper;

    @PersistenceContext
    private EntityManager em;

    public RenterRatingService(RenterHistoryLoader loader, RenterRatingRepository ratings,
                               RenterRatingSnapshotRepository snapshots,
                               RenterRatingAdjustmentRepository adjustments, RenterRepository renters,
                               UserRepository users, ObjectMapper mapper) {
        this.loader = loader;
        this.ratings = ratings;
        this.snapshots = snapshots;
        this.adjustments = adjustments;
        this.renters = renters;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public RenterRatingDetailDTO live(UUID tenantId, UUID renterId, LocalDate today) {
        requireRenter(tenantId, renterId);
        RenterHistory history = loader.load(tenantId, List.of(renterId), today.minusDays(WINDOW_DAYS)).get(renterId);
        RenterRatingResult r = RenterRatingEngine.score(history, today);
        boolean started = isRated(history);
        List<RenterRatingSnapshot> last = new ArrayList<>(
                snapshots.findTop12ByTenantIdAndRenterIdOrderByMonthDesc(tenantId, renterId));
        last.sort(Comparator.comparing(RenterRatingSnapshot::getMonth));
        List<RatingTrendPointDTO> trend = new ArrayList<>();
        for (RenterRatingSnapshot s : last) trend.add(new RatingTrendPointDTO(s.getMonth(), s.getScore(), s.getGrade()));
        trend.add(new RatingTrendPointDTO(today, r.score(), r.grade()));
        return new RenterRatingDetailDTO(renterId, r.score(), r.grade(), r.deductionsCount(),
                RenterRatingPolicy.BASE_SCORE, toDtos(r.events()), trend, started);
    }

    /**
     * Scores the renters and stores the rows whose fingerprint changed. A requested renter that is no longer
     * rated (no started lease) has its stored row deleted. Returns the number of rows written.
     */
    @Transactional
    public int recomputeAndStore(UUID tenantId, Collection<UUID> renterIds, LocalDate today) {
        if (renterIds == null || renterIds.isEmpty()) return 0;
        Collection<UUID> ids = new LinkedHashSet<>(renterIds);
        Map<UUID, RenterHistory> histories = loader.load(tenantId, ids, today.minusDays(WINDOW_DAYS));
        Map<UUID, RenterRating> stored = new HashMap<>();
        ratings.findByTenantIdAndRenterIdIn(tenantId, ids).forEach(x -> stored.put(x.getRenterId(), x));
        Instant now = Instant.now();
        int written = 0;
        for (UUID id : ids) {
            RenterHistory h = histories.get(id);
            RenterRating existing = stored.get(id);
            if (h == null || !isRated(h)) {
                if (existing != null) ratings.delete(existing);
                continue;
            }
            RenterRatingResult r = RenterRatingEngine.score(h, today);
            String fp = r.fingerprint();
            if (existing != null && fp.equals(existing.getFingerprint())) continue;
            RenterRating row = existing != null ? existing : new RenterRating();
            row.setTenantId(tenantId);
            row.setRenterId(id);
            row.setScore(r.score());
            row.setGrade(r.grade());
            row.setDeductionsCount(r.deductionsCount());
            row.setEventsJson(toJson(r.events()));
            row.setFingerprint(fp);
            row.setComputedAt(now);
            ratings.save(row);
            written++;
        }
        return written;
    }

    /** Upserts a snapshot (score and grade of the stored rating) for each given renter that has a stored rating. */
    @Transactional
    public int snapshotMonth(UUID tenantId, Collection<UUID> renterIds, LocalDate month) {
        if (renterIds == null || renterIds.isEmpty()) return 0;
        Collection<UUID> ids = new LinkedHashSet<>(renterIds);
        Map<UUID, RenterRatingSnapshot> existing = new HashMap<>();
        snapshots.findByTenantIdAndMonthAndRenterIdIn(tenantId, month, ids)
                .forEach(s -> existing.put(s.getRenterId(), s));
        int n = 0;
        for (RenterRating r : ratings.findByTenantIdAndRenterIdIn(tenantId, ids)) {
            RenterRatingSnapshot s = existing.get(r.getRenterId());
            if (s == null) {
                s = new RenterRatingSnapshot();
                s.setTenantId(tenantId);
                s.setRenterId(r.getRenterId());
                s.setMonth(month);
            }
            s.setScore(r.getScore());
            s.setGrade(r.getGrade());
            snapshots.save(s);
            n++;
        }
        return n;
    }

    /**
     * Stored ratings joined to renters and their current (latest started) lease. One count and one page query,
     * both native with an explicit tenant predicate on every table; renterIds null means no id filter.
     */
    @Transactional(readOnly = true)
    public Page<RenterRatingRowDTO> list(UUID tenantId, RatingGrade grade, UUID propertyId, String q,
                                         Collection<UUID> renterIds, Pageable p) {
        return list(tenantId, grade, propertyId, null, q, renterIds, p);
    }

    /** As above, additionally limited to rows whose current property is in propertyIds (null = no limit). */
    @Transactional(readOnly = true)
    public Page<RenterRatingRowDTO> list(UUID tenantId, RatingGrade grade, UUID propertyId,
                                         Collection<UUID> propertyIds, String q,
                                         Collection<UUID> renterIds, Pageable p) {
        if (propertyIds != null && propertyIds.isEmpty()) return new PageImpl<>(List.of(), p, 0);
        if (renterIds != null && renterIds.isEmpty()) return new PageImpl<>(List.of(), p, 0);
        StringBuilder from = new StringBuilder(
                " from renter_ratings r"
                + " join renters n on n.id = r.renter_id and n.tenant_id = :tenantId"
                + " left join lateral (select l.id, l.unit_id from leases l where l.tenant_id = :tenantId"
                + "   and l.renter_id = r.renter_id and l.status not in ('DRAFT', 'PENDING_SIGNATURE')"
                + "   order by l.start_date desc, l.id desc limit 1) cl on true"
                + " left join units u on u.id = cl.unit_id and u.tenant_id = :tenantId"
                + " left join properties pr on pr.id = u.property_id and pr.tenant_id = :tenantId"
                + " where r.tenant_id = :tenantId");
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        if (grade != null) {
            from.append(" and r.grade = :grade");
            params.put("grade", grade.name());
        }
        if (propertyId != null) {
            from.append(" and pr.id = :propertyId");
            params.put("propertyId", propertyId);
        }
        if (propertyIds != null) {
            from.append(" and pr.id in (:propertyIds)");
            params.put("propertyIds", propertyIds);
        }
        if (renterIds != null) {
            from.append(" and r.renter_id in (:renterIds)");
            params.put("renterIds", renterIds);
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            from.append(" and (lower(n.name_en) like :q escape '\\' or lower(coalesce(n.name_ar, '')) like :q escape '\\')");
            params.put("q", like);
        }

        var count = em.createNativeQuery("select count(*)" + from);
        params.forEach(count::setParameter);
        long total = ((Number) count.getSingleResult()).longValue();

        String sql = "select r.renter_id, n.name_en, n.name_ar, r.score, r.grade, r.deductions_count, cl.id,"
                + " u.unit_number, pr.name_en, r.computed_at" + from + orderBy(p.getSort()) + " limit :lim offset :off";
        var query = em.createNativeQuery(sql, Object[].class);
        params.forEach(query::setParameter);
        query.setParameter("lim", p.getPageSize());
        query.setParameter("off", p.getOffset());
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<RenterRatingRowDTO> out = new ArrayList<>(rows.size());
        for (Object[] x : rows) {
            out.add(new RenterRatingRowDTO(uuid(x[0]), (String) x[1], (String) x[2], ((Number) x[3]).intValue(),
                    RatingGrade.valueOf((String) x[4]), ((Number) x[5]).intValue(), uuid(x[6]), (String) x[7],
                    (String) x[8], instant(x[9])));
        }
        return new PageImpl<>(out, p, total);
    }

    @Transactional
    public RatingEventDTO addAdjustment(UUID tenantId, UUID renterId, int points, String reason, UUID userId,
                                        LocalDate today) {
        String trimmed = reason == null ? "" : reason.trim();
        if (points == 0 || points < -MAX_ADJUSTMENT || points > MAX_ADJUSTMENT) {
            throw new BusinessRuleViolationException("Adjustment must be between -30 and 30 and not zero");
        }
        if (trimmed.isEmpty() || trimmed.length() > MAX_REASON) {
            throw new BusinessRuleViolationException("Reason is required (1-500 characters)");
        }
        requireRenter(tenantId, renterId);
        RenterHistory history = loader.load(tenantId, List.of(renterId), today.minusDays(WINDOW_DAYS)).get(renterId);
        if (history == null || !isRated(history)) {
            throw new BusinessRuleViolationException("Tenant has no started contract");
        }
        RenterRatingAdjustment a = new RenterRatingAdjustment();
        a.setTenantId(tenantId);
        a.setRenterId(renterId);
        a.setPoints(points);
        a.setReason(trimmed);
        a.setCreatedBy(userId);
        a = adjustments.saveAndFlush(a);
        recomputeAndStore(tenantId, List.of(renterId), today);
        UUID adjustmentId = a.getId();
        String byName = userId == null ? null : users.findDisplayNameById(userId).orElse(null);
        return scoreOne(tenantId, renterId, today).events().stream()
                .filter(e -> e.type() == RatingEventType.ADJUSTMENT && adjustmentId.equals(e.sourceId()))
                .findFirst().map(e -> RatingEventDTO.of(e, byName))
                .orElseGet(() -> new RatingEventDTO(RatingEventType.ADJUSTMENT, today, points, 1.0, points,
                        "ADJUSTMENT", adjustmentId, null, adjustmentId, trimmed, byName));
    }

    @Transactional
    public void removeAdjustment(UUID tenantId, UUID renterId, UUID adjustmentId, UUID userId, LocalDate today) {
        RenterRatingAdjustment a = adjustments.findByIdAndTenantIdAndRenterId(adjustmentId, tenantId, renterId)
                .orElseThrow(() -> new NotFoundException("Adjustment not found"));
        if (a.getRemovedAt() == null) {
            a.setRemovedAt(Instant.now());
            a.setRemovedBy(userId);
            adjustments.saveAndFlush(a);
        }
        recomputeAndStore(tenantId, List.of(renterId), today);
    }

    /**
     * Events as DTOs, with the display name of whoever added each adjustment (spec 3: an adjustment shows
     * who and when). One name lookup for all of them; names only, so the native cross-tenant read used for
     * audit attribution elsewhere (a SUPER_ADMIN has no tenant) exposes nothing more.
     */
    private List<RatingEventDTO> toDtos(List<RatingEvent> events) {
        Set<UUID> ids = events.stream().map(RatingEvent::createdBy).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : users.findDisplayNamesByIds(ids)) names.put(uuid(row[0]), (String) row[1]);
        }
        return events.stream().map(e -> RatingEventDTO.of(e, names.get(e.createdBy()))).toList();
    }

    private RenterRatingResult scoreOne(UUID tenantId, UUID renterId, LocalDate today) {
        RenterHistory h = loader.load(tenantId, List.of(renterId), today.minusDays(WINDOW_DAYS)).get(renterId);
        return RenterRatingEngine.score(h, today);
    }

    private void requireRenter(UUID tenantId, UUID renterId) {
        Renter r = renterId == null ? null : renters.findById(renterId).orElse(null);
        if (r == null || !tenantId.equals(r.getTenantId())) throw new NotFoundException("Tenant not found");
    }

    private static boolean isRated(RenterHistory h) {
        for (LeaseRow l : h.leases()) {
            if (l.status() != LeaseStatus.DRAFT && l.status() != LeaseStatus.PENDING_SIGNATURE) return true;
        }
        return false;
    }

    /** Whitelisted sort keys (score, name, computedAt); anything else falls back to score ascending. */
    private static String orderBy(Sort sort) {
        Sort.Order o = sort == null || sort.isUnsorted() ? null : sort.iterator().next();
        String dir = o != null && o.isDescending() ? " desc" : " asc";
        String key = o == null ? "score" : o.getProperty();
        String col = switch (key) {
            case "name" -> "lower(n.name_en)";
            case "computedAt" -> "r.computed_at";
            default -> "r.score";
        };
        return " order by " + col + dir + ", r.renter_id";
    }

    private String toJson(List<RatingEvent> events) {
        try {
            return mapper.writeValueAsString(events);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize rating events", e);
        }
    }

    private static UUID uuid(Object o) {
        if (o == null) return null;
        return o instanceof UUID u ? u : UUID.fromString(o.toString());
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
