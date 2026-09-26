package com.datagami.rentaxis.core.service.penalty;

import com.datagami.rentaxis.domain.entity.PenaltyAssessment;
import com.datagami.rentaxis.domain.entity.enums.PenaltyAssessmentStatus;
import com.datagami.rentaxis.domain.repository.PenaltyAssessmentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * S16-09 / PR #369 R1 P3-5: a penalty still PROPOSED when its lease reaches an end
 * where nothing can be charged any more can never be approved ("charge this penalty
 * through settlement") and would sit in the queue for ever. Every such end closes it as
 * WAIVED, in its own transaction, with a note saying which end and that it was not
 * charged:
 * <ul>
 *   <li>the settlement is finalised (the statement was drawn without it);</li>
 *   <li>the tenancy moves to another unit (the lease left behind takes no charges);</li>
 *   <li>the lease closes with nothing left to collect.</li>
 * </ul>
 * <p>Only the repository — the lease-closing services sit below the penalty service in
 * the dependency graph, and this must not pull the register in behind them.</p>
 */
@Component
public class PenaltyLapse {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);

    /** Why the proposal lapsed. */
    public enum End { SETTLED, TRANSFERRED, CLOSED }

    private final PenaltyAssessmentRepository repository;

    public PenaltyLapse(PenaltyAssessmentRepository repository) {
        this.repository = repository;
    }

    /**
     * @param detail for TRANSFERRED, the unit the tenancy moved to; else ignored
     * @return how many proposals lapsed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int lapse(UUID leaseId, LocalDate on, End end, String detail) {
        int lapsed = 0;
        for (PenaltyAssessment p : repository.findByLease_Id(leaseId)) {
            if (p.getStatus() != PenaltyAssessmentStatus.PROPOSED) continue;
            PenaltyAssessment a = repository.findByIdForUpdate(p.getId()).orElse(null);
            if (a == null || a.getStatus() != PenaltyAssessmentStatus.PROPOSED) continue;
            a.setStatus(PenaltyAssessmentStatus.WAIVED);
            a.setResolutionNote(note(on, end, detail));
            repository.save(a);
            lapsed++;
        }
        return lapsed;
    }

    static String note(LocalDate on, End end, String detail) {
        String d = on == null ? "" : " on " + DAY.format(on);
        return switch (end) {
            case SETTLED -> "Lapsed: not decided before the lease was settled" + d + "; it was not included in the settlement.";
            case TRANSFERRED -> "Lapsed: not decided before the tenancy moved" + (detail == null ? "" : " to " + detail)
                    + d + "; it was not charged.";
            case CLOSED -> "Lapsed: not decided before the lease closed" + d + " with nothing left to collect; it was not charged.";
        };
    }
}
