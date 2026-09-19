package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One line on the renter's "my payments" screen: a row of the cheque register,
 * read from the renter's side (spec §9.3, §11 "Renter portal").
 *
 * <p>It is not {@code ChequeDTO}. The register's own wire shape carries journal
 * ids, the debit account the landlord banked into and the replacement chain —
 * none of which is the renter's business — and it carries none of the three
 * things this screen exists to show: whether the row can be paid right now, how
 * much of the lease's approved penalties is still outstanding, and whether the
 * property takes online payments at all.</p>
 *
 * @param installmentNumber the row's position on the lease's schedule ({@code seqNo}).
 * @param dueDate the date on the instrument — when the money is owed.
 * @param payable what the "Pay now" button charges: the amount for a due row the
 *        renter can actually settle, zero otherwise. A BOUNCED row <em>is</em>
 *        payable — {@code createOrder} supersedes it with an online row first.
 * @param penaltyOutstanding Σ of this lease's uncleared approved-penalty collection
 *        rows. Repeated on every row of the lease: it is a property of the lease,
 *        not of the instalment, and the screen shows it as a lease-level banner.
 * @param onlineEnabled the property's {@code RentCollectionSettings.onlinePaymentEnabled}.
 *        A property with no settings row takes online payments — the flag is an
 *        opt-out, and treating an absent row as "disabled" would silently hide the
 *        pay button for every property nobody has configured.
 * @param penaltyAssessmentId set when this row <em>is</em> a penalty collection row,
 *        so the screen can label it as a fine rather than as rent.
 */
public record RenterChequeDTO(UUID id,
                              UUID leaseId,
                              int installmentNumber,
                              LocalDate dueDate,
                              BigDecimal amount,
                              ChequeStatus status,
                              ChequeMode mode,
                              String chequeNumber,
                              String bankName,
                              String narration,
                              String propertyName,
                              String unitIdentifier,
                              String renterName,
                              boolean due,
                              boolean overdue,
                              int daysOverdue,
                              int gracePeriodDays,
                              BigDecimal penaltyOutstanding,
                              BigDecimal payable,
                              boolean onlineEnabled,
                              UUID penaltyAssessmentId,
                              ChequeFailureReason failureReason,
                              LocalDate clearedAt,
                              String statusChangedAt) {
}
