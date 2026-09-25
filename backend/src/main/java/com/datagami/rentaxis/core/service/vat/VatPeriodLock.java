package com.datagami.rentaxis.core.service.vat;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.VatReturn;
import com.datagami.rentaxis.domain.repository.VatReturnRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * #55: a VAT return marked filed locks its period. A posting that touches an
 * Output VAT or Input VAT account and is dated inside a filed period is refused
 * ({@code vat.periodFiled}); the correction is dated in an open period and lands on
 * that period's return as an adjustment. Called by {@code PostingService} for
 * every post and reversal, so no VAT path can go round it.
 *
 * <p>Cheap for the common case: one indexed lookup of a filed period covering the
 * date, and only then the VAT accounts. Native SQL binds {@code tenant_id}.</p>
 */
@Component
public class VatPeriodLock {

    static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);

    private final VatReturnRepository returns;
    private final NamedParameterJdbcTemplate jdbc;

    public VatPeriodLock(VatReturnRepository returns, NamedParameterJdbcTemplate jdbc) {
        this.returns = returns;
        this.jdbc = jdbc;
    }

    public void assertOpen(UUID tenantId, Collection<UUID> accountIds, LocalDate date) {
        if (tenantId == null || date == null || accountIds == null || accountIds.isEmpty()) return;
        Optional<VatReturn> filed = returns.filedCovering(tenantId, date);
        if (filed.isEmpty()) return;
        Set<UUID> vat = vatAccounts(tenantId);
        if (accountIds.stream().noneMatch(vat::contains)) return;
        VatReturn r = filed.get();
        LocalDate next = firstOpenDate(tenantId, r.getPeriodEnd().plusDays(1));
        throw new BusinessRuleViolationException("The VAT return for " + DMY.format(r.getPeriodStart()) + " – "
                + DMY.format(r.getPeriodEnd()) + " is filed, so VAT dated " + DMY.format(date)
                + " cannot change. Date the correction on or after " + DMY.format(next)
                + "; it will show on that period's return.",
                "vat.periodFiled", Map.of("from", DMY.format(r.getPeriodStart()), "to", DMY.format(r.getPeriodEnd()),
                        "date", DMY.format(date), "next", DMY.format(next)));
    }

    /** {@code date}, or the day after the filed period(s) that hold it. */
    public LocalDate firstOpenDate(UUID tenantId, LocalDate date) {
        LocalDate d = date;
        for (int guard = 0; guard < 100; guard++) {
            Optional<VatReturn> filed = returns.filedCovering(tenantId, d);
            if (filed.isEmpty()) return d;
            d = filed.get().getPeriodEnd().plusDays(1);
        }
        return d;
    }

    /** Whether {@code date} falls in a filed VAT period. */
    public boolean isFiled(UUID tenantId, LocalDate date) {
        return tenantId != null && date != null && returns.filedCovering(tenantId, date).isPresent();
    }

    /** The leaves the OUTPUT_VAT and INPUT_VAT roles resolve to, tenant default or per property. */
    public Set<UUID> vatAccounts(UUID tenantId) {
        MapSqlParameterSource p = new MapSqlParameterSource("t", tenantId);
        return new HashSet<>(jdbc.queryForList("""
                select account_id from tenant_default_account_mappings where tenant_id = :t and role in ('OUTPUT_VAT', 'INPUT_VAT')
                union
                select account_id from property_account_mappings where tenant_id = :t and role in ('OUTPUT_VAT', 'INPUT_VAT')
                """, p, UUID.class));
    }
}
