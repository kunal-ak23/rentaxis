package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.penalty.PenaltyAssessmentDTO;
import com.datagami.rentaxis.api.dto.penalty.ProposePenaltyRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.penalty.PenaltyAssessmentService;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyAssessmentStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * F14-50: booking fees. An amenity is free, a fixed fee per booking or a fee per
 * hour; a parking spot is free or a fixed fee per booking. The fee is quoted when
 * the renter asks (the portal shows it first) and becomes a BOOKING_FEE charge on
 * the renter's lease — income, VAT per F14-30 — when the booking is approved,
 * posted at once. A booking cancelled before its slot has the charge reversed.
 */
@Service
public class BookingFeeService {

    public static final String SOURCE = "BOOKING";
    public static final Set<String> TYPES = Set.of("FREE", "PER_BOOKING", "PER_HOUR");

    private static final EnumSet<LeaseStatus> LIVE = EnumSet.of(LeaseStatus.ACTIVE, LeaseStatus.NOTICE_GIVEN,
            LeaseStatus.EXPIRED, LeaseStatus.RENEWED);

    private final PenaltyAssessmentService charges;
    private final LeaseRepository leases;

    public BookingFeeService(PenaltyAssessmentService charges, LeaseRepository leases) {
        this.charges = charges;
        this.leases = leases;
    }

    /** Validates a fee setting; PER_HOUR is for amenities only. */
    public static void validate(String type, BigDecimal amount, boolean parking) {
        String t = type == null ? "FREE" : type;
        if (!TYPES.contains(t) || (parking && "PER_HOUR".equals(t))) {
            throw new BusinessRuleViolationException("Unknown fee type " + t, "booking.feeType", Map.of("type", t));
        }
        if (!"FREE".equals(t) && (amount == null || amount.signum() <= 0)) {
            throw new BusinessRuleViolationException("A fee needs an amount greater than zero", "booking.feeAmount", Map.of());
        }
    }

    public static BigDecimal quote(PropertyAmenity a, BookingRequest b) {
        return quote(a.getFeeType(), a.getFeeAmount(), b);
    }

    public static BigDecimal quote(ParkingSpot s, BookingRequest b) {
        return quote(s.getFeeType(), s.getFeeAmount(), b);
    }

    /** Per hour: the slot's hours on each day it covers (a slot without times counts one hour a day). */
    static BigDecimal quote(String type, BigDecimal amount, BookingRequest b) {
        if (type == null || "FREE".equals(type) || amount == null) return BigDecimal.ZERO.setScale(2);
        if ("PER_BOOKING".equals(type)) return amount.setScale(2, RoundingMode.HALF_UP);
        long days = b.getPreferredDate() == null ? 1
                : ChronoUnit.DAYS.between(b.getPreferredDate(),
                        b.getPreferredEndDate() != null ? b.getPreferredEndDate() : b.getPreferredDate()) + 1;
        BigDecimal hours = BigDecimal.ONE;
        if (b.getPreferredStartTime() != null && b.getPreferredEndTime() != null
                && b.getPreferredEndTime().isAfter(b.getPreferredStartTime())) {
            long minutes = Duration.between(b.getPreferredStartTime(), b.getPreferredEndTime()).toMinutes();
            hours = BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
        }
        return amount.multiply(hours).multiply(BigDecimal.valueOf(Math.max(1, days))).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * On approval: the quoted fee becomes a BOOKING_FEE charge on the renter's live
     * lease of the booked-from unit, approved (posted) at once. No live lease with a
     * fee to charge refuses the approval.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void chargeOnApproval(BookingRequest b, String what, LocalDate on) {
        if (b.getFeeAmount() == null || b.getFeeAmount().signum() <= 0 || b.getChargeId() != null) return;
        Lease lease = leases.findByUnitIdAndStatusIn(b.getUnitId(), LIVE).stream()
                .filter(l -> Objects.equals(l.getTenantId(), b.getTenantId()))
                .filter(l -> l.getRenter() != null && Objects.equals(l.getRenter().getUserId(), b.getRenterUserId()))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "The renter has no live lease on this unit to charge the booking fee to.", "booking.noLease", Map.of()));
        String description = "Booking fee – " + what + (b.getPreferredDate() == null ? "" : " on " + b.getPreferredDate());
        PenaltyAssessmentDTO c = charges.proposeFromSource(new ProposePenaltyRequest(lease.getId(), null,
                PenaltyReason.BOOKING_FEE, b.getFeeAmount(), description, LocalDate.now(), null), null, SOURCE, b.getId(), true);
        charges.approveBySystem(c.id(), on);
        b.setChargeId(c.id());
    }

    /** A booking cancelled before its slot: its fee is reversed (with its VAT, on a credit note). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reverseOnCancel(BookingRequest b, LocalDate on) {
        if (b.getChargeId() == null) return;
        PenaltyAssessmentStatus status = charges.statusOf(b.getChargeId());
        // PR #361 R1 P1-1: the fee was written off as a bad debt — the booking stands.
        if (status == PenaltyAssessmentStatus.WRITTEN_OFF) {
            throw new BusinessRuleViolationException("This booking's fee was written off as a bad debt; it can no longer be"
                    + " cancelled.", "booking.feeWrittenOff", Map.of());
        }
        if (status == PenaltyAssessmentStatus.APPROVED) {
            charges.reverseBySystem(b.getChargeId(), on, "Booking cancelled before its slot");
        }
    }

    /** Whether the slot has not started yet. */
    public static boolean beforeSlot(BookingRequest b, LocalDateTime now) {
        if (b.getPreferredDate() == null) return true;
        LocalDateTime start = b.getPreferredDate().atTime(b.getPreferredStartTime() != null
                ? b.getPreferredStartTime() : java.time.LocalTime.MIN);
        return now.isBefore(start);
    }
}
