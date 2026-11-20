package com.datagami.rentaxis.core.service.rating;

import com.datagami.rentaxis.config.AppTimeZone;
import com.datagami.rentaxis.core.service.rating.RenterHistory.*;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.MeetingStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyAssessmentStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import com.datagami.rentaxis.domain.entity.enums.RatingGrade;
import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import com.datagami.rentaxis.domain.entity.enums.RenewalOutcome;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.datagami.rentaxis.core.service.rating.RenterRatingPolicy.*;

/** Pure scoring function. No database access. */
public final class RenterRatingEngine {

    private RenterRatingEngine() {}

    private static final String ON_HOLD = "ON_HOLD";

    public static RenterRatingResult score(RenterHistory h, LocalDate today) {
        List<LeaseRow> started = h.leases().stream()
                .filter(l -> l.status() != LeaseStatus.DRAFT && l.status() != LeaseStatus.PENDING_SIGNATURE)
                .toList();
        if (started.isEmpty()) {
            return new RenterRatingResult(h.renterId(), BASE_SCORE, RatingGrade.NEW, 0, List.of());
        }
        Map<UUID, LeaseRow> leaseById = started.stream()
                .collect(Collectors.toMap(LeaseRow::id, l -> l, (a, b) -> a));
        Set<UUID> leaseIds = leaseById.keySet();

        List<RatingEvent> raw = new ArrayList<>();
        int windowCheques = 0;

        for (ChequeRow c : h.cheques()) {
            if (c.leaseId() != null && !leaseIds.contains(c.leaseId())) continue;
            if (c.chequeDate() != null && inWindow(c.chequeDate(), today)
                    && c.status() != ChequeStatus.DRAFT && c.status() != ChequeStatus.CANCELLED) windowCheques++;
            LocalDate bounceDate = c.bouncedAt() != null ? c.bouncedAt() : c.importedBouncedOn();
            if (bounceDate != null && c.failureReason() != ChequeFailureReason.TECHNICAL_RETURN) {
                add(raw, RatingEventType.BOUNCE, bounceDate, BOUNCE_POINTS, "CHEQUE", c.id(), c.leaseId(), null, null,
                        today);
                continue;
            }
            if (c.status() != ChequeStatus.CLEARED || c.clearedAt() == null || c.chequeDate() == null) continue;
            if (!c.clearedAt().isAfter(c.chequeDate().plusDays(ON_TIME_GRACE_DAYS))) {
                add(raw, RatingEventType.ON_TIME_CHEQUE, c.clearedAt(), ON_TIME_CHEQUE_POINTS, "CHEQUE", c.id(),
                        c.leaseId(), null, null, today);
            } else {
                add(raw, RatingEventType.LATE_CLEAR, c.clearedAt(), LATE_CLEAR_POINTS, "CHEQUE", c.id(),
                        c.leaseId(), null, null, today);
            }
        }

        for (LeaseRow l : started) {
            if (l.renewedFromLeaseId() != null && l.startDate() != null
                    && h.leases().stream().anyMatch(o -> l.renewedFromLeaseId().equals(o.id()))) {
                add(raw, RatingEventType.RENEWAL, l.startDate(), RENEWAL_POINTS, "LEASE", l.id(), l.id(), null, null,
                        today);
            }
        }

        for (PenaltyRow p : h.penalties()) {
            if (p.leaseId() == null || !leaseIds.contains(p.leaseId()) || p.incidentDate() == null) continue;
            if (p.status() != PenaltyAssessmentStatus.APPROVED && p.status() != PenaltyAssessmentStatus.WRITTEN_OFF) continue;
            if (p.reason() == PenaltyReason.LATE_PAYMENT) {
                add(raw, RatingEventType.LATE_PAYMENT_PENALTY, p.incidentDate(), LATE_PAYMENT_POINTS, "PENALTY",
                        p.id(), p.leaseId(), null, null, today);
            } else if (p.reason() == PenaltyReason.DAMAGE
                    || (p.reason() == PenaltyReason.CHEQUE_RETURN && p.sourceChequeId() == null)) {
                add(raw, RatingEventType.MISCONDUCT_PENALTY, p.incidentDate(), MISCONDUCT_POINTS, "PENALTY",
                        p.id(), p.leaseId(), null, null, today);
            }
        }

        for (WriteOffRow w : h.writeOffs()) {
            if (w.leaseId() == null || !leaseIds.contains(w.leaseId()) || w.writeOffDate() == null) continue;
            if (!"WRITTEN_OFF".equals(w.status())) continue;
            add(raw, RatingEventType.WRITE_OFF, w.writeOffDate(), WRITE_OFF_POINTS, "WRITE_OFF", w.id(),
                    w.leaseId(), null, null, today);
        }

        addLongHolds(h, leaseIds, today, raw);

        for (OpportunityRow o : h.opportunities()) {
            if (o.leaseId() == null || !leaseIds.contains(o.leaseId())) continue;
            if (o.intent() == RenewalIntent.RENEW && o.intentCapturedAt() != null) {
                LocalDate captured = toDate(o.intentCapturedAt());
                LocalDate end = leaseById.get(o.leaseId()).endDate();
                if (end != null && ChronoUnit.DAYS.between(captured, end) >= EARLY_INTENT_MIN_DAYS) {
                    add(raw, RatingEventType.EARLY_INTENT, captured, EARLY_INTENT_POINTS, "OPPORTUNITY", o.id(),
                            o.leaseId(), null, null, today);
                }
            }
            if (o.outcome() == RenewalOutcome.EXPIRED_NO_RESPONSE && o.closedAt() != null) {
                add(raw, RatingEventType.NO_RESPONSE, toDate(o.closedAt()), NO_RESPONSE_POINTS, "OPPORTUNITY",
                        o.id(), o.leaseId(), null, null, today);
            }
        }

        for (MeetingRow m : h.meetings()) {
            if (m.leaseId() == null || !leaseIds.contains(m.leaseId()) || m.slotStart() == null) continue;
            if (m.status() == MeetingStatus.NO_SHOW) {
                add(raw, RatingEventType.MEETING_NO_SHOW, toDate(m.slotStart()), NO_SHOW_POINTS, "MEETING", m.id(),
                        m.leaseId(), null, null, today);
            }
        }

        for (AdjustmentRow a : h.adjustments()) {
            if (a.createdAt() == null) continue;
            add(raw, RatingEventType.ADJUSTMENT, toDate(a.createdAt()), a.points(), "ADJUSTMENT", a.id(), null,
                    a.reason(), a.createdBy(), today);
        }

        List<RatingEvent> events = applyCaps(raw);
        events.sort(Comparator.comparing(RatingEvent::date).reversed()
                .thenComparing(e -> e.type().ordinal())
                .thenComparing(e -> String.valueOf(e.sourceId())));

        double sum = events.stream().mapToDouble(RatingEvent::appliedPoints).sum();
        int score = (int) Math.max(0, Math.min(100, Math.round(BASE_SCORE + sum)));
        int deductions = (int) events.stream().filter(e -> e.appliedPoints() < 0).count();
        boolean hasAdjustment = events.stream().anyMatch(e -> e.type() == RatingEventType.ADJUSTMENT);

        LocalDate earliest = started.stream().map(LeaseRow::startDate).filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        boolean thin = (earliest != null && earliest.isAfter(today.minusMonths(NEW_MIN_LEASE_MONTHS)))
                || windowCheques < NEW_MIN_CHEQUES;
        RatingGrade grade = (thin && deductions == 0 && !hasAdjustment) ? RatingGrade.NEW : gradeFor(score);

        return new RenterRatingResult(h.renterId(), score, grade, deductions, List.copyOf(events));
    }

    private static void addLongHolds(RenterHistory h, Set<UUID> leaseIds, LocalDate today, List<RatingEvent> raw) {
        Map<UUID, List<LeaseEventRow>> byLease = new HashMap<>();
        for (LeaseEventRow e : h.leaseEvents()) {
            if (e.leaseId() == null || !leaseIds.contains(e.leaseId()) || e.createdAt() == null) continue;
            byLease.computeIfAbsent(e.leaseId(), k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<UUID, List<LeaseEventRow>> en : byLease.entrySet()) {
            List<LeaseEventRow> evs = en.getValue().stream()
                    .sorted(Comparator.comparing(LeaseEventRow::createdAt)).toList();
            for (int i = 0; i < evs.size(); i++) {
                LeaseEventRow in = evs.get(i);
                if (!ON_HOLD.equals(in.newState())) continue;
                LocalDate start = toDate(in.createdAt());
                LocalDate end = today;
                for (int j = i + 1; j < evs.size(); j++) {
                    if (ON_HOLD.equals(evs.get(j).previousState())) {
                        end = toDate(evs.get(j).createdAt());
                        break;
                    }
                }
                if (ChronoUnit.DAYS.between(start, end) > LONG_HOLD_MAX_DAYS) {
                    add(raw, RatingEventType.LONG_HOLD, start.plusDays(LONG_HOLD_MAX_DAYS + 1), LONG_HOLD_POINTS,
                            "LEASE", en.getKey(), en.getKey(), null, null, today);
                    break; // at most one per lease
                }
            }
        }
    }

    /** Scales each capped credit type so its events sum to exactly the cap. */
    private static List<RatingEvent> applyCaps(List<RatingEvent> raw) {
        Map<RatingEventType, Integer> caps = Map.of(
                RatingEventType.ON_TIME_CHEQUE, ON_TIME_CHEQUE_CAP,
                RatingEventType.RENEWAL, RENEWAL_CAP,
                RatingEventType.EARLY_INTENT, EARLY_INTENT_CAP);
        Map<RatingEventType, Double> sums = new HashMap<>();
        for (RatingEvent e : raw) {
            if (caps.containsKey(e.type())) sums.merge(e.type(), e.appliedPoints(), Double::sum);
        }
        List<RatingEvent> out = new ArrayList<>(raw.size());
        for (RatingEvent e : raw) {
            Integer cap = caps.get(e.type());
            if (cap != null && sums.get(e.type()) > cap) {
                out.add(e.withAppliedPoints(e.appliedPoints() * cap / sums.get(e.type())));
            } else {
                out.add(e);
            }
        }
        return out;
    }

    private static void add(List<RatingEvent> out, RatingEventType type, LocalDate date, int base, String sourceType,
                            UUID sourceId, UUID leaseId, String note, UUID createdBy, LocalDate today) {
        double w = weight(date, today);
        if (w == 0) return;
        out.add(new RatingEvent(type, date, base, w, base * w, sourceType, sourceId, leaseId, note, createdBy));
    }

    static double weight(LocalDate date, LocalDate today) {
        long age = ChronoUnit.DAYS.between(date, today);
        if (age <= FULL_WEIGHT_DAYS) return FULL_WEIGHT;
        if (age <= HALF_WEIGHT_DAYS) return HALF_WEIGHT;
        return 0;
    }

    private static boolean inWindow(LocalDate date, LocalDate today) {
        long age = ChronoUnit.DAYS.between(date, today);
        return age >= 0 && age <= HALF_WEIGHT_DAYS;
    }

    private static RatingGrade gradeFor(int score) {
        if (score >= GRADE_A_MIN) return RatingGrade.A;
        if (score >= GRADE_B_MIN) return RatingGrade.B;
        if (score >= GRADE_C_MIN) return RatingGrade.C;
        return RatingGrade.D;
    }

    private static LocalDate toDate(Instant i) {
        return i.atZone(AppTimeZone.DEFAULT).toLocalDate();
    }
}
