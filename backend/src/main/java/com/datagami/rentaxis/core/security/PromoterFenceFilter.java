package com.datagami.rentaxis.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * The promoter fence (promoter plan, "defence in depth — the most important
 * piece"): a signed-in PROMOTER is answered 403 on every path except an explicit
 * allowlist, before any controller, {@code @PreAuthorize} or service sees the
 * request.
 *
 * <p>Why a fence rather than a role on each endpoint: many endpoints are
 * {@code isAuthenticated()} or decide roles inside their services (tickets,
 * meetings, notifications by reference, ...), and a role list that was not written
 * with PROMOTER in mind would admit it by omission. Here the default is "no":
 * a new endpoint is closed to promoters until someone adds it below on purpose.
 * {@code PromoterFenceSweepIT} calls every mapped handler as a promoter to hold
 * that line.
 *
 * <p>Runs inside the Spring Security chain right after {@link ApiSecurityFilter},
 * so the principal is already established from the bearer token or the stored row
 * behind the legacy headers. Paths that filter leaves anonymous carry no promoter
 * identity and are not this filter's business. Spring Security's firewall has
 * already refused non-normalised paths ({@code ..}, {@code ;}, encoded slashes);
 * {@link #isAllowed} refuses them again rather than trust that.
 *
 * <p>The allowlist is what the web shell calls for every signed-in user
 * ({@code useTenantFeatures}: features and info; the header logo; the notification
 * bell; the profile page), the caller's own profile and password
 * ({@link ApiSecurityFilter#SELF_SERVICE_PROFILE_PATH}), and the promoter API.
 */
public class PromoterFenceFilter extends OncePerRequestFilter {

    public static final String PROMOTER_AUTHORITY = "ROLE_PROMOTER";

    /** Any method: the promoter's own API, its profile and password, its notifications. */
    private static final List<String> OPEN_PREFIXES = List.of(
            "/api/v1/promoter",
            ApiSecurityFilter.SELF_SERVICE_PROFILE_PATH,
            "/api/v1/notifications");

    /** GET only: the web shell's reads. */
    private static final Set<String> SHELL_READS = Set.of(
            "/api/v1/tenant/features",
            "/api/v1/tenant/info",
            "/api/v1/org/branding/logo");

    private static final byte[] DENIED =
            "{\"error\":true,\"message\":\"Access denied\",\"status\":403}".getBytes(StandardCharsets.UTF_8);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isPromoter(SecurityContextHolder.getContext().getAuthentication())
                && !isAllowed(request.getMethod(), pathOf(request))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getOutputStream().write(DENIED);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Whether a PROMOTER may make this request. Public for the sweep test. */
    public static boolean isAllowed(String method, String path) {
        if (path == null || path.contains("..") || path.contains(";") || path.contains("%")
                || path.contains("\\") || path.contains("//")) {
            return false;
        }
        for (String prefix : OPEN_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return "GET".equals(method) && SHELL_READS.contains(path);
    }

    private static boolean isPromoter(Authentication auth) {
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (PROMOTER_AUTHORITY.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }
}
