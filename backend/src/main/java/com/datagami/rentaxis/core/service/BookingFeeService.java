package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.penalty.PenaltyAssessmentDTO;
import com.datagami.rentaxis.api.dto.penalty.ProposePenaltyRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.IncomeLeafRule;
import com.datagami.rentaxis.core.service.penalty.PenaltyAssessmentService;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyAssessmentStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.entity.enums.SettlementStatus;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.LeaseSettlementRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotRepository;
import com.datagami.rentaxis.domain.repository.PropertyAmenityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * F14-50: booking fees. An amenity is free, a fixed fee per booking, a fee per hour
 * or a fee per day; a parking spot is free, a fixed fee per booking, a fee per day
 * or a fee per month. The fee is quoted when the renter asks (the portal shows it
 * first) and becomes a BOOKING_FEE charge on the renter's lease when the booking is
 * approved, posted at once as a journal voucher (spec 2026-11-19 §3): Dr the
 * receivable, Cr the bay's or amenity's income leaf (see {@link #incomeAccountFor}),
 * VAT per F14-30. A booking cancelled before its slot has the charge reversed.
 */
@Service
public class BookingFeeService {

    public static final String SOURCE = "BOOKING";
    public static final Set<String> TYPES = Set.of("FREE", "PER_BOOKING", "PER_HOUR", "PER_DAY", "PER_MONTH");

    private static final EnumSet<LeaseStatus> LIVE = EnumSet.of(LeaseStatus.ACTIVE, LeaseStatus.NOTICE_GIVEN,
            LeaseStatus.EXPIRED, LeaseStatus.ON_HOLD, LeaseStatus.RENEWED);

    private final PenaltyAssessmentService charges;
    private final LeaseRepository leases;
    private final ParkingSpotRepository spots;
    private final PropertyAmenityRepository amenities;
    private final AccountRepository accounts;
    private final AccountResolver accountResolver;
    private final LeaseSettlementRepository settlements;

    public BookingFeeService(PenaltyAssessmentService charges, LeaseRepository leases, ParkingSpotRepository spots,
                             PropertyAmenityRepository amenities, AccountRepository accounts,
                             AccountResolver accountResolver, LeaseSettlementRepository settlements) {
        this.charges = charges;
        this.leases = leases;
        this.spots = spots;
        this.amenities = amenities;
        this.accounts = accounts;
        this.accountResolver = accountResolver;
        this.settlements = settlements;
    }

    /** Validates a fee setting; PER_HOUR is for amenities only, PER_MONTH for parking spots only. */
    public static void validate(String type, BigDecimal amount, boolean parking) {
        String t = type == null ? "FREE" : type;
        if (!TYPES.contains(t) || (parking && "PER_HOUR".equals(t))
                || (!parking && "PER_MONTH".equals(t))) {
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

    /**
     * Quotes the booking and keeps what it was quoted on (spec 2026-11-19 §6): the fee
     * amount, and the fee type and unit amount as a snapshot, so a later change to the
     * price does not change the credit on an early release.
     */
    public static void applyQuote(PropertyAmenity a, BookingRequest b) {
        applyQuote(a.getFeeType(), a.getFeeAmount(), b);
    }

    public static void applyQuote(ParkingSpot s, BookingRequest b) {
        applyQuote(s.getFeeType(), s.getFeeAmount(), b);
    }

    private static void applyQuote(String type, BigDecimal amount, BookingRequest b) {
        b.setFeeAmount(quote(type, amount, b));
        b.setFeeType(type == null ? "FREE" : type);
        b.setFeeUnitAmount(amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP));
    }

    /** The fee types an early release credits pro rata (spec 2026-11-19 §5): parking by the day or the month. */
    private static final Set<String> PRO_RATA = Set.of("PER_DAY", "PER_MONTH");

    /**
     * Spec 2026-11-19 §6: the net credit for the unused part of a parking booking released
     * on {@code releaseDay} (that day counts as used). The used part is the same
     * {@link #quote} as at approval, from the start to the release day; the credit is the
     * fee charged less that, kept within [0, feeAmount]. Nothing is credited for a
     * PER_BOOKING (or any other) fee, for a booking without a snapshot, or for a release
     * on or after the end date; a release before the start uses nothing.
     */
    public static BigDecimal creditForRelease(String feeType, BigDecimal unitAmount, BigDecimal feeAmount,
                                              LocalDate start, LocalDate end, LocalDate releaseDay) {
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        if (feeType == null || !PRO_RATA.contains(feeType) || unitAmount == null || unitAmount.signum() <= 0
                || feeAmount == null || feeAmount.signum() <= 0 || start == null || end == null || releaseDay == null
                || !releaseDay.isBefore(end)) {
            return zero;
        }
        BigDecimal fee = feeAmount.setScale(2, RoundingMode.HALF_UP);
        if (releaseDay.isBefore(start)) return fee;
        BigDecimal used = quote(feeType, unitAmount, start, releaseDay, null, null);
        BigDecimal credit = fee.subtract(used);
        if (credit.signum() < 0) return zero;
        return credit.min(fee);
    }

    /** The request's dates and times, quoted by fee type as the overload below describes. */
    static BigDecimal quote(String type, BigDecimal amount, BookingRequest b) {
        return quote(type, amount, b.getPreferredDate(), b.getPreferredEndDate(), b.getPreferredStartTime(), b.getPreferredEndTime());
    }

    /**
     * PER_HOUR: the slot's hours on each day it covers (no times counts one hour a day). PER_DAY: amount
     * times the days covered, both ends counted. PER_MONTH: whole months from the start date, then the
     * leftover days at the day rate of the month they fall in. A null end means a single day.
     */
    public static BigDecimal quote(String type, BigDecimal amount, LocalDate start, LocalDate end, LocalTime from, LocalTime to) {
        if (type == null || "FREE".equals(type) || amount == null) return BigDecimal.ZERO.setScale(2);
        if ("PER_BOOKING".equals(type)) return amount.setScale(2, RoundingMode.HALF_UP);
        LocalDate last = end != null ? end : start;
        if ("PER_MONTH".equals(type)) {
            if (start == null) return amount.setScale(2, RoundingMode.HALF_UP);
            long n = wholeMonths(start, last);
            LocalDate rest = start.plusMonths(n);
            BigDecimal total = amount.multiply(BigDecimal.valueOf(n));
            if (!rest.isAfter(last)) {
                long days = ChronoUnit.DAYS.between(rest, last) + 1;
                total = total.add(amount.multiply(BigDecimal.valueOf(days))
                        .divide(BigDecimal.valueOf(rest.lengthOfMonth()), 10, RoundingMode.HALF_UP));
            }
            return total.setScale(2, RoundingMode.HALF_UP);
        }
        long days = start == null ? 1 : ChronoUnit.DAYS.between(start, last) + 1;
        if ("PER_DAY".equals(type)) {
            return amount.multiply(BigDecimal.valueOf(Math.max(1, days))).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal hours = BigDecimal.ONE;
        if (from != null && to != null && to.isAfter(from)) {
            long minutes = Duration.between(from, to).toMinutes();
            hours = BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
        }
        return amount.multiply(hours).multiply(BigDecimal.valueOf(Math.max(1, days))).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * PR #468 review I1: the whole months from {@code start} that end on or before {@code last}.
     * Whole month k ends the day before {@code start.plusMonths(k)} (so a 31 Jan start has its
     * first month end on 27 Feb, as before). Worked out from the calendar-month difference and
     * nudged by a step or two, never counted one month at a time, so a far-off end date costs
     * nothing. Zero when {@code last} is before the first whole month ends.
     */
    static long wholeMonths(LocalDate start, LocalDate last) {
        if (last.isBefore(start)) return 0;
        long n = Math.max(0, ChronoUnit.MONTHS.between(start, last));
        // plusMonths clamps to the month end, so MONTHS.between can be a month off either way.
        while (n > 0 && monthEnd(start, n).isAfter(last)) n--;
        while (!monthEnd(start, n + 1).isAfter(last)) n++;
        return n;
    }

    /** The last day of whole month {@code k} counted from {@code start}. */
    private static LocalDate monthEnd(LocalDate start, long k) {
        return start.plusMonths(k).minusDays(1);
    }

    /**
     * Spec 2026-11-19 §2: the income account a booking's fee credits. The bay's or
     * amenity's own leaf when it names one (checked again here: it may have been
     * deactivated or moved since it was picked, and then the approval is refused with
     * {@link IncomeLeafRule#CODE}); otherwise the property's PARKING_INCOME for a bay,
     * and for an amenity the property's AMENITY_INCOME. Either falls back to OTHER_INCOME
     * while the property has no such leaf yet ("Generate missing accounts" not run), as
     * every booking fee posted before this feature (PR #468 review m5): approval never
     * fails for a missing parking or amenity leaf while OTHER_INCOME is mapped.
     *
     * <p>{@code resolveOrNull}, never {@code resolve} inside a try/catch: a resolver
     * exception would mark the approval's transaction rollback-only (see
     * {@link AccountResolver#resolveOrNull}).</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID incomeAccountFor(BookingRequest b, UUID propertyId) {
        UUID own = null;
        if (b.getParkingSpotId() != null) {
            own = spots.findById(b.getParkingSpotId()).map(ParkingSpot::getIncomeAccountId).orElse(null);
        } else if (b.getAmenityId() != null) {
            own = amenities.findById(b.getAmenityId()).map(PropertyAmenity::getIncomeAccountId).orElse(null);
        }
        if (own != null) {
            Account account = accounts.findByIdScopedToTenant(own)
                    .orElseThrow(() -> new BusinessRuleViolationException("The booking's income account was not found",
                            IncomeLeafRule.CODE, Map.of()));
            IncomeLeafRule.require(account, propertyId);
            return account.getId();
        }
        AccountRole role = b.getResourceType() == BookingResourceType.PARKING_SPOT
                ? AccountRole.PARKING_INCOME : AccountRole.AMENITY_INCOME;
        Account leaf = accountResolver.resolveOrNull(role, propertyId);
        if (leaf != null) return leaf.getId();
        return accountResolver.resolve(AccountRole.OTHER_INCOME, propertyId).getId();
    }

    /**
     * The renter's live lease on the unit, in the tenant; {@code booking.noLease} when there is none.
     * Shared by the charge on approval and the staff pre-check before a booking is created for a renter.
     */
    public Lease requireLiveLease(UUID tenantId, UUID unitId, UUID renterUserId) {
        return leases.findByUnitIdAndStatusIn(unitId, LIVE).stream()
                .filter(l -> Objects.equals(l.getTenantId(), tenantId))
                .filter(l -> l.getRenter() != null && Objects.equals(l.getRenter().getUserId(), renterUserId))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "The renter has no live lease on this unit to charge the booking fee to.", "booking.noLease", Map.of()));
    }

    /**
     * On approval: the quoted fee becomes a BOOKING_FEE charge on the renter's live
     * lease of the booked-from unit, approved (posted) at once as a journal voucher to
     * {@link #incomeAccountFor}. No live lease with a fee to charge refuses the approval.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void chargeOnApproval(BookingRequest b, String what, LocalDate on) {
        if (b.getFeeAmount() == null || b.getFeeAmount().signum() <= 0 || b.getChargeId() != null) return;
        Lease lease = requireLiveLease(b.getTenantId(), b.getUnitId(), b.getRenterUserId());
        // Before anything is saved: a refused income account leaves no charge behind.
        UUID incomeAccountId = incomeAccountFor(b, b.getPropertyId());
        LocalDate start = b.getPreferredDate();
        LocalDate end = b.getPreferredEndDate();
        String description = "Booking fee - " + what + (start == null ? ""
                : " " + start + (end != null && !end.equals(start) ? " to " + end : ""));
        PenaltyAssessmentDTO c = charges.proposeFromSource(new ProposePenaltyRequest(lease.getId(), null,
                PenaltyReason.BOOKING_FEE, b.getFeeAmount(), description, on, null), null, SOURCE, b.getId(), true,
                incomeAccountId);
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

    /** Ruling R12: a collection row in one of these has the renter's money (or a payment in flight). */
    private static final EnumSet<com.datagami.rentaxis.domain.entity.enums.ChequeStatus> PAID = EnumSet.of(
            com.datagami.rentaxis.domain.entity.enums.ChequeStatus.DEPOSITED,
            com.datagami.rentaxis.domain.entity.enums.ChequeStatus.CLEARED,
            com.datagami.rentaxis.domain.entity.enums.ChequeStatus.ONLINE_PENDING);

    /** Whether the booking's fee has been paid (its collection row is deposited, cleared or paying online). */
    public boolean feePaid(BookingRequest b) {
        if (b.getChargeId() == null) return false;
        return PAID.contains(charges.collectionStatusOf(b.getChargeId()));
    }

    /**
     * Follow-up ruling R12 (101-d): a renter cannot cancel a booking whose fee is paid;
     * money movements stay in staff hands, who cancel it with a credit note.
     */
    public void requireUnpaidForRenter(BookingRequest b) {
        if (feePaid(b)) {
            throw new BusinessRuleViolationException("The fee for this booking is paid, so it cannot be cancelled here."
                    + " Ask the management office to cancel it.", "booking.feePaid", Map.of());
        }
    }

    /**
     * Ruling R12: staff cancel an approved booking. An unpaid fee is reversed as on a
     * renter's cancel; a paid one is reversed too and the payment stays as a credit on
     * the renter's account (with a tax credit note for its VAT).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void creditOnStaffCancel(BookingRequest b, LocalDate on, String reason) {
        if (b.getChargeId() == null) return;
        PenaltyAssessmentStatus status = charges.statusOf(b.getChargeId());
        if (status == PenaltyAssessmentStatus.WRITTEN_OFF) {
            throw new BusinessRuleViolationException("This booking's fee was written off as a bad debt; it can no longer be"
                    + " cancelled.", "booking.feeWrittenOff", Map.of());
        }
        if (status != PenaltyAssessmentStatus.APPROVED) return;
        // Review P3-5: a deposited cheque or a pending online payment may still bounce or
        // fail, and its PDR reversal would then re-debit the renter for a cancelled fee.
        // Cancel once the payment has cleared (credit) or failed (plain reversal).
        com.datagami.rentaxis.domain.entity.enums.ChequeStatus row = charges.collectionStatusOf(b.getChargeId());
        if (row == com.datagami.rentaxis.domain.entity.enums.ChequeStatus.DEPOSITED
                || row == com.datagami.rentaxis.domain.entity.enums.ChequeStatus.ONLINE_PENDING) {
            throw new BusinessRuleViolationException("The fee's payment is still being collected; cancel the booking"
                    + " once it has cleared or bounced.", "booking.feeInFlight", Map.of());
        }
        if (feePaid(b)) {
            charges.creditCollected(b.getChargeId(), on, reason);
        } else {
            charges.reverse(b.getChargeId(), on, reason);
        }
    }

    /**
     * Spec 2026-11-19 §6: a parking booking released early has the unused part of its fee
     * credited ({@link #creditForRelease}), on {@code on}, through
     * {@link PenaltyAssessmentService#creditPart}. The credit is posted only while the fee's
     * collection row is settled one way or the other: no row, unpaid (REGISTERED) or paid
     * (CLEARED), so the receivable still equals the row (ledger rule 3). A payment in flight
     * refuses the release ({@code booking.feeInFlight}, as for staff cancel); any other row
     * (bounced, replaced, returned, transferred, draft) refuses it with
     * {@code booking.feeRowNotSettled}: the row, or its replacement, would still ask for the
     * full fee after the receivable was credited (final review I2). A fee written off or
     * reversed, or whose collection row was cancelled elsewhere, stands, and the release goes
     * ahead with no credit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void creditOnRelease(BookingRequest b, LocalDate on) {
        if (b.getChargeId() == null) return;
        if (charges.statusOf(b.getChargeId()) != PenaltyAssessmentStatus.APPROVED) return;
        BigDecimal credit = creditForRelease(b.getFeeType(), b.getFeeUnitAmount(), b.getFeeAmount(),
                b.getPreferredDate(), b.getPreferredEndDate(), on);
        if (credit.signum() <= 0) return;
        requireLeaseNotSettled(charges.leaseOf(b.getChargeId()));
        com.datagami.rentaxis.domain.entity.enums.ChequeStatus row = charges.collectionStatusOf(b.getChargeId());
        if (row == com.datagami.rentaxis.domain.entity.enums.ChequeStatus.DEPOSITED
                || row == com.datagami.rentaxis.domain.entity.enums.ChequeStatus.ONLINE_PENDING) {
            throw new BusinessRuleViolationException("The fee's payment is still being collected; release the booking"
                    + " once it has cleared.", "booking.feeInFlight", Map.of());
        }
        if (row == com.datagami.rentaxis.domain.entity.enums.ChequeStatus.CANCELLED) return;
        if (row != null && row != com.datagami.rentaxis.domain.entity.enums.ChequeStatus.REGISTERED
                && row != com.datagami.rentaxis.domain.entity.enums.ChequeStatus.CLEARED) {
            throw new BusinessRuleViolationException("The fee's payment is " + row.name().toLowerCase()
                    + "; settle or replace it before the booking is released.", "booking.feeRowNotSettled",
                    Map.of());
        }
        charges.creditPart(b.getChargeId(), credit, on, "Parking released early on " + on + ": unused days credited");
    }

    /**
     * PR #468 review m2/m3: no early-release credit once the renter's contract is CLOSED or
     * its settlement finalised. The settlement counted the fee in full; a credit now would
     * leave a renter credit (or a lowered row) behind a figure that was paid out on, with no
     * door left to refund it. Refused with {@code booking.leaseSettled}, so the office settles
     * the difference by hand rather than the bay being released with a silent credit.
     */
    private void requireLeaseNotSettled(Lease lease) {
        if (lease == null) return;
        boolean finalised = settlements.findByLeaseId(lease.getId())
                .map(s -> s.getStatus() == SettlementStatus.FINALIZED).orElse(false);
        if (lease.getStatus() == LeaseStatus.CLOSED || finalised) {
            throw new BusinessRuleViolationException("The renter's contract is settled, so the unused part of the"
                    + " fee cannot be credited on release. Adjust it with the settlement.", "booking.leaseSettled",
                    Map.of());
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
