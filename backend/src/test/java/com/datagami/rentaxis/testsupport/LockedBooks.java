package com.datagami.rentaxis.testsupport;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Books locked over months whose recognition is still PLANNED — the state a lock set
 * before bug 46's rule left behind ({@code lockThrough} now refuses to create it).
 * Tests of how the product treats a locked month that still holds planned rows (the
 * run skips them, a posting into the lock is refused) set it here, as data, for the
 * tenant in context.
 */
public final class LockedBooks {

    private LockedBooks() { }

    public static void lockOverPlanned(JdbcTemplate jdbc, LocalDate through) {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) throw new IllegalStateException("No tenant in context");
        jdbc.update("""
                insert into tenant_fiscal_settings (tenant_id, fiscal_year_start_month, updated_at)
                values (?, 1, now()) on conflict (tenant_id) do nothing""", tenantId);
        jdbc.update("update tenant_fiscal_settings set books_locked_through = ?, books_lock_from_start = false,"
                + " updated_at = now() where tenant_id = ?", through, tenantId);
    }
}
