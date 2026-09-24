package com.datagami.rentaxis.core.service.report.statement;

import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository.MovementRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The ledger questions the statement sections ask, all for one effective property
 * ({@code coalesce(line property, account property)}), all read-only.
 */
@Component
public class StatementLedger {

    /** Before any book this system can hold. */
    static final LocalDate BEGINNING = LocalDate.of(1900, 1, 1);

    private final JournalLineRepository lines;
    private final AccountResolver resolver;
    private final AccountRepository accounts;

    public StatementLedger(JournalLineRepository lines, AccountResolver resolver, AccountRepository accounts) {
        this.lines = lines;
        this.resolver = resolver;
        this.accounts = accounts;
    }

    /** The leaves these roles resolve to for the property; a role with no mapping is left out. */
    public List<UUID> accountsFor(UUID propertyId, AccountRole... roles) {
        Set<UUID> out = new LinkedHashSet<>();
        for (AccountRole role : roles) {
            Account a = resolver.resolveOrNull(role, propertyId);
            if (a != null) out.add(a.getId());
        }
        return new ArrayList<>(out);
    }

    /**
     * Every bank and cash leaf of the tenant ({@code ChequeService.isSettlementAccount}),
     * cached per statement: a cheque is banked, and a refund paid, from whichever one
     * the clerk picked, not only the property's BANK role leaf.
     */
    public List<UUID> settlementAccounts(StatementContext ctx) {
        return ctx.cached("settlementAccounts", () -> accounts.findAll().stream()
                .filter(a -> ctx.tenantId().equals(a.getTenantId()))
                .filter(ChequeService::isSettlementAccount)
                .map(Account::getId).toList());
    }

    public List<MovementRow> movement(UUID tenantId, UUID propertyId, List<UUID> accountIds, LocalDate from, LocalDate to) {
        if (accountIds.isEmpty()) return List.of();
        return lines.movementForProperty(tenantId, accountIds, propertyId, from, to);
    }

    /** Σ(credit − debit) over the rows the filter keeps. */
    public static BigDecimal creditNet(List<MovementRow> rows, Predicate<MovementRow> keep) {
        return rows.stream().filter(keep).map(r -> r.getCredit().subtract(r.getDebit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
