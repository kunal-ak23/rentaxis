package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.dto.ledger.AccountLedgerDTO;
import com.datagami.rentaxis.api.dto.ledger.LedgerRowDTO;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Vendor;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository.BalanceRow;
import com.datagami.rentaxis.domain.repository.JournalLineRepository.CounterRow;
import com.datagami.rentaxis.domain.repository.JournalLineRepository.LineRow;
import com.datagami.rentaxis.domain.repository.VendorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read side of the ledger (spec §6): one account's ledger with a running balance,
 * the general ledger, a renter's or vendor's ledger, and the trial balance.
 *
 * <p>Every balance is signed debit-positive, so a credit-balance account (advance
 * rent, a deposit) reads negative and the trial balance's debit and credit columns
 * still add up to the same number.</p>
 */
@Service
@Transactional(readOnly = true)
public class LedgerQueryService {

    /** A ledger page is printed, not streamed; beyond this the caller has to narrow the range. */
    public static final int MAX_ROWS = 5000;

    /**
     * Ceiling across a whole multi-account response. One account is capped at MAX_ROWS,
     * but a general ledger over every active leaf would otherwise multiply that by the
     * size of the chart of accounts and build the lot in memory.
     */
    public static final int MAX_TOTAL_ROWS = 20_000;

    public record LedgerFilter(LocalDate from, LocalDate to, UUID propertyId, UUID unitId, UUID leaseId, UUID renterId) {
        LedgerFilter normalised() {
            LocalDate f = from == null ? LocalDate.of(2000, 1, 1) : from;
            LocalDate t = to == null ? LocalDate.of(2099, 12, 31) : to;
            if (t.isBefore(f)) throw new BusinessRuleViolationException("'to' must not be before 'from'");
            return new LedgerFilter(f, t, propertyId, unitId, leaseId, renterId);
        }
    }

    private final JournalLineRepository lines;
    private final AccountRepository accounts;
    private final VendorRepository vendors;

    public LedgerQueryService(JournalLineRepository lines, AccountRepository accounts, VendorRepository vendors) {
        this.lines = lines; this.accounts = accounts; this.vendors = vendors;
    }

    public AccountLedgerDTO accountLedger(UUID accountId, LedgerFilter filter) {
        LedgerFilter f = filter.normalised();
        UUID tenantId = TenantContextHolder.getTenantId();
        Account account = accounts.findById(accountId).orElseThrow(() -> new NotFoundException("Account not found"));
        BigDecimal opening = orZero(lines.balanceBefore(tenantId, accountId, f.from(), f.propertyId(), f.unitId(), f.leaseId(), f.renterId()));
        List<LineRow> raw = lines.ledgerRows(tenantId, accountId, f.from(), f.to(), f.propertyId(), f.unitId(), f.leaseId(), f.renterId(), MAX_ROWS + 1);
        boolean truncated = raw.size() > MAX_ROWS;
        if (truncated) raw = raw.subList(0, MAX_ROWS);

        Map<UUID, String> fallbackParticulars = fallbackParticulars(tenantId, raw, accountId);
        BigDecimal running = opening, totalDr = BigDecimal.ZERO, totalCr = BigDecimal.ZERO;
        List<LedgerRowDTO> rows = new ArrayList<>(raw.size());
        for (LineRow r : raw) {
            running = running.add(r.getDebit()).subtract(r.getCredit());
            totalDr = totalDr.add(r.getDebit()); totalCr = totalCr.add(r.getCredit());
            rows.add(new LedgerRowDTO(r.getEntryId(), r.getEntryNumber(), r.getEntryDate(), r.getDocType(),
                    particular(r, fallbackParticulars),
                    r.getLineNarration() != null ? r.getLineNarration() : r.getEntryNarration(),
                    r.getDebit(), r.getCredit(), running,
                    r.getPropertyId(), r.getUnitId(), r.getLeaseId(), r.getRenterId(), r.getChequeId()));
        }
        return new AccountLedgerDTO(account.getId(), account.getCode(), account.getName(), account.getAccountType().name(),
                opening, rows, totalDr, totalCr, running, truncated, account.getNameAr());
    }

    /**
     * The general ledger spans accounts, so an open-ended range is a different
     * proposition here than on one account: {@code GET /ledger} with no parameters
     * would otherwise mean "every account, this century". An omitted range therefore
     * means the current month to date, and the caller asks for more explicitly.
     */
    public List<AccountLedgerDTO> generalLedger(List<UUID> accountIds, LedgerFilter filter) {
        LocalDate from = filter.from() == null ? LocalDate.now().withDayOfMonth(1) : filter.from();
        LocalDate to = filter.to() == null ? LocalDate.now() : filter.to();
        LedgerFilter f = new LedgerFilter(from, to, filter.propertyId(), filter.unitId(), filter.leaseId(), filter.renterId()).normalised();
        List<UUID> ids = (accountIds == null || accountIds.isEmpty())
                ? lines.activeAccountIds(TenantContextHolder.getTenantId(), f.from(), f.to(), f.propertyId(), f.unitId(), f.leaseId(), f.renterId())
                : accountIds;
        return ledgers(ids, f);
    }

    public List<AccountLedgerDTO> renterLedger(UUID renterId, LocalDate from, LocalDate to) {
        LedgerFilter f = new LedgerFilter(from, to, null, null, null, renterId).normalised();
        List<UUID> ids = lines.activeAccountIds(TenantContextHolder.getTenantId(), f.from(), f.to(), null, null, null, renterId);
        return ledgers(ids, f);
    }

    /**
     * One ledger per account, stopping at {@link #MAX_TOTAL_ROWS} rows in total. The last
     * account admitted is flagged truncated, so a response that ran into the ceiling never
     * reads as a complete set of books.
     */
    private List<AccountLedgerDTO> ledgers(List<UUID> accountIds, LedgerFilter f) {
        List<AccountLedgerDTO> out = new ArrayList<>(accountIds.size());
        int total = 0;
        for (UUID id : accountIds) {
            AccountLedgerDTO ledger = accountLedger(id, f);
            out.add(ledger);
            total += ledger.rows().size();
            if (total >= MAX_TOTAL_ROWS) {
                out.set(out.size() - 1, truncate(ledger));
                break;
            }
        }
        return out.stream().sorted(Comparator.comparing(AccountLedgerDTO::accountCode)).toList();
    }

    private static AccountLedgerDTO truncate(AccountLedgerDTO l) {
        return new AccountLedgerDTO(l.accountId(), l.accountCode(), l.accountName(), l.accountType(),
                l.openingBalance(), l.rows(), l.totalDebit(), l.totalCredit(), l.closingBalance(), true, l.accountNameAr());
    }

    public AccountLedgerDTO vendorLedger(UUID vendorId, LocalDate from, LocalDate to) {
        Vendor v = vendors.findById(vendorId).orElseThrow(() -> new NotFoundException("Vendor not found"));
        if (v.getPayableAccount() == null) throw new BusinessRuleViolationException("Vendor has no ledger account yet");
        return accountLedger(v.getPayableAccount().getId(), new LedgerFilter(from, to, null, null, null, null));
    }

    /**
     * Trial balance as of a date, optionally filtered to one property.
     *
     * <p>Only the unfiltered (tenant-wide) trial balance is guaranteed to balance.
     * A property-filtered one need not: tenant-level accounts — cash, VAT, rounding,
     * discount, forfeited income, opening-balance difference — carry no property
     * dimension, so their half of an entry is excluded while the property-tagged half
     * is kept. The difference the UI reports in that mode is expected, not a
     * corruption of the books.
     */
    public List<TrialBalanceRowDTO> trialBalance(LocalDate asOf, UUID propertyId) {
        LocalDate d = asOf == null ? LocalDate.now() : asOf;
        List<BalanceRow> balances = lines.balancesAsOf(TenantContextHolder.getTenantId(), d, propertyId);
        if (balances.isEmpty()) return List.of();
        Map<UUID, Account> byId = accounts.findAllById(balances.stream().map(BalanceRow::getAccountId).toList())
                .stream().collect(Collectors.toMap(Account::getId, a -> a));
        return balances.stream()
                .filter(b -> byId.containsKey(b.getAccountId()))
                .map(b -> {
                    Account a = byId.get(b.getAccountId());
                    return new TrialBalanceRowDTO(a.getId(), a.getCode(), a.getName(), a.getAccountType().name(),
                            a.getParentId(), a.getPropertyId(), b.getDebit(), b.getCredit(), b.getDebit().subtract(b.getCredit()),
                            a.getNameAr());
                })
                .sorted(Comparator.comparing(TrialBalanceRowDTO::code)).toList();
    }

    /**
     * The "Particular" a row prints. A paired posting already recorded the one account
     * the line faces (Addendum A) — a TCO whose receivable is split across advance rent,
     * deposit and admin fee must name a different account on each of its three rows.
     * Older or n-to-1 entries have no pairing, so those rows fall back to naming every
     * other account on the entry.
     */
    private Map<UUID, String> fallbackParticulars(UUID tenantId, List<LineRow> raw, UUID accountId) {
        Set<UUID> unpaired = raw.stream().filter(r -> r.getContraAccountName() == null)
                .map(LineRow::getEntryId).collect(Collectors.toCollection(LinkedHashSet::new));
        if (unpaired.isEmpty()) return Map.of();
        return lines.counterAccounts(tenantId, unpaired, accountId).stream()
                .collect(Collectors.toMap(CounterRow::getEntryId, CounterRow::getNames));
    }

    private static String particular(LineRow r, Map<UUID, String> fallback) {
        return r.getContraAccountName() != null ? r.getContraAccountName() : fallback.getOrDefault(r.getEntryId(), "");
    }

    private static BigDecimal orZero(BigDecimal b) { return b == null ? BigDecimal.ZERO : b; }
}
