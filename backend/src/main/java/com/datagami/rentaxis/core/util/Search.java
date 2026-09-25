package com.datagami.rentaxis.core.util;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The shared rules of the paged list and lookup endpoints (scale P1-3 / P1-6).
 *
 * <p><b>The term is lowercased and trimmed here, once.</b> Every search query compares
 * {@code lower(column) like :q}; a term passed through as typed would make any capital
 * letter match nothing ("Semi" missing "R14 Semi Salem").</p>
 */
public final class Search {

    /** The largest page a paged list serves. */
    public static final int MAX_PAGE_SIZE = 200;
    /** The most options a lookup returns. */
    public static final int MAX_LOOKUP = 50;
    /** The most ids a names lookup takes. */
    public static final int MAX_NAMES = 200;

    private Search() {
    }

    /** {@code "%term%"} lowercased and trimmed, or {@code null} for a blank term. */
    public static String like(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        return "%" + escape(q.trim().toLowerCase(Locale.ROOT)) + "%";
    }

    /** {@code "term%"} lowercased and trimmed, or {@code null} for a blank prefix. */
    public static String prefix(String p) {
        if (p == null || p.isBlank()) {
            return null;
        }
        return escape(p.trim().toLowerCase(Locale.ROOT)) + "%";
    }

    /** LIKE wildcards typed by the user match themselves. */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** A page request with the page clamped to 0.. and the size to 1..{@link #MAX_PAGE_SIZE}. */
    public static Pageable page(int page, int size, Sort sort) {
        return PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, MAX_PAGE_SIZE)), sort);
    }

    /** A lookup limit clamped to 1..{@link #MAX_LOOKUP}. */
    public static int limit(int limit) {
        return Math.max(1, Math.min(limit, MAX_LOOKUP));
    }

    /** The distinct ids of a names lookup; more than {@link #MAX_NAMES} is refused. */
    public static List<UUID> names(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<UUID> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.size() > MAX_NAMES) {
            throw new BusinessRuleViolationException("Ask for at most " + MAX_NAMES + " ids at a time; this request named "
                    + distinct.size() + ".");
        }
        return distinct;
    }

    /**
     * The organisation a paged / search / names endpoint answers for. They bind it in SQL,
     * so a caller with none — a SUPER_ADMIN who has not picked an organisation — is told to
     * pick one (400) rather than handed an empty page that looks like "nothing matches".
     */
    public static UUID requireTenant() {
        UUID t = com.datagami.rentaxis.core.tenant.TenantContextHolder.getTenantId();
        if (t == null) {
            throw new BusinessRuleViolationException("Select an organisation first");
        }
        return t;
    }

    /** The tenant for the register's SQL reads: the caller's, or none only for a SUPER_ADMIN (all organisations). */
    public record TenantScope(UUID tenantId, boolean allTenants) {
    }

    /**
     * {@link #requireTenant}, except that a SUPER_ADMIN with no organisation selected keeps
     * reading across organisations — what the dashboard, register tiles and aging did before
     * they were SQL. Any other caller without a tenant is refused.
     */
    public static TenantScope tenantOrSuperAdmin() {
        UUID t = com.datagami.rentaxis.core.tenant.TenantContextHolder.getTenantId();
        if (t != null) return new TenantScope(t, false);
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean superAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
        if (!superAdmin) throw new BusinessRuleViolationException("Select an organisation first");
        return new TenantScope(new UUID(0L, 0L), true);
    }

    /** A JPQL {@code in :ids} list for "unrestricted": ignored by the query, but never empty. */
    public static Collection<UUID> scopeIds(List<UUID> scoped) {
        return scoped == null || scoped.isEmpty() ? List.of(new UUID(0L, 0L)) : scoped;
    }
}
