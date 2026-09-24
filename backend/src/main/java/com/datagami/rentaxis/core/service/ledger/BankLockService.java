package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The per-bank-account lock (finance-ops spec §4 "The lock"): a posting that
 * touches a ledger leaf of a bank account reconciled through date R, dated on or
 * before R, would change a signed-off reconciliation, so it is refused. No
 * document type is exempt — opening balances and import journals included.
 *
 * <p>{@link PostingService} calls {@link #assertOpen(UUID, Collection, LocalDate)}
 * for every post and every reversal. The services that post bank movements call
 * the same check early, so the refusal comes before any work is done.</p>
 *
 * <p><b>Serialisation with finalize.</b> The bank accounts owning the posted leaves
 * are read {@code FOR SHARE}; finalize takes the same row {@code FOR UPDATE}. So
 * either the posting commits first and finalize recomputes with it, or finalize
 * commits first and the posting, re-reading the row once the lock is released,
 * is refused. The same pattern VAT tax points use against the fiscal lock.</p>
 *
 * <p><b>Fast path.</b> A tenant that has never started a reconciliation
 * ({@code tenant_fiscal_settings.bank_rec_started} false) has no lock to check:
 * no leaf set is read and no row is locked. The flag is read off the settings row
 * the fiscal lock has already loaded into the persistence context for the same
 * posting, so it costs no query either. The flag is set when the tenant's first
 * reconciliation is created, a request before any finalize.</p>
 *
 * <p>Lives in the ledger package and reads the tables directly, the dependency
 * direction {@link TenantFiscalSettingsService} uses: the bank package depends on
 * the ledger, never the other way round.</p>
 */
@Service
public class BankLockService {

    static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantFiscalSettingsRepository settings;

    public BankLockService(NamedParameterJdbcTemplate jdbc, TenantFiscalSettingsRepository settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /** One bank account a posting would touch, with its lock. */
    public record Held(UUID bankAccountId, String bankLabel, LocalDate reconciledThrough, UUID accountId, String leafName) { }

    /** The early check, for the caller's tenant. */
    @Transactional
    public void assertOpen(Collection<UUID> accountIds, LocalDate entryDate) {
        assertOpen(TenantContextHolder.getTenantId(), accountIds, entryDate);
    }

    /**
     * Refuses when any of {@code accountIds} is a leaf of a bank account
     * reconciled through {@code entryDate} or later. Takes the owning bank
     * accounts {@code FOR SHARE}, in id order, unless the tenant has never
     * reconciled.
     */
    @Transactional
    public void assertOpen(UUID tenantId, Collection<UUID> accountIds, LocalDate entryDate) {
        if (tenantId == null || accountIds == null || accountIds.isEmpty() || entryDate == null) return;
        if (!started(tenantId)) return;
        Set<UUID> ids = new TreeSet<>(accountIds);
        List<Held> held = jdbc.query("""
                select b.id as bank_account_id, b.bank_name, b.account_number, b.reconciled_through,
                       a.id as account_id, a.name as leaf_name
                from bank_account_ledgers l
                join bank_accounts b on b.id = l.bank_account_id and b.tenant_id = l.tenant_id
                join accounts a on a.id = l.account_id and a.tenant_id = l.tenant_id
                where l.tenant_id = :t and l.account_id in (:ids)
                order by b.id, a.id
                for share of b""",
                new MapSqlParameterSource("t", tenantId).addValue("ids", ids),
                (rs, i) -> new Held(rs.getObject("bank_account_id", UUID.class),
                        label(rs.getString("bank_name"), rs.getString("account_number")),
                        rs.getObject("reconciled_through", LocalDate.class),
                        rs.getObject("account_id", UUID.class), rs.getString("leaf_name")));
        for (Held h : held) {
            if (h.reconciledThrough() != null && !entryDate.isAfter(h.reconciledThrough())) {
                throw new BusinessRuleViolationException(h.bankLabel() + " is reconciled through "
                        + h.reconciledThrough().format(DMY) + ". A posting dated " + entryDate.format(DMY) + " on '"
                        + h.leafName() + "' would change a signed-off reconciliation. Reopen that reconciliation first.");
            }
        }
    }

    /** The early check for a reversal: the entry's own accounts, on the reversal's date. */
    @Transactional
    public void assertOpenForEntry(UUID entryId, LocalDate reversalDate) {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null || entryId == null || !started(t)) return;
        List<UUID> accounts = jdbc.queryForList(
                "select distinct account_id from journal_lines where tenant_id = :t and journal_entry_id = :e",
                new MapSqlParameterSource("t", t).addValue("e", entryId), UUID.class);
        assertOpen(t, accounts, reversalDate);
    }

    /** The bank account's lock date, or empty. */
    @Transactional(readOnly = true)
    public Optional<LocalDate> reconciledThrough(UUID bankAccountId) {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null) return Optional.empty();
        return jdbc.queryForList("select reconciled_through from bank_accounts where id = :b and tenant_id = :t",
                        new MapSqlParameterSource("t", t).addValue("b", bankAccountId), LocalDate.class)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    /**
     * The fast-path flag. {@code findById} is served from the persistence context
     * when the fiscal lock has already read the row in this transaction.
     */
    boolean started(UUID tenantId) {
        return settings.findById(tenantId).map(TenantFiscalSettings::isBankRecStarted).orElse(false);
    }

    /** "Emirates Islamic 0123": the bank and the account number's last four digits. */
    public static String label(String bankName, String accountNumber) {
        String n = accountNumber == null ? "" : accountNumber.replaceAll("\\s", "");
        String tail = n.length() > 4 ? n.substring(n.length() - 4) : n;
        return ((bankName == null ? "" : bankName) + " " + tail).trim();
    }
}
