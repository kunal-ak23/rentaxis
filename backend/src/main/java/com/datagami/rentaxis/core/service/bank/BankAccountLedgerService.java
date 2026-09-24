package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.api.dto.bank.BankRecDTOs;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BankAccount;
import com.datagami.rentaxis.domain.repository.BankAccountRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The ledger leaves one real bank account owns (finance-ops spec §3 decision):
 * book balance is the sum over the set, and statement lines match journal lines
 * on any leaf in it. A leaf belongs to at most one bank account.
 *
 * <p>Native SQL throughout, so every statement names the tenant explicitly: an
 * account id arrives in a request body, and the Hibernate filter does not cover
 * native queries.</p>
 */
@Service
public class BankAccountLedgerService {

    private final BankAccountRepository bankAccounts;
    private final NamedParameterJdbcTemplate jdbc;

    public BankAccountLedgerService(BankAccountRepository bankAccounts, NamedParameterJdbcTemplate jdbc) {
        this.bankAccounts = bankAccounts;
        this.jdbc = jdbc;
    }

    public static UUID requireTenant() {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null) throw new BusinessRuleViolationException("Select an organisation first");
        return t;
    }

    /** The bank account, in the caller's tenant (explicit check on top of the filter). Missing → 404. */
    @Transactional(readOnly = true)
    public BankAccount requireBankAccount(UUID id) {
        UUID t = requireTenant();
        BankAccount b = bankAccounts.findById(id).orElseThrow(() -> new NotFoundException("Bank account not found"));
        if (!t.equals(b.getTenantId())) throw new NotFoundException("Bank account not found");
        return b;
    }

    @Transactional(readOnly = true)
    public List<BankRecDTOs.Leaf> leaves(UUID bankAccountId) {
        UUID t = requireTenant();
        requireBankAccount(bankAccountId);
        return jdbc.query("""
                select a.id, a.code, a.name, a.property_id from bank_account_ledgers l
                join accounts a on a.id = l.account_id and a.tenant_id = l.tenant_id
                where l.tenant_id = :t and l.bank_account_id = :b order by a.code, a.name""",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId),
                (rs, i) -> new BankRecDTOs.Leaf(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getObject("property_id", UUID.class)));
    }

    /** The leaf ids; empty when none is assigned yet. */
    @Transactional(readOnly = true)
    public Set<UUID> leafSet(UUID bankAccountId) {
        UUID t = requireTenant();
        return new LinkedHashSet<>(jdbc.queryForList(
                "select account_id from bank_account_ledgers where tenant_id = :t and bank_account_id = :b",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId), UUID.class));
    }

    /** The leaf set, or the refusal that matching needs one. */
    @Transactional(readOnly = true)
    public Set<UUID> requireLeafSet(UUID bankAccountId) {
        Set<UUID> set = leafSet(bankAccountId);
        if (set.isEmpty()) {
            throw new BusinessRuleViolationException("Assign a ledger account to this bank account before reconciling");
        }
        return set;
    }

    /** The bank account that owns this leaf, if any. */
    @Transactional(readOnly = true)
    public Optional<UUID> ownerOf(UUID accountId) {
        UUID t = requireTenant();
        return jdbc.queryForList("select bank_account_id from bank_account_ledgers where tenant_id = :t and account_id = :a",
                new MapSqlParameterSource("t", t).addValue("a", accountId), UUID.class).stream().findFirst();
    }

    /**
     * Replaces the set. Every leaf must be an active BANK leaf of this tenant, not
     * owned by another bank account (refused with that account's name), and a leaf
     * leaves the set only while no live match references a line on it.
     */
    @Transactional
    public List<BankRecDTOs.Leaf> setLeaves(UUID bankAccountId, List<UUID> accountIds) {
        UUID t = requireTenant();
        BankAccount bank = requireBankAccount(bankAccountId);
        // Serialise edits of this account's set (and imports, which take the same row).
        jdbc.queryForList("select id from bank_accounts where id = :b and tenant_id = :t for update",
                new MapSqlParameterSource("t", t).addValue("b", bank.getId()), UUID.class);
        Set<UUID> wanted = new LinkedHashSet<>(accountIds == null ? List.of() : accountIds);
        for (UUID a : wanted) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select code, name, account_sub_type, is_group, is_active from accounts where id = :a and tenant_id = :t""",
                    new MapSqlParameterSource("t", t).addValue("a", a));
            if (rows.isEmpty()) throw new NotFoundException("Ledger account not found");
            Map<String, Object> r = rows.get(0);
            String label = r.get("code") + " " + r.get("name");
            if (Boolean.TRUE.equals(r.get("is_group")) || !"BANK".equals(r.get("account_sub_type"))) {
                throw new BusinessRuleViolationException(label + " is not a bank ledger leaf; cash is counted, not reconciled");
            }
            if (!Boolean.TRUE.equals(r.get("is_active"))) throw new BusinessRuleViolationException(label + " is inactive");
            List<String> owner = jdbc.queryForList("""
                    select b.bank_name || ' ' || b.account_number from bank_account_ledgers l
                    join bank_accounts b on b.id = l.bank_account_id
                    where l.tenant_id = :t and l.account_id = :a and l.bank_account_id <> :b""",
                    new MapSqlParameterSource("t", t).addValue("a", a).addValue("b", bankAccountId), String.class);
            if (!owner.isEmpty()) {
                throw new BusinessRuleViolationException(label + " already belongs to bank account " + owner.get(0));
            }
        }
        Set<UUID> current = leafSet(bankAccountId);
        for (UUID gone : current) {
            if (wanted.contains(gone)) continue;
            Integer live = jdbc.queryForObject("""
                    select count(*) from bank_match_book_items i
                    join bank_matches m on m.id = i.match_id and m.tenant_id = i.tenant_id
                    join journal_lines jl on jl.id = i.journal_line_id and jl.tenant_id = i.tenant_id
                    where i.tenant_id = :t and m.bank_account_id = :b and not i.released and jl.account_id = :a""",
                    new MapSqlParameterSource("t", t).addValue("b", bankAccountId).addValue("a", gone), Integer.class);
            if (live != null && live > 0) {
                throw new BusinessRuleViolationException("Lines on this ledger account are matched; undo those matches first");
            }
            jdbc.update("delete from bank_account_ledgers where tenant_id = :t and bank_account_id = :b and account_id = :a",
                    new MapSqlParameterSource("t", t).addValue("b", bankAccountId).addValue("a", gone));
        }
        for (UUID a : wanted) {
            if (current.contains(a)) continue;
            jdbc.update("insert into bank_account_ledgers (tenant_id, bank_account_id, account_id) values (:t, :b, :a)",
                    new MapSqlParameterSource("t", t).addValue("b", bankAccountId).addValue("a", a));
        }
        return leaves(bankAccountId);
    }

    @Transactional
    public BankAccount setBankTrn(UUID bankAccountId, String trn) {
        BankAccount b = requireBankAccount(bankAccountId);
        String v = trn == null || trn.isBlank() ? null : trn.replaceAll("\\s", "");
        if (v != null && !v.matches("\\d{15}")) {
            throw new BusinessRuleViolationException("A UAE TRN is 15 digits");
        }
        b.setBankTrn(v);
        return bankAccounts.save(b);
    }
}
