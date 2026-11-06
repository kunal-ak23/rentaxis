package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.penalty.PenaltyAssessmentDTO;
import com.datagami.rentaxis.api.dto.penalty.ProposePenaltyRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.FiguresChangedException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.penalty.PenaltyAssessmentService;
import com.datagami.rentaxis.core.service.recognition.ProrationEngine;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A lease on hold whose renter turned out to have left (client call, Nov 2026). The
 * days from the old end to the move-out date are charged at the old day rate as a
 * use-and-occupation charge — through the charges pipeline, approved and dated
 * today, so nothing lands in a closed month — and the lease becomes EXPIRED with
 * the unit released.
 *
 * <p>The other way out of ON_HOLD, the renter staying, is an ordinary renewal that
 * starts the day after the old end ({@code LeaseService.renewalStartsTooEarly}),
 * posted with income starting in the current month ({@link BackdatedIncome}).</p>
 */
@Service
public class HoldoverService {

    public static final String SOURCE = "HOLDOVER";
    public static final String CHANGED = "holdover.changed";
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MMYYYY = DateTimeFormatter.ofPattern("MM/yyyy");

    private final LeaseRepository leases;
    private final LeaseLineRepository lines;
    private final LeaseAccessPolicy access;
    private final LeaseService leaseService;
    private final PenaltyAssessmentService charges;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private Clock clock = Clock.systemDefaultZone();

    public HoldoverService(LeaseRepository leases, LeaseLineRepository lines, LeaseAccessPolicy access,
                           LeaseService leaseService, PenaltyAssessmentService charges) {
        this.leases = leases;
        this.lines = lines;
        this.access = access;
        this.leaseService = leaseService;
        this.charges = charges;
    }

    public record Month(String month, int days, BigDecimal amount) { }

    /** The hold from the day after the old end to the move-out date, priced at the old day rate. */
    public record VacatePreview(LocalDate holdFrom, LocalDate movedOutOn, int days, BigDecimal dayRate,
                                List<Month> months, BigDecimal amount) { }

    public record VacateRequest(LocalDate movedOutOn, BigDecimal expectedAmount) { }

    public record VacateResult(UUID leaseId, BigDecimal amount, UUID chargeId) { }

    @Transactional(readOnly = true)
    public VacatePreview previewVacate(UUID leaseId, LocalDate movedOutOn) {
        Lease lease = onHold(leaseId);
        access.requireReadable(lease);
        return price(lease, movedOutOn);
    }

    @Transactional
    public VacateResult vacate(UUID leaseId, VacateRequest request) {
        if (request == null || request.movedOutOn() == null) {
            throw new BusinessRuleViolationException("Enter the date the renter moved out.");
        }
        Lease lease = onHold(leaseId);
        access.requireManageable(lease);
        VacatePreview p = price(lease, request.movedOutOn());
        if (request.expectedAmount() != null && request.expectedAmount().compareTo(p.amount()) != 0) {
            throw new FiguresChangedException(CHANGED, "The use-and-occupation charge is now "
                    + p.amount().toPlainString() + ", not " + request.expectedAmount().toPlainString()
                    + ". Review it again; nothing was charged.");
        }
        UUID chargeId = null;
        if (p.amount().signum() > 0) {
            String description = "Use and occupation " + p.holdFrom().format(DMY) + " – " + p.movedOutOn().format(DMY)
                    + " (" + p.days() + " days at " + p.dayRate().setScale(2, RoundingMode.HALF_UP).toPlainString()
                    + "/day)";
            PenaltyAssessmentDTO proposed = charges.proposeFromSource(new ProposePenaltyRequest(leaseId, null,
                            PenaltyReason.USE_AND_OCCUPATION, p.amount(), description, p.movedOutOn(), null),
                    currentUserId(), SOURCE, leaseId, false);
            chargeId = charges.approve(proposed.id(), today()).id();
        }
        leaseService.markHoldoverVacated(leaseId, p.movedOutOn());
        return new VacateResult(leaseId, p.amount(), chargeId);
    }

    VacatePreview price(Lease lease, LocalDate movedOutOn) {
        LocalDate holdFrom = lease.getEndDate().plusDays(1);
        if (movedOutOn == null) throw new BusinessRuleViolationException("Enter the date the renter moved out.");
        if (movedOutOn.isAfter(today())) {
            throw new BusinessRuleViolationException("The move-out date cannot be in the future.");
        }
        if (movedOutOn.isBefore(lease.getEndDate())) {
            throw new BusinessRuleViolationException("The move-out date cannot be before the old term ended on "
                    + lease.getEndDate().format(DMY) + ".");
        }
        BigDecimal rate = oldDayRate(lease);
        if (movedOutOn.isBefore(holdFrom)) {
            return new VacatePreview(holdFrom, movedOutOn, 0, rate, List.of(), BigDecimal.ZERO.setScale(2));
        }
        int days = ProrationEngine.daysInclusive(holdFrom, movedOutOn);
        BigDecimal amount = rate.multiply(BigDecimal.valueOf(days)).setScale(2, RoundingMode.HALF_UP);
        List<Month> months = ProrationEngine.slice(amount, holdFrom, movedOutOn).stream()
                .map(s -> new Month(s.periodStart().format(MMYYYY), s.days(), s.amount())).toList();
        return new VacatePreview(holdFrom, movedOutOn, days, rate, months, amount);
    }

    /**
     * The rent's day rate on the day the term ended: each RENT line covering it,
     * net ÷ the days of its own window (the per-day rule recognition uses).
     */
    BigDecimal oldDayRate(Lease lease) {
        BigDecimal rate = BigDecimal.ZERO;
        LocalDate last = lease.getEndDate();
        for (LeaseLine line : lines.findByLease_IdOrderBySeqNoAsc(lease.getId())) {
            boolean rent = line.getChargeType() != null && line.getChargeType().getBehaviour() == ChargeBehaviour.RENT;
            BigDecimal net = line.getNetAmount() == null ? BigDecimal.ZERO : line.getNetAmount();
            if (!rent || net.signum() <= 0) continue;
            LocalDate from = line.getPeriodStart() != null ? line.getPeriodStart() : lease.getStartDate();
            LocalDate to = line.getPeriodEnd() != null ? line.getPeriodEnd() : LeaseService.originalTermEnd(lease);
            if (from == null || to == null || to.isBefore(from) || last.isBefore(from) || last.isAfter(to)) continue;
            rate = rate.add(ProrationEngine.dayRate(net, from, to));
        }
        if (rate.signum() == 0) {
            throw new BusinessRuleViolationException("This lease has no rent running on its last day ("
                    + last.format(DMY) + "), so there is no day rate to charge the held-over days at.");
        }
        return rate;
    }

    private Lease onHold(UUID leaseId) {
        Lease lease = leases.findByIdScopedToTenant(leaseId).orElseThrow(() -> new NotFoundException("Lease not found"));
        if (lease.getStatus() != LeaseStatus.ON_HOLD) {
            throw new BusinessRuleViolationException("This lease is not on hold.", "holdover.notOnHold",
                    Map.of("status", String.valueOf(lease.getStatus())));
        }
        if (lease.getEndDate() == null) throw new BusinessRuleViolationException("This lease has no end date.");
        return lease;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
