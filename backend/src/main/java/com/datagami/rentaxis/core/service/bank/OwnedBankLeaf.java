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
 * leaf of a bank account attached to the property; the tenant's default BANK
 * mapping when owned; the default bank account's leaf; the only bank account's
 * leaf. Only leaves that are tenant-wide or this property's are considered (a
 * receipt may not land in another property's leaf). Within one bank account its chart account ({@code coa_account_id}) is
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
                    where m.tenant_id = :t and m.property_id = :p and m.role = 'BANK'""", p);
            if (mapped.isPresent()) return mapped;
            // 2. a bank account attached to the property
            Optional<UUID> attached = first(LEAF_OF_ACCOUNT + """
                    where b.tenant_id = :t and b.is_active and b.property_id = :p
                    order by b.is_default desc, (l.account_id = b.coa_account_id) desc, a.code, a.id""", p);
            if (attached.isPresent()) return attached;
        }
        // 3. the tenant default BANK mapping, when owned
        Optional<UUID> tenantDefault = first("""
                select d.account_id from tenant_default_account_mappings d
                join bank_account_ledgers l on l.account_id = d.account_id and l.tenant_id = d.tenant_id
                join accounts a on a.id = d.account_id and a.tenant_id = d.tenant_id
                where d.tenant_id = :t and d.role = 'BANK' and (a.property_id is null or a.property_id = :p)""", p);
        if (tenantDefault.isPresent()) return tenantDefault;
        // 4. the default bank account
        Optional<UUID> byDefault = first(LEAF_OF_ACCOUNT + """
                where b.tenant_id = :t and b.is_active and b.is_default
                order by (l.account_id = b.coa_account_id) desc, a.code, a.id""", p);
        if (byDefault.isPresent()) return byDefault;
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
