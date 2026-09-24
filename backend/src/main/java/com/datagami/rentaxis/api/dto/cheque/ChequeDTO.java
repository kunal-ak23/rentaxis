package com.datagami.rentaxis.api.dto.cheque;

import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;

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
 *
 * <p>{@code leaseStatus} is on the row for the same reason: <b>which actions this
 * row still admits is a property of the contract, not of the instrument</b>. A
 * CLOSED lease refuses every transition and an ended one refuses new grid rows, so
 * a register that knows only {@code status} keeps offering Deposit and Clear on
 * rows the server will refuse — the client cannot tell a REGISTERED cheque on a
 * running tenancy from one on a contract that has been settled and shut. It is the
 * lease's own status, never re-derived.</p>
 */
public record ChequeDTO(UUID id,
                        UUID leaseId,
                        LeaseStatus leaseStatus,
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
                        int daysOverdue,
                        /* The VAT inside {@code amount}, and the net it is charged on (spec 2026-09-24 §1). */
                        BigDecimal vatAmount,
                        BigDecimal vatTaxableAmount,
                        /* What the row collects (RENT, FEE, DEPOSIT, MIXED), or null when it never said. */
                        com.datagami.rentaxis.domain.entity.enums.ChequeRowKind rowKind,
                        /* F14-52: a BOUNCED row whose debt the ledger no longer carries (a settlement or
                           payment absorbed it): not overdue, nothing to replace. */
                        boolean ledgerSettled,
                        /* F14-24/F14-62: the receipt number (RR-yy/n) given when the money landed;
                           null for a row not cleared, or cleared before the series existed. */
                        String receiptNumber) {
}
