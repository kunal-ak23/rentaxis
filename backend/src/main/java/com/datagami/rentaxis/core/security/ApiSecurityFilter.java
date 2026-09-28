package com.datagami.rentaxis.core.security;

import com.datagami.rentaxis.api.AssetController;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Optional;
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
 *   <li><b>Legacy X-User-* headers</b>: everything else. {@code X-User-Id}
 *       names the caller; the role, status and tenant access are read from the
 *       database (break round 1, F1/F2 — see {@code handleLegacyHeaders}).
 *       Two opt-in tightenings on top: (a) SUPER_ADMIN assertions must carry
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

    private static final Logger log = LoggerFactory.getLogger(ApiSecurityFilter.class);

    private final AuthTokenService authTokenService;
    private final BearerTokenStateCheck tokenStateCheck;
    private final LegacyHeaderIdentityCheck legacyIdentityCheck;
    private final String internalProxySecret;
    private final String legacyHeadersMode;

    public ApiSecurityFilter(AuthTokenService authTokenService,
            BearerTokenStateCheck tokenStateCheck,
            LegacyHeaderIdentityCheck legacyIdentityCheck,
            @Value("${app.auth.internal-proxy-secret:}") String internalProxySecret,
            @Value("${app.auth.legacy-headers:allow}") String legacyHeadersMode) {
        this.authTokenService = authTokenService;
        this.tokenStateCheck = tokenStateCheck;
        this.legacyIdentityCheck = legacyIdentityCheck;
        this.internalProxySecret = internalProxySecret == null ? "" : internalProxySecret;
        this.legacyHeadersMode = legacyHeadersMode == null ? "allow" : legacyHeadersMode;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // The tenant is a ThreadLocal on a pooled Tomcat thread (security audit
        // P1-1). Whatever an earlier request left on this thread must never be
        // seen by this one, and nothing this request sets may outlive it — on
        // every path, including the skipped public routes and the error paths
        // that used to return without clearing. TenantContextResetFilter does
        // the same at the outermost edge; this is the second line.
        TenantContextHolder.clear();
        try {
            authenticate(request, response, filterChain);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private void authenticate(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Skip the pre-authentication routes (login, register, set-password, ...).
        // The self-service profile routes under /api/auth/me act AS the signed-in
        // user, so they are not skipped: they need the principal this filter sets
        // (PR #342). Skipping them left every one anonymous, so the controller
        // could only take identity from a raw X-User-Id header — which, with no
        // filter in front, anyone could send, token or not, and which the phase-2
        // legacy-header deny would never have reached.
        if (!isSelfServiceProfilePath(path)
                && (path.startsWith("/api/v1/auth/") || path.startsWith("/api/auth/")
                || path.startsWith("/actuator/") || path.startsWith("/api/webhooks/")
                // Only the public asset folder skips authentication (issue #300):
                // every other storage key under /serve now requires a caller, and
                // skipping the filter for it would leave that caller anonymous.
                || path.startsWith("/api/v1/assets/serve/" + AssetController.PUBLIC_PREFIX + "/")
                || path.startsWith("/public/")
                || path.startsWith("/api/v1/public/"))) {
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
            refuse(response, HttpServletResponse.SC_UNAUTHORIZED, AuthReason.LEGACY_DENIED,
                    "A valid bearer token is required.");
            return;
        }

        // Path 2: legacy headers, passed from the Next.js proxy middleware and
        // (for now) the installed mobile apps.
        handleLegacyHeaders(request, response, filterChain, path);
    }

    /**
     * Legacy path. {@code X-User-Id} names the caller; everything else about
     * them — role, status, which organisations they belong to — is read from
     * the database (through {@link LegacyHeaderIdentityCheck}'s short cache,
     * evicted on every in-process change), never from the headers (break round
     * 1, F1/F2). The web session revalidates only every 5 minutes and froze its
     * membership list at login, so trusting {@code X-User-Role} and
     * {@code X-User-Tenant-Id} let a demoted admin re-promote themselves, a
     * deleted user keep writing, and a user moved to another organisation keep
     * acting in the old one. The rules below mirror {@link #handleBearer} plus
     * {@link TokenRevocationService#rejectionReason}: unknown or non-ACTIVE user
     * 401; requested tenant must be the home tenant or a membership, else 403
     * (SUPER_ADMIN reaches any); active organisation must be ACTIVE, else 401
     * (SUPER_ADMIN exempt).
     */
    private void handleLegacyHeaders(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain, String path) throws ServletException, IOException {
        String userIdStr = request.getHeader("X-User-Id");
        // Still required to enter this path (a request without it stays
        // anonymous, as before), but only the stored role is ever granted.
        String assertedRole = request.getHeader("X-User-Role");
        String activeTenantIdStr = request.getHeader("X-Tenant-Id");
        String homeTenantIdStr = request.getHeader("X-User-Tenant-Id");

        if (userIdStr != null && assertedRole != null) {
            // SUPER_ADMIN gate on the assertion itself, before any lookup: an
            // unauthenticated header claim of the one role that bypasses tenant
            // scoping. No mobile app uses SUPER_ADMIN, so once
            // app.auth.internal-proxy-secret is set only the web proxy (which
            // sends X-Internal-Auth) may assert it. Unset secret => gate off.
            if ("SUPER_ADMIN".equals(assertedRole) && !internalProxyAuthenticated(request)) {
                refuse(response, HttpServletResponse.SC_FORBIDDEN, AuthReason.PROXY_AUTH_REQUIRED,
                        "SUPER_ADMIN requires internal proxy authentication.");
                return;
            }

            UUID userId;
            UUID requestedTenantId;
            UUID assertedHomeTenantId;
            try {
                userId = UUID.fromString(userIdStr);
                requestedTenantId = (activeTenantIdStr != null && !activeTenantIdStr.isBlank())
                        ? UUID.fromString(activeTenantIdStr)
                        : null;
                assertedHomeTenantId = (homeTenantIdStr != null && !homeTenantIdStr.isBlank()
                        && !homeTenantIdStr.equals("undefined"))
                                ? UUID.fromString(homeTenantIdStr)
                                : null;
            } catch (IllegalArgumentException e) {
                refuse(response, HttpServletResponse.SC_BAD_REQUEST, AuthReason.BAD_HEADERS, "Invalid UUID format in context headers.");
                return;
            }

            Optional<LegacyHeaderIdentityCheck.CurrentUser> found = legacyIdentityCheck.currentUser(userId);
            if (found.isEmpty() || !found.get().active() || found.get().role() == null) {
                log.info("Refusing legacy identity headers for user {}: {}", userId,
                        found.isEmpty() ? "user no longer exists" : "user is not active");
                refuse(response, HttpServletResponse.SC_UNAUTHORIZED, AuthReason.USER_INACTIVE, "Unknown or inactive user.");
                return;
            }
            LegacyHeaderIdentityCheck.CurrentUser user = found.get();
            UserRole role = user.role();
            if (!role.name().equals(assertedRole) && log.isDebugEnabled()) {
                // Debug, not info: after a demotion the stale web session keeps
                // asserting the old role on every request until it revalidates.
                log.debug("Legacy headers for user {} asserted role {} but the stored role is {}; using the stored role",
                        userId, assertedRole, role);
            }

            // A stored SUPER_ADMIN presented under another asserted role still
            // needs the proxy's proof before it is granted SUPER_ADMIN.
            if (role == UserRole.SUPER_ADMIN && !internalProxyAuthenticated(request)) {
                refuse(response, HttpServletResponse.SC_FORBIDDEN, AuthReason.PROXY_AUTH_REQUIRED,
                        "SUPER_ADMIN requires internal proxy authentication.");
                return;
            }

            // Tenant authorization, judged against the stored home tenant and
            // memberships. X-Tenant-Id is the active-tenant selector; without it
            // X-User-Tenant-Id (what the web proxy and mobile send as the
            // caller's tenant) selects, and without either the stored home
            // tenant does. SUPER_ADMIN reaches any tenant, or none.
            //
            // No `&& requestedTenantId != null` shortcut for the other roles:
            // with no tenant a tenant-scoped role would reach the controllers
            // with NO TenantContext, which disables the tenantFilter on
            // BaseTenantEntity — a cross-tenant read, not a harmless unscoped one.
            //
            // The self-service profile routes (/api/auth/me*) skip the
            // organisation checks: they set no tenant context and act on the
            // caller's own row, and they are how the web session revalidates. A
            // user moved to another organisation still presents their OLD home
            // tenant there; refusing that with a 403 left the session stale for
            // its whole life instead of letting /me report the new tenant. The
            // user must still exist and be ACTIVE (checked above).
            boolean selfService = isSelfServiceProfilePath(path);
            boolean authorized;
            if (role == UserRole.SUPER_ADMIN || selfService) {
                authorized = true;
            } else {
                if (requestedTenantId == null) {
                    requestedTenantId = assertedHomeTenantId != null ? assertedHomeTenantId : user.homeTenantId();
                }
                authorized = user.belongsTo(requestedTenantId);
            }
            if (!authorized) {
                refuse(response, HttpServletResponse.SC_FORBIDDEN, AuthReason.NOT_A_MEMBER, "Access to requested tenant is forbidden.");
                return;
            }

            // Same rule as bearer: an organisation that is not ACTIVE admits no
            // one but SUPER_ADMIN (the role that re-activates it).
            if (requestedTenantId != null && role != UserRole.SUPER_ADMIN && !selfService
                    && !legacyIdentityCheck.orgActive(requestedTenantId)) {
                log.info("Refusing legacy identity headers for user {}: organisation {} is not active",
                        userId, requestedTenantId);
                refuse(response, HttpServletResponse.SC_UNAUTHORIZED, AuthReason.ORG_INACTIVE, "Organisation is not active.");
                return;
            }

            // Establish Spring Security Context from the stored role.
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    userId.toString(), null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role.name())));
            SecurityContextHolder.getContext().setAuthentication(auth);

            // Setup Tenant Context for Database Isolation (Hibernate Filters)
            if (requestedTenantId != null && !selfService) {
                TenantContextHolder.setTenantId(requestedTenantId);
            }
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
     * True when no internal-proxy secret is configured (gate off, for
     * compatibility) or the request carries the matching {@code X-Internal-Auth}.
     */
    private boolean internalProxyAuthenticated(HttpServletRequest request) {
        if (internalProxySecret.isBlank()) {
            return true;
        }
        String presented = request.getHeader("X-Internal-Auth");
        byte[] expected = internalProxySecret.getBytes(StandardCharsets.UTF_8);
        byte[] actual = presented != null ? presented.getBytes(StandardCharsets.UTF_8) : new byte[0];
        // MessageDigest.isEqual: constant-time comparison — do not replace with
        // String.equals, which leaks a timing oracle.
        return MessageDigest.isEqual(expected, actual);
    }

    /** Response header naming why this filter refused a request (never set on success). */
    public static final String AUTH_REASON_HEADER = "X-Auth-Reason";

    /**
     * Stable, machine-readable reasons for this filter's refusals. A header, not
     * the error body: {@code server.error.include-message} is off, so a
     * {@code sendError} message never reaches the client. They only tell a client
     * which recovery applies (sign out, or pick another organisation); none
     * reveals more than the status code already does.
     */
    public enum AuthReason {
        /** The named user does not exist or is not ACTIVE: the session is over. */
        USER_INACTIVE,
        /** The organisation the request acts in is not ACTIVE. */
        ORG_INACTIVE,
        /** The caller is not a member of the requested organisation (or named none). */
        NOT_A_MEMBER,
        /** Legacy X-User-* headers are switched off (app.auth.legacy-headers=deny). */
        LEGACY_DENIED,
        /** The bearer token is invalid, expired or revoked. */
        BAD_TOKEN,
        /** SUPER_ADMIN asserted without the internal proxy's proof. */
        PROXY_AUTH_REQUIRED,
        /** A malformed id in the identity headers. */
        BAD_HEADERS;

        /** The code for a {@link BearerTokenStateCheck} rejection. */
        static AuthReason forRejection(String rejection) {
            if (TokenRevocationService.ORG_NOT_ACTIVE.equals(rejection)) return ORG_INACTIVE;
            if (TokenRevocationService.TOKEN_REVOKED.equals(rejection)) return BAD_TOKEN;
            return USER_INACTIVE;
        }
    }

    private static void refuse(HttpServletResponse response, int status, AuthReason reason, String message)
            throws IOException {
        response.setHeader(AUTH_REASON_HEADER, reason.name());
        response.sendError(status, message);
    }

    /**
     * {@code /api/auth/me} and everything under it; SecurityConfig requires
     * authentication there.
     *
     * <p>These routes get a principal but no tenant context. They are about the
     * user, not a tenant: they read the caller's own user row by id, their
     * memberships and org names. With the active tenant set, a multi-tenant admin
     * working in a secondary organisation could not load their own profile (the
     * tenant filter hides a user row whose home tenant is another one). Leaving it
     * unset is what these routes had when the filter skipped them.
     */
    public static boolean isSelfServiceProfilePath(String path) {
        return path.equals(SELF_SERVICE_PROFILE_PATH) || path.startsWith(SELF_SERVICE_PROFILE_PATH + "/");
    }

    public static final String SELF_SERVICE_PROFILE_PATH = "/api/auth/me";

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
            refuse(response, HttpServletResponse.SC_UNAUTHORIZED, AuthReason.BAD_TOKEN, "Invalid or expired bearer token.");
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
            refuse(response, HttpServletResponse.SC_BAD_REQUEST, AuthReason.BAD_HEADERS, "Invalid UUID format in context headers.");
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

        // Same shape as the legacy branch: a verified non-SUPER_ADMIN token with no
        // home tenant and no X-Tenant-Id is refused rather than let through unscoped.
        if (!authorized) {
            refuse(response, HttpServletResponse.SC_FORBIDDEN, AuthReason.NOT_A_MEMBER, "Access to requested tenant is forbidden.");
            return;
        }

        // Revocation (audit P1-2): a genuine, unexpired token is still refused
        // once the row behind it has moved on — password or role changed, user
        // moved, removed, deleted or deactivated, or the organisation it acts in
        // deactivated. 401, not 403: the token itself is dead, and a 401 is what
        // sends the mobile apps back to their login screen. After the tenant
        // authorization so a request for somebody else's organisation still
        // gets its 403 rather than a verdict on that organisation's status.
        String rejection = tokenStateCheck.rejectionReason(identity, requestedTenantId);
        if (rejection != null) {
            log.info("Refusing bearer token for user {}: {}", identity.userId(), rejection);
            refuse(response, HttpServletResponse.SC_UNAUTHORIZED, AuthReason.forRejection(rejection),
                    "Invalid or expired bearer token.");
            return;
        }

        // Establish Spring Security Context from the verified claims.
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                identity.userId().toString(), null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + identity.role().name())));
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Setup Tenant Context for Database Isolation (Hibernate Filters)
        if (requestedTenantId != null && !isSelfServiceProfilePath(request.getRequestURI())) {
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
