package com.datagami.rentaxis.api.dto.cheque;

import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Wire shape of one row in the cheque register.
 *
 * <p>The {@code propertyName}/{@code unitIdentifier}/{@code renterName} labels are
 * denormalised out of the relations so the register table renders without the
 * client fetching three more resources per row.</p>
 *
 * <p>{@code due}, {@code overdue} and {@code daysOverdue} are computed server-side
 * from {@code ChequeDueRules} against a caller-supplied "today" and the lease's
 * grace period. They are not stored: a client that derived them from
 * {@code chequeDate} alone would ignore grace days and show a renter as late a
 * week early.</p>
 */
public record ChequeDTO(UUID id,
                        UUID leaseId,
                        UUID propertyId,
                        UUID unitId,
                        UUID renterId,
                        String propertyName,
                        String unitIdentifier,
                        String renterName,
                        int seqNo,
                        LocalDate postingDate,
                        String chequeNumber,
                        LocalDate chequeDate,
                        String payeeBank,
                        String payerName,
                        UUID debitAccountId,
                        String debitAccountName,
                        BigDecimal amount,
                        String narration,
                        ChequeMode mode,
                        ChequeStatus status,
                        ChequeFailureReason failureReason,
                        UUID replacesId,
                        UUID replacedById,
                        String imageUrl,
                        LocalDate depositedAt,
                        LocalDate clearedAt,
                        LocalDate bouncedAt,
                        LocalDate returnedAt,
                        UUID pdrJournalId,
                        UUID crtJournalId,
                        UUID cbrJournalId,
                        UUID penaltyAssessmentId,
                        boolean due,
                        boolean overdue,
                        int daysOverdue) {
}
