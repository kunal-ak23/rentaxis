package com.datagami.rentaxis.core.service.report.tenantledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.report.grid.ReportLabels;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What the Tenant Ledger (R1) is asked for (reports spec §2).
 *
 * @param from            first day of the period; required
 * @param to              last day of the period; required, not before {@code from}
 * @param propertyId      one property, or {@code null} for every property the caller may see
 * @param accountIds      the ledger accounts; empty means every non-group RECEIVABLE leaf
 * @param renterId        one renter, or {@code null} for all (the "No tenant" group included)
 * @param leaseId         one contract, or {@code null} for all
 * @param onlyWithBalance keep only the tenants whose closing at {@code to} is not zero
 * @param hideReversed    leave out both legs of each reversal (spec §2: shown by default)
 * @param lang            {@code en} or {@code ar}; anything else reads English
 */
public record TenantLedgerFilter(LocalDate from, LocalDate to, UUID propertyId, List<UUID> accountIds, UUID renterId,
                                 UUID leaseId, boolean onlyWithBalance, boolean hideReversed, String lang) {

    public TenantLedgerFilter {
        if (from == null || to == null) {
            throw new BusinessRuleViolationException("Choose a period: 'from' and 'to' are required");
        }
        if (to.isBefore(from)) {
            throw new BusinessRuleViolationException("'to' must not be before 'from'");
        }
        accountIds = accountIds == null ? List.of() : accountIds.stream().filter(Objects::nonNull).distinct().toList();
        lang = ReportLabels.lang(lang);
    }
}
