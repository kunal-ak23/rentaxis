package com.datagami.rentaxis.core.security;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.UUID;

/**
 * Authenticates API requests. Two paths, strictly ordered:
 *
 * <ol>
 *   <li><b>Bearer token</b> (phase 1+): when an {@code Authorization: Bearer}
 *       header is presented AND {@link AuthTokenService} is enabled, identity
 *       (user id, role, home tenant, memberships) comes exclusively from the
 *       verified claims. An invalid or expired token is an immediate 401 —
 *       the filter must NEVER fall back from a presented token to the legacy
 *       headers, or an attacker could downgrade to the spoofable path by
 *       sending a garbage token alongside spoofed X-User-* headers.
 *       {@code X-Tenant-Id} remains the ACTIVE-tenant selector (data, not
 *       identity) but is authorized against the verified claims.</li>
 *   <li><b>Legacy X-User-* headers</b>: everything else. Byte-identical to the
 *       pre-token behaviour when no secrets are configured, with two opt-in
 *       tightenings: (a) SUPER_ADMIN assertions must carry
 *       {@code X-Internal-Auth} matching {@code app.auth.internal-proxy-secret}
 *       once that secret is set (only the web proxy legitimately asserts
 *       SUPER_ADMIN; no mobile app does), and (b) the phase-2 kill switch
 *       {@code app.auth.legacy-headers=deny} rejects any request without a
 *       valid Bearer.</li>
 * </ol>
 *
 * <p>Config knobs live here (not in {@link AuthTokenService}) because they
 * gate filter routing, not token cryptography: the token service owns only
 * the signing secret.
 */
@Component
public class ApiSecurityFilter extends OncePerRequestFilter {

    private final AuthTokenService authTokenService;
    private final String internalProxySecret;
    private final String legacyHeadersMode;

    public ApiSecurityFilter(AuthTokenService authTokenService,
            @Value("${app.auth.internal-proxy-secret:}") String internalProxySecret,
            @Value("${app.auth.legacy-headers:allow}") String legacyHeadersMode) {
        this.authTokenService = authTokenService;
        this.internalProxySecret = internalProxySecret == null ? "" : internalProxySecret;
        this.legacyHeadersMode = legacyHeadersMode == null ? "allow" : legacyHeadersMode;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Skip auth routes to prevent interception overhead
        if (path.startsWith("/api/v1/auth/") || path.startsWith("/api/auth/")
                || path.startsWith("/actuator/") || path.startsWith("/api/webhooks/")
                || path.startsWith("/api/v1/assets/serve/") || path.startsWith("/public/")
                || path.startsWith("/api/v1/public/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Path 1: verified bearer token. Only taken when a token is actually
        // presented AND the token service has a secret; with no secret the
        // Authorization header is ignored entirely so behaviour stays
        // byte-identical until APP_AUTH_TOKEN_SECRET is configured.
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ") && authTokenService.enabled()) {
            handleBearer(request, response, filterChain, authorization.substring("Bearer ".length()));
            return;
        }

        // Phase-2 kill switch: once flipped to "deny", nothing without a valid
        // Bearer gets past this filter on non-skipped paths.
        if ("deny".equalsIgnoreCase(legacyHeadersMode)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                    "A valid bearer token is required.");
            return;
        }

        // Path 2: legacy headers, passed from the Next.js proxy middleware and
        // (for now) the installed mobile apps.
        String userIdStr = request.getHeader("X-User-Id");
        String userRole = request.getHeader("X-User-Role");
        String activeTenantIdStr = request.getHeader("X-Tenant-Id");
        String homeTenantIdStr = request.getHeader("X-User-Tenant-Id");

        try {
            if (userIdStr != null && userRole != null) {

                // SUPER_ADMIN gate: an unauthenticated header assertion of the
                // one role that bypasses tenant scoping. No mobile app uses
                // SUPER_ADMIN, so once app.auth.internal-proxy-secret is set,
                // only the web proxy (which sends X-Internal-Auth) may assert
                // it. Unset secret => gate off, for compatibility.
                if ("SUPER_ADMIN".equals(userRole) && !internalProxySecret.isBlank()) {
                    String presented = request.getHeader("X-Internal-Auth");
                    byte[] expected = internalProxySecret.getBytes(StandardCharsets.UTF_8);
                    byte[] actual = presented != null ? presented.getBytes(StandardCharsets.UTF_8) : new byte[0];
                    // MessageDigest.isEqual: constant-time comparison — do not
                    // replace with String.equals, which leaks a timing oracle.
                    if (!MessageDigest.isEqual(expected, actual)) {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN,
                                "SUPER_ADMIN requires internal proxy authentication.");
                        return;
                    }
                }

                UUID requestedTenantId = (activeTenantIdStr != null && !activeTenantIdStr.isBlank())
                        ? UUID.fromString(activeTenantIdStr)
                        : null;
                UUID homeTenantId = (homeTenantIdStr != null && !homeTenantIdStr.isBlank()
                        && !homeTenantIdStr.equals("undefined"))
                                ? UUID.fromString(homeTenantIdStr)
                                : null;

                boolean authorized = false;

                // Validate Tenant Access.
                //
                // SECURITY_GUARD belongs in the same-tenant branch as every other
                // tenant-scoped role, and omitting it made guard requests unreachable
                // rather than merely unauthorized: with an X-Tenant-Id header the
                // role fell through to the 403 below, and without one it was let
                // through with no TenantContext at all — which silently disables the
                // tenantFilter on BaseTenantEntity. Guards get exactly the RENTER
                // treatment: the requested tenant must equal their home tenant.
                if ("SUPER_ADMIN".equals(userRole)) {
                    authorized = true;
                } else if ("TENANT_ADMIN".equals(userRole) || "PROPERTY_MANAGER".equals(userRole)
                        || "TENANT_USER".equals(userRole) || "RENTER".equals(userRole)
                        || "SECURITY_GUARD".equals(userRole)) {
                    if (requestedTenantId == null) {
                        requestedTenantId = homeTenantId;
                    }
                    if (requestedTenantId != null && requestedTenantId.equals(homeTenantId)) {
                        authorized = true;
                    }
                }

                if (!authorized && requestedTenantId != null) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access to requested tenant is forbidden.");
                    return;
                }

                // Establish Spring Security Context
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        userIdStr, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + userRole)));
                SecurityContextHolder.getContext().setAuthentication(auth);

                // Setup Tenant Context for Database Isolation (Hibernate Filters)
                if (requestedTenantId != null) {
                    TenantContextHolder.setTenantId(requestedTenantId);
                }
            }
        } catch (IllegalArgumentException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid UUID format in context headers.");
            return;
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Guarantee cleanup of the thread local to prevent context leakage across
            // requests
            TenantContextHolder.clear();
        }
    }

    /**
     * Bearer path: identity from verified claims only. The X-User-* headers are
     * ignored here by construction — nothing in this method reads them.
     */
    private void handleBearer(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain,
            String token) throws ServletException, IOException {

        AuthTokenService.VerifiedIdentity identity;
        try {
            identity = authTokenService.verify(token);
        } catch (AuthTokenService.TokenInvalidException | AuthTokenService.TokenExpiredException e) {
            // Hard stop. Falling back to the legacy headers here would let an
            // attacker downgrade to the spoofable path by attaching a garbage
            // token to spoofed X-User-* headers.
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired bearer token.");
            return;
        }

        // X-Tenant-Id stays the active-tenant selector: it is data (which of
        // the caller's tenants this request operates on), not identity.
        UUID requestedTenantId;
        try {
            String activeTenantIdStr = request.getHeader("X-Tenant-Id");
            requestedTenantId = (activeTenantIdStr != null && !activeTenantIdStr.isBlank())
                    ? UUID.fromString(activeTenantIdStr)
                    : null;
        } catch (IllegalArgumentException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid UUID format in context headers.");
            return;
        }

        UUID homeTenantId = identity.homeTenantId();
        if (requestedTenantId == null) {
            requestedTenantId = homeTenantId;
        }

        // Same authorization shape as the legacy branch, but judged against
        // verified claims: SUPER_ADMIN reaches any tenant; every other role
        // must request its home tenant or one of its memberships ("tids"
        // covers multi-tenant users).
        boolean authorized = false;
        if (identity.role() == UserRole.SUPER_ADMIN) {
            authorized = true;
        } else if (requestedTenantId != null
                && (requestedTenantId.equals(homeTenantId) || identity.tenantIds().contains(requestedTenantId))) {
            authorized = true;
        }

        if (!authorized && requestedTenantId != null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access to requested tenant is forbidden.");
            return;
        }

        // Establish Spring Security Context from the verified claims.
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                identity.userId().toString(), null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + identity.role().name())));
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Setup Tenant Context for Database Isolation (Hibernate Filters)
        if (requestedTenantId != null) {
            TenantContextHolder.setTenantId(requestedTenantId);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Guarantee cleanup of the thread local to prevent context leakage across
            // requests
            TenantContextHolder.clear();
        }
    }
}
