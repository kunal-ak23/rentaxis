package com.datagami.rentaxis.core.service.rating;

import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.MeetingStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyAssessmentStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import com.datagami.rentaxis.domain.entity.enums.RenewalOutcome;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Plain rows for one renter; input of the pure {@link RenterRatingEngine}. */
public record RenterHistory(UUID renterId,
                            List<LeaseRow> leases, List<ChequeRow> cheques, List<PenaltyRow> penalties,
                            List<WriteOffRow> writeOffs, List<LeaseEventRow> leaseEvents,
                            List<OpportunityRow> opportunities, List<MeetingRow> meetings,
                            List<AdjustmentRow> adjustments) {

    public record LeaseRow(UUID id, LeaseStatus status, LocalDate startDate, LocalDate endDate,
                           UUID renewedFromLeaseId) {}

    public record ChequeRow(UUID id, UUID leaseId, ChequeStatus status, LocalDate chequeDate, LocalDate clearedAt,
                            LocalDate bouncedAt, LocalDate importedBouncedOn, ChequeFailureReason failureReason) {}

    /** sourceChequeId is the penalty's linked cheque (penalty_assessments.cheque_id, same tenant), else null. */
    public record PenaltyRow(UUID id, UUID leaseId, PenaltyReason reason, PenaltyAssessmentStatus status,
                             LocalDate incidentDate, UUID sourceChequeId) {}

    public record WriteOffRow(UUID id, UUID leaseId, String status, LocalDate writeOffDate) {}

    public record LeaseEventRow(UUID leaseId, String previousState, String newState, Instant createdAt) {}

    public record OpportunityRow(UUID id, UUID leaseId, RenewalIntent intent, Instant intentCapturedAt,
                                 RenewalOutcome outcome, Instant closedAt) {}

    public record MeetingRow(UUID id, UUID leaseId, MeetingStatus status, Instant slotStart) {}

    public record AdjustmentRow(UUID id, int points, String reason, UUID createdBy, Instant createdAt) {}
}
