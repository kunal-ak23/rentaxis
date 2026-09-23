package com.datagami.rentaxis.core.security;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Filter-level contract tests, run against the real filter with a real
 * {@link AuthTokenService} (no mocks) so what is pinned here is the actual
 * routing between the bearer path and the legacy header path.
 *
 * <p>The single most important test in this class is
 * {@link #garbageBearerWithSpoofedLegacyHeadersIs401NotHeaderFallback}: it
 * pins that a presented-but-invalid token can never downgrade the request to
 * the spoofable header path.
 */
class ApiSecurityFilterTest {

    private static final String TOKEN_SECRET = "filter-test-token-secret-at-least-32-bytes";
    private static final String PROXY_SECRET = "internal-proxy-secret-for-tests";

    private final AuthTokenService enabledTokens = new AuthTokenService(TOKEN_SECRET);
    private final AuthTokenService disabledTokens = new AuthTokenService("");

    /**
     * Every token's row is current. Revocation against real rows is pinned by
     * {@code BearerTokenRevocationIT}; these tests are about routing.
     */
    private static final BearerTokenStateCheck ALWAYS_CURRENT = (identity, tenant) -> null;

    /** Today's production config: no token secret, no proxy secret, legacy allowed. */
    private ApiSecurityFilter legacyOnlyFilter() {
        return new ApiSecurityFilter(disabledTokens, ALWAYS_CURRENT, "", "allow");
    }

    /** Phase-1 activated config: token secret set, proxy gate set, legacy still allowed. */
    private ApiSecurityFilter activatedFilter() {
        return new ApiSecurityFilter(enabledTokens, ALWAYS_CURRENT, PROXY_SECRET, "allow");
    }

    /** Captures what the downstream servlet would observe, before the filter's cleanup. */
    private static class CapturingChain implements FilterChain {
        boolean invoked = false;
        Authentication auth;
        UUID tenantInContext;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            invoked = true;
            auth = SecurityContextHolder.getContext().getAuthentication();
            tenantInContext = TenantContextHolder.getTenantId();
        }
    }

    private static List<String> authorities(Authentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        return req;
    }

    @BeforeEach
    @AfterEach
    void resetContexts() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    // ------------------------------------------------------------------
    // Legacy path with NO secrets configured: byte-identical to today.
    // ------------------------------------------------------------------

    @Test
    void legacyRenterInOwnTenantPasses() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        MockHttpServletRequest req = request("/api/v1/properties");
        req.addHeader("X-User-Id", userId.toString());
        req.addHeader("X-User-Role", "RENTER");
        req.addHeader("X-Tenant-Id", tenant.toString());
        req.addHeader("X-User-Tenant-Id", tenant.toString());
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        legacyOnlyFilter().doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(chain.auth.getPrincipal()).isEqualTo(userId.toString());
        assertThat(authorities(chain.auth)).containsExactly("ROLE_RENTER");
        assertThat(chain.tenantInContext).isEqualTo(tenant);
        // Thread-local must be cleared after the chain returns.
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    /**
     * ACCOUNTANT is a tenant-scoped role like any other. It was left out of the
     * filter's same-tenant branch when the role was added, which made every
     * finance endpoint a 403 for the one role they exist for — the @PreAuthorize
     * on those controllers never even ran.
     */
    @Test
    void legacyAccountantInOwnTenantPasses() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        MockHttpServletRequest req = request("/api/v1/finance/journals");
        req.addHeader("X-User-Id", userId.toString());
        req.addHeader("X-User-Role", "ACCOUNTANT");
        req.addHeader("X-Tenant-Id", tenant.toString());
        req.addHeader("X-User-Tenant-Id", tenant.toString());
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        legacyOnlyFilter().doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(authorities(chain.auth)).containsExactly("ROLE_ACCOUNTANT");
        assertThat(chain.tenantInContext).isEqualTo(tenant);
    }

    /**
     * The active-tenant selector is optional: dropping {@code X-Tenant-Id} makes
     * the filter fall back to the caller's home tenant, so an ACCOUNTANT is
     * admitted and still scoped. Same rule the other tenant-scoped roles get.
     */
    @Test
    void legacyAccountantWithoutActiveTenantHeaderFallsBackToHomeTenant() throws Exception {
        UUID tenant = UUID.randomUUID();
        MockHttpServletRequest req = request("/api/v1/finance/journals");
        req.addHeader("X-User-Id", UUID.randomUUID().toString());
        req.addHeader("X-User-Role", "ACCOUNTANT");
        req.addHeader("X-User-Tenant-Id", tenant.toString());
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        legacyOnlyFilter().doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(authorities(chain.auth)).containsExactly("ROLE_ACCOUNTANT");
        assertThat(chain.tenantInContext).isEqualTo(tenant);
    }

    /**
     * With NEITHER tenant header there is nothing to scope to, and the request is
     * now refused instead of admitted.
     *
     * <p>It used to fall through the 403 (which only fired when an X-Tenant-Id was
     * actually presented) and reach the controllers authenticated but with no
     * TenantContext — which leaves the {@code tenantFilter} on BaseTenantEntity
     * DISABLED. That is not a harmlessly unscoped request: @PreAuthorize passes and
     * every repository read then spans all tenants. The replaced test
     * ({@code legacyAccountantWithNoTenantHeadersIsNotGivenATenantContext}) pinned
     * exactly that fall-through.
     */
    @Test
    void legacyAccountantWithNoTenantHeadersIs403() throws Exception {
        MockHttpServletRequest req = request("/api/v1/finance/journals");
        req.addHeader("X-User-Id", UUID.randomUUID().toString());
        req.addHeader("X-User-Role", "ACCOUNTANT");
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        legacyOnlyFilter().doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(chain.invoked).isFalse();
        assertThat(chain.tenantInContext).isNull();
    }

    /** Same rule for a second tenant-scoped role, so this is not an ACCOUNTANT special case. */
    @Test
    void legacyPropertyManagerWithNoTenantHeadersIs403() throws Exception {
        MockHttpServletRequest req = request("/api/v1/properties");
        req.addHeader("X-User-Id", UUID.randomUUID().toString());
        req.addHeader("X-User-Role", "PROPERTY_MANAGER");
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        legacyOnlyFilter().doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(chain.invoked).isFalse();
    }

    /**
     * An X-User-Role the filter does not know (a typo, a retired role, a probe) is
     * refused rather than admitted unscoped — it never matched a branch, so it never
     * set {@code authorized}.
     */
    @Test
    void legacyUnknownRoleWithNoTenantHeadersIs403() throws Exception {
        MockHttpServletRequest req = request("/api/v1/properties");
        req.addHeader("X-User-Id", UUID.randomUUID().toString());
        req.addHeader("X-User-Role", "AUDITOR");
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        legacyOnlyFilter().doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(chain.invoked).isFalse();
    }

    /** SUPER_ADMIN sets authorized unconditionally, so tightening the 403 leaves it alone. */
    @Test
    void legacySuperAdminWithNoTenantHeadersStillPasses() throws Exception {
        UUID userId = UUID.randomUUID();
        MockHttpServletRequest req = request("/api/v1/landlord-orgs");
        req.addHeader("X-User-Id", userId.toString());
        req.addHeader("X-User-Role", "SUPER_ADMIN");
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        legacyOnlyFilter().doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(authorities(chain.auth)).containsExactly("ROLE_SUPER_ADMIN");
        assertThat(chain.tenantInContext).isNull();
    }

    /** The bearer half of the same change: a verified non-admin with no tenant at all. */
    @Test
    void bearerTenantRoleWithNoHomeTenantAndNoTenantHeaderIs403() throws Exception {
        String token = enabledTokens.issue(UUID.randomUUID(), UserRole.ACCOUNTANT, null, List.of());

        MockHttpServletRequest req = request("/api/v1/finance/journals");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        activatedFilter().doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(chain.invoked).isFalse();
    }

    /** A verified SUPER_ADMIN has no home tenant by design and must still pass. */
    @Test
    void bearerSuperAdminWithoutAnyTenantStillPasses() throws Exception {
        String token = enabledTokens.issue(UUID.randomUUID(), UserRole.SUPER_ADMIN, null, List.of());

        MockHttpServletRequest req = request("/api/v1/landlord-orgs");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        activatedFilter().doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(chain.tenantInContext).isNull();
    }

    @Test
    void legacyAccountantRequestingAForeignTenantIs403() throws Exception {
        MockHttpServletRequest req = request("/api/v1/finance/journals");
        req.addHeader("X-User-Id", UUID.randomUUID().toString());
        req.addHeader("X-User-Role", "ACCOUNTANT");
        req.addHeader("X-Tenant-Id", UUID.randomUUID().toString());
        req.addHeader("X-User-Tenant-Id", UUID.randomUUID().toString());
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        legacyOnlyFilter().doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(chain.invoked).isFalse();
    }

    @Test
    void legacySuperAdminPassesWithoutInternalAuthWhenNoProxySecretConfigured() throws Exception {
        MockHttpServletRequest req = request("/api/v1/landlord-orgs");
        req.addHeader("X-User-Id", UUID.randomUUID().toString());
        req.addHeader("X-User-Role", "SUPER_ADMIN");
        req.addHeader("X-Tenant-Id", UUID.randomUUID().toString());
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        legacyOnlyFilter().doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(authorities(chain.auth)).containsExactly("ROLE_SUPER_ADMIN");
    }

    @Test
    void legacyForeignTenantIs403() throws Exception {
        MockHttpServletRequest req = request("/api/v1/properties");
        req.addHeader("X-User-Id", UUID.randomUUID().toString());
        req.addHeader("X-User-Role", "RENTER");
        req.addHeader("X-Tenant-Id", UUID.randomUUID().toString());
        req.addHeader("X-User-Tenant-Id", UUID.randomUUID().toString());
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        legacyOnlyFilter().doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(chain.invoked).isFalse();
    }

    @Test
    void legacyMalformedUuidIs400() throws Exception {
        MockHttpServletRequest req = request("/api/v1/properties");
        req.addHeader("X-User-Id", UUID.randomUUID().toString());
        req.addHeader("X-User-Role", "RENTER");
        req.addHeader("X-Tenant-Id", "not-a-uuid");
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        legacyOnlyFilter().doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(chain.invoked).isFalse();
    }

    @Test
    void legacyNoHeadersPassesThroughUnauthenticated() throws Exception {
        MockHttpServletRequest req = request("/api/v1/properties");
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        legacyOnlyFilter().doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(chain.auth).isNull();
        assertThat(chain.tenantInContext).isNull();
    }

    @Test
    void bearerHeaderIsIgnoredEntirelyWhileTokenServiceDisabled() throws Exception {
        // Pre-activation compat: a token (even garbage) must not change the
        // legacy path's behaviour while no secret is configured.
        UUID userId = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        MockHttpServletRequest req = request("/api/v1/properties");
        req.addHeader("Authorization", "Bearer complete-garbage");
        req.addHeader("X-User-Id", userId.toString());
        req.addHeader("X-User-Role", "RENTER");
        req.addHeader("X-Tenant-Id", tenant.toString());
        req.addHeader("X-User-Tenant-Id", tenant.toString());
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        legacyOnlyFilter().doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(chain.auth.getPrincipal()).isEqualTo(userId.toString());
    }

    // ------------------------------------------------------------------
    // Bearer path (token service enabled).
    // ------------------------------------------------------------------

    @Test
    void aRevokedBearerIs401AndNeverReachesTheChain() throws Exception {
        UUID user = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        String token = enabledTokens.issue(user, UserRole.TENANT_ADMIN, home, List.of(home), 3);
        UUID[] askedAbout = new UUID[1];
        int[] askedVersion = new int[1];
        BearerTokenStateCheck revoked = (identity, tenant) -> {
            askedAbout[0] = tenant;
            askedVersion[0] = identity.tokenVersion();
            return "token has been revoked";
        };

        MockHttpServletRequest req = request("/api/v1/properties");
        req.addHeader("Authorization", "Bearer " + token);
        // Legacy headers alongside: a revoked token must not fall back to them.
        req.addHeader("X-User-Id", user.toString());
        req.addHeader("X-User-Role", "TENANT_ADMIN");
        req.addHeader("X-User-Tenant-Id", home.toString());
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        new ApiSecurityFilter(enabledTokens, revoked, PROXY_SECRET, "allow").doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.invoked).isFalse();
        assertThat(askedAbout[0]).as("the check is asked about the tenant the request acts in").isEqualTo(home);
        assertThat(askedVersion[0]).isEqualTo(3);
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    void aSkippedPublicRouteNeitherInheritsNorLeavesATenant() throws Exception {
        // A tenant left on this pooled thread by an earlier request (audit P1-1).
        UUID stale = UUID.randomUUID();
        TenantContextHolder.setTenantId(stale);

        MockHttpServletRequest req = request("/api/v1/public/renewal-intent");
        req.addHeader("X-Tenant-ID", UUID.randomUUID().toString());
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain throwingChain = (rq, rs) -> {
            assertThat(TenantContextHolder.getTenantId()).as("the request starts clean").isNull();
            // What the old TenantInterceptor did, followed by a handler that throws.
            TenantContextHolder.setTenantId(UUID.randomUUID());
            throw new IllegalStateException("handler blew up");
        };

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> activatedFilter().doFilter(req, res, throwingChain))
                .isInstanceOf(IllegalStateException.class);

        assertThat(TenantContextHolder.getTenantId()).as("and ends clean, even when it throws").isNull();
    }

    @Test
    void validBearerTakesIdentityFromClaimsNotFromHeaders() throws Exception {
        UUID realUser = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        String token = enabledTokens.issue(realUser, UserRole.RENTER, home, List.of(home));

        MockHttpServletRequest req = request("/api/v1/properties");
        req.addHeader("Authorization", "Bearer " + token);
        // Spoofed identity headers alongside the token: must be ignored.
        req.addHeader("X-User-Id", UUID.randomUUID().toString());
        req.addHeader("X-User-Role", "SUPER_ADMIN");
        req.addHeader("X-User-Tenant-Id", UUID.randomUUID().toString());
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        activatedFilter().doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(chain.auth.getPrincipal()).isEqualTo(realUser.toString());
        assertThat(authorities(chain.auth)).containsExactly("ROLE_RENTER");
        assertThat(chain.tenantInContext).isEqualTo(home);
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    void garbageBearerWithSpoofedLegacyHeadersIs401NotHeaderFallback() throws Exception {
        // THE downgrade attack. If this test fails the whole fix is void: an
        // attacker sends a junk token plus spoofed SUPER_ADMIN headers and
        // must get 401, never the legacy header treatment.
        MockHttpServletRequest req = request("/api/v1/landlord-orgs");
        req.addHeader("Authorization", "Bearer junk.junk.junk");
        req.addHeader("X-User-Id", UUID.randomUUID().toString());
        req.addHeader("X-User-Role", "SUPER_ADMIN");
        req.addHeader("X-Tenant-Id", UUID.randomUUID().toString());
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        activatedFilter().doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.invoked).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void expiredBearerIs401() throws Exception {
        // Round-trip an otherwise-valid token whose expiry has passed, minted
        // with jjwt against the same secret.
        String expired = io.jsonwebtoken.Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("role", UserRole.RENTER.name())
                .claim("tids", List.of())
                .issuedAt(java.util.Date.from(java.time.Instant.now().minusSeconds(7200)))
                .expiration(java.util.Date.from(java.time.Instant.now().minusSeconds(3600)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        TOKEN_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();

        MockHttpServletRequest req = request("/api/v1/properties");
        req.addHeader("Authorization", "Bearer " + expired);
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        activatedFilter().doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.invoked).isFalse();
    }

    @Test
    void bearerWithMembershipTenantInTidsIsAllowed() throws Exception {
        UUID user = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        String token = enabledTokens.issue(user, UserRole.TENANT_ADMIN, home, List.of(home, membership));

        MockHttpServletRequest req = request("/api/v1/properties");
        req.addHeader("Authorization", "Bearer " + token);
        req.addHeader("X-Tenant-Id", membership.toString());
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        activatedFilter().doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(chain.tenantInContext).isEqualTo(membership);
    }

    @Test
    void bearerRequestingForeignTenantIs403() throws Exception {
        UUID user = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        String token = enabledTokens.issue(user, UserRole.TENANT_ADMIN, home, List.of(home));

        MockHttpServletRequest req = request("/api/v1/properties");
        req.addHeader("Authorization", "Bearer " + token);
        req.addHeader("X-Tenant-Id", UUID.randomUUID().toString());
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        activatedFilter().doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(chain.invoked).isFalse();
    }

    @Test
    void bearerSuperAdminMayRequestAnyTenant() throws Exception {
        UUID anyTenant = UUID.randomUUID();
        String token = enabledTokens.issue(UUID.randomUUID(), UserRole.SUPER_ADMIN, null, List.of());

        MockHttpServletRequest req = request("/api/v1/landlord-orgs");
        req.addHeader("Authorization", "Bearer " + token);
        req.addHeader("X-Tenant-Id", anyTenant.toString());
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        activatedFilter().doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(chain.tenantInContext).isEqualTo(anyTenant);
        // The internal-proxy gate is for legacy HEADER assertions of
        // SUPER_ADMIN only; a verified token needs no X-Internal-Auth.
    }

    @Test
    void bearerWithoutTenantHeaderFallsBackToHomeTenant() throws Exception {
        UUID home = UUID.randomUUID();
        String token = enabledTokens.issue(UUID.randomUUID(), UserRole.RENTER, home, List.of(home));

        MockHttpServletRequest req = request("/api/v1/properties");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        activatedFilter().doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(chain.tenantInContext).isEqualTo(home);
    }

    @Test
    void bearerWithMalformedTenantHeaderIs400() throws Exception {
        String token = enabledTokens.issue(UUID.randomUUID(), UserRole.RENTER, UUID.randomUUID(), List.of());

        MockHttpServletRequest req = request("/api/v1/properties");
        req.addHeader("Authorization", "Bearer " + token);
        req.addHeader("X-Tenant-Id", "not-a-uuid");
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        activatedFilter().doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(chain.invoked).isFalse();
    }

    // ------------------------------------------------------------------
    // Legacy SUPER_ADMIN internal-proxy gate.
    // ------------------------------------------------------------------

    @Test
    void legacySuperAdminWithoutInternalAuthIs403WhenSecretConfigured() throws Exception {
        MockHttpServletRequest req = request("/api/v1/landlord-orgs");
        req.addHeader("X-User-Id", UUID.randomUUID().toString());
        req.addHeader("X-User-Role", "SUPER_ADMIN");
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        activatedFilter().doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(chain.invoked).isFalse();
    }

    @Test
    void legacySuperAdminWithWrongInternalAuthIs403() throws Exception {
        MockHttpServletRequest req = request("/api/v1/landlord-orgs");
        req.addHeader("X-User-Id", UUID.randomUUID().toString());
        req.addHeader("X-User-Role", "SUPER_ADMIN");
        req.addHeader("X-Internal-Auth", "wrong-secret");
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        activatedFilter().doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(chain.invoked).isFalse();
    }

    @Test
    void legacySuperAdminWithCorrectInternalAuthPasses() throws Exception {
        UUID userId = UUID.randomUUID();
        MockHttpServletRequest req = request("/api/v1/landlord-orgs");
        req.addHeader("X-User-Id", userId.toString());
        req.addHeader("X-User-Role", "SUPER_ADMIN");
        req.addHeader("X-Internal-Auth", PROXY_SECRET);
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        activatedFilter().doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(chain.auth.getPrincipal()).isEqualTo(userId.toString());
        assertThat(authorities(chain.auth)).containsExactly("ROLE_SUPER_ADMIN");
    }

    @Test
    void internalProxyGateDoesNotApplyToNonSuperAdminRoles() throws Exception {
        UUID tenant = UUID.randomUUID();
        MockHttpServletRequest req = request("/api/v1/properties");
        req.addHeader("X-User-Id", UUID.randomUUID().toString());
        req.addHeader("X-User-Role", "RENTER");
        req.addHeader("X-Tenant-Id", tenant.toString());
        req.addHeader("X-User-Tenant-Id", tenant.toString());
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        activatedFilter().doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
    }

    // ------------------------------------------------------------------
    // Phase-2 kill switch: legacy-headers=deny.
    // ------------------------------------------------------------------

    @Test
    void denyModeRejectsLegacyHeadersWithoutBearer() throws Exception {
        UUID tenant = UUID.randomUUID();
        MockHttpServletRequest req = request("/api/v1/properties");
        req.addHeader("X-User-Id", UUID.randomUUID().toString());
        req.addHeader("X-User-Role", "RENTER");
        req.addHeader("X-Tenant-Id", tenant.toString());
        req.addHeader("X-User-Tenant-Id", tenant.toString());
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        new ApiSecurityFilter(enabledTokens, ALWAYS_CURRENT, "", "deny").doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.invoked).isFalse();
    }

    @Test
    void denyModeStillAcceptsAValidBearer() throws Exception {
        UUID user = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        String token = enabledTokens.issue(user, UserRole.RENTER, home, List.of(home));

        MockHttpServletRequest req = request("/api/v1/properties");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        new ApiSecurityFilter(enabledTokens, ALWAYS_CURRENT, "", "deny").doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(chain.auth.getPrincipal()).isEqualTo(user.toString());
    }

    @Test
    void denyModeDoesNotAffectSkippedPublicPaths() throws Exception {
        MockHttpServletRequest req = request("/api/auth/login");
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        new ApiSecurityFilter(enabledTokens, ALWAYS_CURRENT, "", "deny").doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
    }

    // ------------------------------------------------------------------
    // Skip-list preservation.
    // ------------------------------------------------------------------

    @Test
    void skipListPathsBypassAuthEntirelyEvenWithGarbageBearer() throws Exception {
        MockHttpServletRequest req = request("/api/v1/auth/firebase");
        req.addHeader("Authorization", "Bearer junk");
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        activatedFilter().doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(res.getStatus()).isEqualTo(200);
    }
}
