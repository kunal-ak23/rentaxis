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
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

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

    /**
     * F14-20: the imported statement a posting on {@code accountIds} dated
     * {@code date} falls inside (or before). One bank account owning one of the
     * leaves, whose imported statement lines run through {@code date} or later.
     */
    public record StatementCover(UUID bankAccountId, String bankLabel, LocalDate from, LocalDate to) { }

    /** How a bank movement relates to the imported statement (F14-20). */
    public enum StatementEvidence {
        /** An ordinary action: refused when an imported statement covers its date. */
        CHECK,
        /** The user confirmed the movement is not on the imported statement. */
        CONFIRMED_NOT_ON_STATEMENT,
        /** Recorded from a statement line (bank reconciliation), a cut-over replay or a gateway capture. */
        EXEMPT;

        public static StatementEvidence of(Boolean notOnStatement) {
            return Boolean.TRUE.equals(notOnStatement) ? CONFIRMED_NOT_ON_STATEMENT : CHECK;
        }
    }

    /** The statement covering {@code date} on any of the leaves, the latest-ending first. */
    @Transactional(readOnly = true)
    public Optional<StatementCover> statementCovering(Collection<UUID> accountIds, LocalDate date) {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null || accountIds == null || accountIds.isEmpty() || date == null) return Optional.empty();
        return jdbc.query("""
                select b.id, b.bank_name, b.account_number, min(s.txn_date) as first_day, max(s.txn_date) as last_day
                from bank_account_ledgers l
                join bank_accounts b on b.id = l.bank_account_id and b.tenant_id = l.tenant_id
                join bank_statement_lines s on s.bank_account_id = b.id and s.tenant_id = b.tenant_id
                where l.tenant_id = :t and l.account_id in (:ids)
                group by b.id, b.bank_name, b.account_number
                having max(s.txn_date) >= :d
                order by max(s.txn_date) desc, b.id
                limit 1""",
                new MapSqlParameterSource("t", t).addValue("ids", new TreeSet<>(accountIds)).addValue("d", date),
                (rs, i) -> new StatementCover(rs.getObject("id", UUID.class),
                        label(rs.getString("bank_name"), rs.getString("account_number")),
                        rs.getObject("first_day", LocalDate.class), rs.getObject("last_day", LocalDate.class)))
                .stream().findFirst();
    }

    /**
     * F14-20: a bank movement dated on or before the last day of a statement
     * already imported for its bank account is refused, unless the user confirmed
     * it is not on that statement. Returns the statement when the confirmation was
     * used, so the caller can {@link #recordOffStatement record} it against the
     * journal it posts.
     */
    @Transactional(readOnly = true)
    public Optional<StatementCover> requireOffStatement(Collection<UUID> accountIds, LocalDate date,
                                                       StatementEvidence evidence) {
        if (evidence == null || evidence == StatementEvidence.EXEMPT) return Optional.empty();
        Optional<StatementCover> cover = statementCovering(accountIds, date);
        if (cover.isEmpty()) return Optional.empty();
        StatementCover c = cover.get();
        if (evidence != StatementEvidence.CONFIRMED_NOT_ON_STATEMENT) {
            throw new BusinessRuleViolationException("A statement for " + c.bankLabel() + " covering "
                    + c.from().format(DMY) + " to " + c.to().format(DMY) + " is already imported. An entry dated "
                    + date.format(DMY) + " falls inside it: record it from its statement line in Bank reconciliation,"
                    + " or confirm that it is not on the statement.",
                    "bank.statementCovers", java.util.Map.of("bank", c.bankLabel(), "from", c.from().format(DMY),
                            "to", c.to().format(DMY), "date", date.format(DMY)));
        }
        return cover;
    }

    /** Keeps the user's "not on the statement" confirmation against the journal it let through. */
    @Transactional
    public void recordOffStatement(StatementCover c, UUID journalEntryId, LocalDate entryDate) {
        UUID t = TenantContextHolder.getTenantId();
        if (c == null || journalEntryId == null || t == null) return;
        // The journal was written through JPA in this transaction; the row below references it.
        if (entityManager != null) entityManager.flush();
        jdbc.update("""
                insert into bank_off_statement_items (id, tenant_id, bank_account_id, journal_entry_id, entry_date,
                                                      statement_from, statement_to, confirmed_by)
                values (:id, :t, :b, :e, :d, :f, :to, :u)
                on conflict (bank_account_id, journal_entry_id) do nothing""",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("t", t).addValue("b", c.bankAccountId())
                        .addValue("e", journalEntryId).addValue("d", entryDate).addValue("f", c.from())
                        .addValue("to", c.to()).addValue("u", currentUserId()));
    }

    /** The narration suffix a confirmed off-statement entry carries. */
    public static String offStatementNote(StatementCover c) {
        return " [not on the " + c.bankLabel() + " statement " + c.from().format(DMY) + "–" + c.to().format(DMY)
                + ", confirmed]";
    }

    private static UUID currentUserId() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
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
