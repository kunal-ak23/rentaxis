package com.datagami.rentaxis.core.tenant;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * An organisation-scoped endpoint called with no organisation selected answers
 * 400 "Select an organisation first" (break round 1, F7).
 *
 * <p>{@code ApiSecurityFilter} lets exactly one kind of signed-in caller through
 * without a tenant: a SUPER_ADMIN that sent no {@code X-Tenant-Id} (the web's
 * "Global View"). With no tenant in context {@link TenantAspect} leaves the
 * Hibernate tenant filter off, so every org-scoped read then spanned ALL
 * organisations — {@code /cheques/summary}, {@code /dashboard/summary},
 * {@code /finance/journals}, {@code /properties} returned mixed data — and code
 * that needed a tenant threw instead ({@code /finance/fiscal-settings} was a 500).
 *
 * <p>So this runs in front of every controller, and the endpoints that are
 * genuinely cross-organisation are an explicit allow-list
 * ({@link TenantSelectionWebConfig#CROSS_ORG_PATHS}). A new controller is
 * org-scoped, and therefore refused without an organisation, until someone adds
 * it there on purpose. Anonymous requests (public routes, pre-login auth) are
 * not this interceptor's business: they have no principal and pass.
 */
public class OrganisationRequiredInterceptor implements HandlerInterceptor {

    public static final String MESSAGE = "Select an organisation first";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (TenantContextHolder.getTenantId() != null) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return true;
        }
        if (isSuperAdminPlatformRead(request, auth)) {
            return true;
        }
        throw new BusinessRuleViolationException(MESSAGE);
    }

    /**
     * Controller ruling (batch 5, partial revert): PR #366 review P2-3 kept
     * platform-wide totals for a SUPER_ADMIN with no organisation selected on
     * exactly these reads — the mobile manager app's dashboard calls them in that
     * state. GET only and SUPER_ADMIN only; any other caller, method or path
     * without an organisation still gets the 400.
     */
    static final List<String> SUPER_ADMIN_PLATFORM_READS = List.of(
            "/api/v1/dashboard/summary",
            "/api/v1/cheques/summary",
            "/api/v1/cheques/aging");

    private static boolean isSuperAdminPlatformRead(HttpServletRequest request, Authentication auth) {
        return "GET".equalsIgnoreCase(request.getMethod())
                && SUPER_ADMIN_PLATFORM_READS.contains(request.getRequestURI())
                && auth.getAuthorities().stream().anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
    }

}
