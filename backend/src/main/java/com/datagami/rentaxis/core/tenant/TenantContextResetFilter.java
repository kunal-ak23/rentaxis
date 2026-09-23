package com.datagami.rentaxis.core.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Outermost servlet filter: every request starts and ends with no tenant on
 * its thread (security audit 2026-09-24, P1-1).
 *
 * <p>{@link TenantContextHolder} is a ThreadLocal and Tomcat reuses its worker
 * threads. The removed {@code TenantInterceptor} set it from an unauthenticated
 * {@code X-Tenant-ID} header and cleared it only in {@code postHandle}, which
 * Spring MVC skips when the handler throws — so an anonymous request with a bad
 * body left an attacker-chosen tenant on the thread for whoever came next
 * (logins scoped to the wrong tenant, SUPER_ADMIN reads narrowed, null tenant
 * ids stamped). Clearing here, before Spring Security and every other filter,
 * and in a {@code finally}, makes that impossible whatever sets the tenant
 * later. {@code ApiSecurityFilter} clears as well; this one does not depend on
 * any route being routed through it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantContextResetFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TenantContextHolder.clear();
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
        }
    }
}
