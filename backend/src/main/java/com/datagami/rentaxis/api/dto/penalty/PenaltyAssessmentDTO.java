package com.datagami.rentaxis.api.dto.penalty;

import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyAssessmentStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape of one penalty assessment (spec §7.3).
 *
 * <p>{@code collectionStatus} is the register row's own status, denormalised so
 * the worklist can say "approved, not yet collected" without a second fetch per
 * row. It is null until approval creates the row.</p>
 */
public record PenaltyAssessmentDTO(UUID id,
                                   UUID leaseId,
                                   UUID chequeId,
                                   String chequeNumber,
                                   UUID renterId,
                                   String renterName,
                                   UUID propertyId,
                                   String propertyName,
                                   PenaltyReason reason,
                                   BigDecimal amount,
                                   String description,
                                   java.time.LocalDate incidentDate,
                                   PenaltyAssessmentStatus status,
                                   UUID proposedBy,
                                   Instant proposedAt,
                                   UUID approvedBy,
                                   Instant approvedAt,
                                   UUID journalId,
                                   UUID collectionChequeId,
                                   ChequeStatus collectionStatus,
                                   String resolutionNote,
                                   /* F14-31: the description as a code + args, for the reader's language. */
                                   String descriptionCode,
                                   java.util.Map<String, String> descriptionArgs,
                                   /* F14-28: the amount first proposed, when it was reduced. */
                                   BigDecimal proposedAmount) {
}
