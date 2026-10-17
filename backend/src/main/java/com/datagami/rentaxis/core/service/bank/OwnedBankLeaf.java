package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F14-16: the bank ledger leaf a receipt for a property lands in, chosen among the
 * leaves a bank account owns — money booked to a leaf no bank account owns can
 * never appear in a reconciliation workspace.
 *
 * <p>In order: the property's own BANK mapping when a bank account owns it; the
 * leaf of a bank account attached to the property; the default bank account's
 * leaf (F14-56);
 * the tenant's default BANK mapping when owned; the only bank account's leaf.
 * Only leaves that are tenant-wide or this property's are ever considered
 * (R1 P2-2). Within one bank account its chart account ({@code coa_account_id}) is
 * preferred when it is one of its leaves, else its first leaf by code. Empty when
 * the tenant has no bank account with a leaf at all (a fresh tenant, a cut-over in
 * progress): callers then keep their old behaviour.</p>
 */
@Component
public class OwnedBankLeaf {

    private final NamedParameterJdbcTemplate jdbc;

    public OwnedBankLeaf(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether some bank account of this tenant owns the leaf. */
    @Transactional(readOnly = true)
    public boolean isOwned(UUID accountId) {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null || accountId == null) return false;
        Integer n = jdbc.queryForObject("select count(*) from bank_account_ledgers where tenant_id = :t and account_id = :a",
                new MapSqlParameterSource("t", t).addValue("a", accountId), Integer.class);
        return n != null && n > 0;
    }

    /**
     * R1 P2-2: whether a receipt for {@code propertyId} may land in {@code accountId}:
     * a bank leaf some bank account owns that is tenant-wide or this property's.
     */
    @Transactional(readOnly = true)
    public boolean validFor(UUID accountId, UUID propertyId) {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null || accountId == null) return false;
        Integer n = jdbc.queryForObject("""
                select count(*) from bank_account_ledgers l
                join accounts a on a.id = l.account_id and a.tenant_id = l.tenant_id and a.is_active
                where l.tenant_id = :t and l.account_id = :a and (a.property_id is null or a.property_id = :p)""",
                new MapSqlParameterSource("t", t).addValue("a", accountId).addValue("p", propertyId), Integer.class);
        return n != null && n > 0;
    }

    /** A bank account the tenant operates: its id and bank name. */
    public record Operating(UUID id, String bankName) { }

    /**
     * R2 ruling: the bank account a new property's own bank leaf is attached to —
     * the default bank account; else the only active one; else the oldest active one
     * (created first). Empty when the tenant has no active bank account.
     */
    @Transactional(readOnly = true)
    public Optional<Operating> operatingBankAccount() {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null) return Optional.empty();
        return jdbc.query("""
                select b.id, b.bank_name from bank_accounts b
                where b.tenant_id = :t and b.is_active
                order by b.is_default desc, b.created_at asc, b.id
                limit 1""", new MapSqlParameterSource("t", t),
                (rs, i) -> new Operating(rs.getObject("id", UUID.class), rs.getString("bank_name"))).stream().findFirst();
    }

    /**
     * Attaches {@code accountId} to the bank account, unless some bank account
     * already owns it, or the bank account is reconciled and the leaf has postings
     * on or before that date (#98 M3: a leaf reused by name at property creation
     * could otherwise slip history into a signed-off reconciliation; the same rule
     * as {@link BankAccountLedgerService#setLeaves}).
     */
    @Transactional
    public Claim attach(UUID bankAccountId, UUID accountId) {
        return claim(bankAccountId, accountId, false);
    }

    /** How {@link #claim} ended. */
    public enum Claim {
        /** The bank account already owned the leaf. */
        ALREADY,
        /** The leaf had no owner; the bank account owns it now. */
        ADOPTED,
        /** Another bank account owned this bank account's building's leaf, which had no postings; this one owns it now. */
        MOVED,
        /** Another bank account owns the leaf and keeps it. */
        OWNED_ELSEWHERE,
        /** The bank account is reconciled and the leaf has postings on or before that date. */
        LOCKED,
        /** Not an active BANK leaf of this tenant, or no such bank account. */
        NOT_A_LEAF
    }

    /**
     * #98 M1: makes {@code bankAccountId} own {@code accountId}.
     *
     * <ul>
     *   <li>An unowned leaf is adopted, unless this bank account is reconciled and
     *       the leaf has postings on or before that date ({@link Claim#LOCKED}).</li>
     *   <li>A leaf another bank account owns moves only when {@code moveUnposted},
     *       the leaf is the own leaf of the property this bank account is attached
     *       to (a building's own bank), and it has no journal line at all: then no
     *       reconciliation (finalized or draft), match or book balance of the old
     *       owner includes anything from it, whether or not the old owner is
     *       reconciled. Otherwise it stays where it is ({@link Claim#OWNED_ELSEWHERE}):
     *       a leaf someone put in another account's set is not taken silently.</li>
     * </ul>
     *
     * <p>Both bank-account rows are taken FOR UPDATE, in id order, before the
     * postings are counted: a posting holds its leaf owner's row FOR SHARE
     * ({@code BankLockService}), so one in flight finishes first and is counted.</p>
     */
    @Transactional
    public Claim claim(UUID bankAccountId, UUID accountId, boolean moveUnposted) {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null || bankAccountId == null || accountId == null) return Claim.NOT_A_LEAF;
        MapSqlParameterSource p = new MapSqlParameterSource("t", t).addValue("b", bankAccountId).addValue("a", accountId);
        Integer leaf = jdbc.queryForObject("""
                select count(*) from accounts where id = :a and tenant_id = :t and account_sub_type = 'BANK'
                  and not is_group and is_active""", p, Integer.class);
        if (leaf == null || leaf == 0) return Claim.NOT_A_LEAF;
        UUID owner = jdbc.queryForList("select bank_account_id from bank_account_ledgers where tenant_id = :t and account_id = :a",
                p, UUID.class).stream().findFirst().orElse(null);
        if (bankAccountId.equals(owner)) return Claim.ALREADY;
        List<java.util.Map<String, Object>> rows = jdbc.queryForList("""
                select id, reconciled_through from bank_accounts where tenant_id = :t and id in (:ids)
                order by id for update""",
                new MapSqlParameterSource("t", t).addValue("ids", owner == null ? List.of(bankAccountId)
                        : List.of(bankAccountId, owner)));
        java.time.LocalDate locked = null;
        boolean found = false;
        for (java.util.Map<String, Object> r : rows) {
            if (!bankAccountId.equals(r.get("id"))) continue;
            found = true;
            Object d = r.get("reconciled_through");
            if (d instanceof java.sql.Date sd) locked = sd.toLocalDate();
            else if (d instanceof java.time.LocalDate ld) locked = ld;
        }
        if (!found) return Claim.NOT_A_LEAF;
        if (locked != null && postings(t, accountId, locked) > 0) return Claim.LOCKED;
        if (owner == null) {
            int n = jdbc.update("""
                    insert into bank_account_ledgers (tenant_id, bank_account_id, account_id) values (:t, :b, :a)
                    on conflict do nothing""", p);
            if (n > 0) renameDefaultNamed(t, bankAccountId, List.of(accountId));
            return n > 0 ? Claim.ADOPTED : Claim.OWNED_ELSEWHERE;
        }
        if (!moveUnposted || !buildingLeaf(t, bankAccountId, accountId) || postings(t, accountId, null) > 0) {
            return Claim.OWNED_ELSEWHERE;
        }
        jdbc.update("""
                update bank_account_ledgers set bank_account_id = :b
                where tenant_id = :t and account_id = :a and bank_account_id = :o""", p.addValue("o", owner));
        // S20-04: a building's leaf is named "<operating bank> - <building>" when the
        // operating bank takes it at property creation; on the move it takes the new
        // bank's name, so the chart does not say one bank while another owns it. Only
        // that exact generated name changes (review P3-17); a name someone typed is kept.
        jdbc.update("""
                update accounts a set name = nb.bank_name || ' - ' || p.name_en,
                       name_en = case when a.name_en is null or a.name_en = a.name
                                      then nb.bank_name || ' - ' || p.name_en else a.name_en end,
                       updated_at = now()
                  from bank_accounts ob, bank_accounts nb, properties p
                 where a.id = :a and a.tenant_id = :t
                   and ob.id = :o and ob.tenant_id = :t and nb.id = :b and nb.tenant_id = :t
                   and p.id = a.property_id and p.tenant_id = :t
                   and ob.bank_name <> nb.bank_name
                   and a.name = ob.bank_name || ' - ' || p.name_en""", p);
        return Claim.MOVED;
    }

    /** Whether the leaf is scoped to the very property the bank account is attached to. */
    public boolean buildingLeaf(UUID t, UUID bankAccountId, UUID accountId) {
        Integer n = jdbc.queryForObject("""
                select count(*) from bank_accounts b join accounts a on a.tenant_id = b.tenant_id and a.id = :a
                where b.id = :b and b.tenant_id = :t and b.property_id is not null and a.property_id = b.property_id""",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId).addValue("a", accountId), Integer.class);
        return n != null && n > 0;
    }

    /** Journal lines on the leaf, dated on or before {@code through} (all of them when null). */
    private int postings(UUID t, UUID accountId, java.time.LocalDate through) {
        Integer n = jdbc.queryForObject("""
                select count(*) from journal_lines jl join journal_entries je on je.id = jl.journal_entry_id
                where jl.tenant_id = :t and jl.account_id = :a and (cast(:r as date) is null or je.entry_date <= :r)""",
                new MapSqlParameterSource("t", t).addValue("a", accountId).addValue("r", through), Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * R2 ruling (b), scoped by #98 M2: a new bank account takes the properties' own
     * bank leaves (their BANK mapping) no bank account owns yet — the leaves
     * properties created before it generated — within its scope: a bank account
     * attached to a property takes only that property's; one for the whole
     * organisation takes every property's. Hand-made BANK leaves are never taken.
     * @return how many leaves it took
     */
    @Transactional
    public int attachUnownedPropertyLeaves(UUID bankAccountId) {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null || bankAccountId == null) return 0;
        List<UUID> taken = jdbc.queryForList("""
                insert into bank_account_ledgers (tenant_id, bank_account_id, account_id)
                select distinct a.tenant_id, b.id, a.id
                from bank_accounts b
                join property_account_mappings m on m.tenant_id = b.tenant_id and m.role = 'BANK'
                 and (b.property_id is null or m.property_id = b.property_id)
                join accounts a on a.id = m.account_id and a.tenant_id = m.tenant_id
                 and a.account_sub_type = 'BANK' and not a.is_group and a.is_active and a.property_id = m.property_id
                where b.id = :b and b.tenant_id = :t
                  and not exists (select 1 from bank_account_ledgers l where l.tenant_id = a.tenant_id and l.account_id = a.id)
                on conflict do nothing
                returning account_id""",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId), UUID.class);
        renameDefaultNamed(t, bankAccountId, taken);
        return taken.size();
    }

    /**
     * The BANK leaf name a property is given while no bank account owns it: the seeded
     * template's (S21-04), and the one seeded before it, which named one client's bank.
     */
    static final List<String> UNOWNED_DEFAULT_PREFIXES = List.of("Bank - ", "Emirates Islamic - ");

    /**
     * Sim r21 S21-04: a property's bank leaf generated before any bank account took it
     * carries the template's name ("Bank - <property>", or the old seed's
     * "Emirates Islamic - <property>"), or the name an earlier owner gave it
     * ("<that bank> - <property>", sim r22 S22-01); once a bank account adopts it, it is
     * named after that bank, as a leaf generated with the bank in place is. Only that exact,
     * untouched name changes. {@code name_en} follows when it still held the old name
     * (review P2-1: the Chart of Accounts shows and saves it); a distinct English name
     * someone typed is kept, and {@code name_ar} is the generic "bank account" label.
     */
    public void renameDefaultNamed(UUID t, UUID bankAccountId, List<UUID> accountIds) {
        if (accountIds.isEmpty()) return;
        jdbc.update("""
                update accounts a set name = nb.bank_name || ' - ' || p.name_en,
                       name_en = case when a.name_en is null or a.name_en = a.name
                                      then nb.bank_name || ' - ' || p.name_en else a.name_en end,
                       updated_at = now()
                  from bank_accounts nb, properties p
                 where a.tenant_id = :t and a.id in (:a)
                   and nb.id = :b and nb.tenant_id = :t
                   and p.id = a.property_id and p.tenant_id = :t
                   and a.account_sub_type = 'BANK' and not a.is_group
                   and (a.name in (cast(:names0 as text) || p.name_en, cast(:names1 as text) || p.name_en)
                        -- sim r22 S22-01: or the name another bank account of the organisation
                        -- gave it ("<that bank> - <property>"): an org-wide bank adopts every
                        -- unowned property leaf on creation, and a leaf it later lets go keeps
                        -- that name until the bank that takes it renames it.
                        or exists (select 1 from bank_accounts ob
                                    where ob.tenant_id = :t and ob.id <> :b
                                      and a.name = ob.bank_name || ' - ' || p.name_en))
                   and a.name <> nb.bank_name || ' - ' || p.name_en""",
                new MapSqlParameterSource("t", t).addValue("b", bankAccountId).addValue("a", accountIds)
                        .addValue("names0", UNOWNED_DEFAULT_PREFIXES.get(0))
                        .addValue("names1", UNOWNED_DEFAULT_PREFIXES.get(1)));
    }

    /** Whether this tenant has any bank account with a ledger leaf. */
    @Transactional(readOnly = true)
    public boolean anyOwned() {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null) return false;
        Integer n = jdbc.queryForObject("select count(*) from bank_account_ledgers where tenant_id = :t",
                new MapSqlParameterSource("t", t), Integer.class);
        return n != null && n > 0;
    }

    @Transactional(readOnly = true)
    public Optional<UUID> forProperty(UUID propertyId) {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null) return Optional.empty();
        MapSqlParameterSource p = new MapSqlParameterSource("t", t).addValue("p", propertyId);
        // 1. the property's own mapping, when owned
        if (propertyId != null) {
            Optional<UUID> mapped = first("""
                    select m.account_id from property_account_mappings m
                    join bank_account_ledgers l on l.account_id = m.account_id and l.tenant_id = m.tenant_id
                    join accounts a on a.id = m.account_id and a.tenant_id = m.tenant_id and a.is_active
                      and (a.property_id is null or a.property_id = :p)
                    where m.tenant_id = :t and m.property_id = :p and m.role = 'BANK'""", p);
            if (mapped.isPresent()) return mapped;
            // 2. a bank account attached to the property
            Optional<UUID> attached = first(LEAF_OF_ACCOUNT + """
                    where b.tenant_id = :t and b.is_active and b.property_id = :p
                    order by b.is_default desc, (l.account_id = b.coa_account_id) desc, a.code, a.id""", p);
            if (attached.isPresent()) return attached;
        }
        // 3. F14-56: the default bank account — the tenant's operating bank — when it
        //    has a leaf this property may use (tenant-wide or its own). A leaf scoped
        //    to another property is never a target (R1 P2-2): ChequeService refuses it,
        //    and the money would sit in another building's books.
        Optional<UUID> byDefault = first(LEAF_OF_ACCOUNT + """
                where b.tenant_id = :t and b.is_active and b.is_default
                order by (l.account_id = b.coa_account_id) desc, a.code, a.id""", p);
        if (byDefault.isPresent()) return byDefault;
        // 4. the tenant default BANK mapping, when owned
        Optional<UUID> tenantDefault = first("""
                select d.account_id from tenant_default_account_mappings d
                join bank_account_ledgers l on l.account_id = d.account_id and l.tenant_id = d.tenant_id
                join accounts a on a.id = d.account_id and a.tenant_id = d.tenant_id
                where d.tenant_id = :t and d.role = 'BANK' and (a.property_id is null or a.property_id = :p)""", p);
        if (tenantDefault.isPresent()) return tenantDefault;
        // 5. the only bank account with a leaf
        List<UUID> accounts = jdbc.queryForList("""
                select distinct b.id from bank_accounts b
                join bank_account_ledgers l on l.bank_account_id = b.id and l.tenant_id = b.tenant_id
                join accounts a on a.id = l.account_id and a.tenant_id = l.tenant_id and a.is_active
                  and (a.property_id is null or a.property_id = :p)
                where b.tenant_id = :t and b.is_active""", p, UUID.class);
        if (accounts.size() != 1) return Optional.empty();
        return first(LEAF_OF_ACCOUNT + """
                where b.tenant_id = :t and b.id = :b
                order by (l.account_id = b.coa_account_id) desc, a.code, a.id""", p.addValue("b", accounts.get(0)));
    }

    private static final String LEAF_OF_ACCOUNT = """
            select l.account_id from bank_accounts b
            join bank_account_ledgers l on l.bank_account_id = b.id and l.tenant_id = b.tenant_id
            join accounts a on a.id = l.account_id and a.tenant_id = l.tenant_id and a.is_active
              and (a.property_id is null or a.property_id = :p)
            """;

    private Optional<UUID> first(String sql, MapSqlParameterSource p) {
        return jdbc.queryForList(sql + " limit 1", p, UUID.class).stream().findFirst();
    }

    /**
     * R1 P2-2: the cash-in-hand leaf a CASH receipt for this property lands in by
     * default — the property's CASH mapping, else the tenant default. Empty when the
     * chart has none.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> cashInHand(UUID propertyId) {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null) return Optional.empty();
        MapSqlParameterSource p = new MapSqlParameterSource("t", t).addValue("p", propertyId);
        if (propertyId != null) {
            Optional<UUID> own = first("""
                    select m.account_id from property_account_mappings m
                    join accounts a on a.id = m.account_id and a.tenant_id = m.tenant_id and a.is_active
                    where m.tenant_id = :t and m.property_id = :p and m.role = 'CASH'""", p);
            if (own.isPresent()) return own;
        }
        return first("""
                select d.account_id from tenant_default_account_mappings d
                join accounts a on a.id = d.account_id and a.tenant_id = d.tenant_id and a.is_active
                where d.tenant_id = :t and d.role = 'CASH'""", p);
    }

    /** One place money may be received into: a cash leaf, or a leaf a bank account owns. */
    public record Option(UUID id, String code, String name, String nameAr, String kind, String bankAccount) { }

    /**
     * R1 P2-2/P2-3: where a receipt for this property may land — the active cash
     * leaves and the leaves bank accounts own, each either tenant-wide or this
     * property's. What the Receive dialog offers, readable by any staff role that
     * may receive.
     */
    @Transactional(readOnly = true)
    public List<Option> optionsFor(UUID propertyId) {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null) return List.of();
        MapSqlParameterSource p = new MapSqlParameterSource("t", t).addValue("p", propertyId);
        return jdbc.query("""
                select a.id, a.code, a.name, a.name_ar, 'CASH' as kind, null as bank, 0 as ord from accounts a
                where a.tenant_id = :t and a.is_active and not a.is_group and a.account_sub_type = 'CASH'
                  and (a.property_id is null or a.property_id = :p)
                union all
                select a.id, a.code, a.name, a.name_ar, 'BANK', min(b.bank_name || ' ' || b.account_number), 1
                from bank_account_ledgers l
                join bank_accounts b on b.id = l.bank_account_id and b.tenant_id = l.tenant_id and b.is_active
                join accounts a on a.id = l.account_id and a.tenant_id = l.tenant_id and a.is_active
                where l.tenant_id = :t and (a.property_id is null or a.property_id = :p)
                group by a.id, a.code, a.name, a.name_ar
                order by ord, code, name""", p,
                (rs, i) -> new Option(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getString("name_ar"), rs.getString("kind"), rs.getString("bank")));
    }
}
