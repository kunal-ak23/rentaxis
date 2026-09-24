package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;

import java.time.LocalDate;
import java.util.function.Function;

/**
 * Cheque entity to wire shape.
 *
 * <p>Reads the lazy relations, so callers must map inside the transaction that
 * loaded the cheque. {@code unit} and {@code debitAccount} are nullable columns
 * and the replacement chain is empty for most rows, so every relation is read
 * through a null-safe accessor rather than assumed present.</p>
 *
 * <p>{@code today} and {@code graceDays} are arguments, not defaults read here:
 * grace comes from the cheque's own lease and "today" from the caller's clock or
 * an as-of-date report, and baking either in would make the register disagree
 * with the ledger for back-dated views.</p>
 */
public final class ChequeMapper {

    private ChequeMapper() {
    }

    public static ChequeDTO toDto(Cheque c, LocalDate today, int graceDays) {
        return toDto(c, today, graceDays, false);
    }

    /** {@code ledgerSettled}: F14-52, a bounced row the ledger has closed — never overdue. */
    public static ChequeDTO toDto(Cheque c, LocalDate today, int graceDays, boolean ledgerSettled) {
        // daysOverdue is a property of the date alone, so on its own it happily
        // reports 365 for a cheque that cleared a year ago. The wire shape is read
        // by a UI that renders the number next to an "overdue" badge, so it is
        // gated on overdue here rather than in the rule, which other callers use
        // for its unconditional meaning.
        boolean overdue = !ledgerSettled && ChequeDueRules.overdue(c, graceDays, today);
        return new ChequeDTO(
                c.getId(),
                nullSafe(c.getLease(), lease -> lease.getId()),
                // Costs nothing extra: every caller already dereferences the lease
                // to get its grace period (see the graceDays argument, and
                // ChequeQueryService.graceOf), so the association is initialised on
                // every path that reaches here. This adds a field to an object that
                // was already loaded, not a query.
                nullSafe(c.getLease(), Lease::getStatus),
                nullSafe(c.getProperty(), Property::getId),
                nullSafe(c.getUnit(), Unit::getId),
                nullSafe(c.getRenter(), Renter::getId),
                nullSafe(c.getProperty(), Property::getNameEn),
                nullSafe(c.getUnit(), Unit::getUnitNumber),
                nullSafe(c.getRenter(), Renter::getNameEn),
                c.getSeqNo(),
                c.getPostingDate(),
                c.getChequeNumber(),
                c.getChequeDate(),
                c.getPayeeBank(),
                c.getPayerName(),
                nullSafe(c.getDebitAccount(), Account::getId),
                nullSafe(c.getDebitAccount(), Account::getName),
                c.getAmount(),
                c.getNarration(),
                c.getMode(),
                c.getStatus(),
                c.getFailureReason(),
                nullSafe(c.getReplaces(), Cheque::getId),
                nullSafe(c.getReplacedBy(), Cheque::getId),
                c.getImageUrl(),
                c.getDepositedAt(),
                c.getClearedAt(),
                c.getBouncedAt(),
                c.getReturnedAt(),
                c.getPdrJournalId(),
                c.getCrtJournalId(),
                c.getCbrJournalId(),
                c.getPenaltyAssessmentId(),
                ChequeDueRules.due(c, today),
                overdue,
                overdue ? ChequeDueRules.daysOverdue(c, graceDays, today) : 0,
                c.getVatAmount(),
                c.getVatTaxableAmount(),
                c.getRowKind(),
                ledgerSettled);
    }

    private static <T, R> R nullSafe(T source, Function<T, R> get) {
        return source == null ? null : get.apply(source);
    }
}
