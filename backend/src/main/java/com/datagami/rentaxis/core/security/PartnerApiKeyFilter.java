package com.datagami.rentaxis.core.security;

import com.datagami.rentaxis.core.service.PromoterApiKeyService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.security.PublicRateLimitFilter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.EstimationProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authenticates the promoter developer API, {@code /api/v1/partner/**}, by
 * {@code X-Api-Key} and nothing else.
 *
 * <p>On partner paths the key is the only credential: an {@code Authorization} bearer
 * token and the X-User-* / X-Tenant-Id headers are never read, and
 * {@link ApiSecurityFilter} does not run at all ({@link #isPartnerPath} is its skip
 * test too, so the two agree on what a partner path is). The principal is a
 * {@link PartnerPrincipal} with the single authority {@link #PARTNER_AUTHORITY} —
 * no {@code ROLE_} prefix, so no {@code hasRole} check anywhere admits it, and
 * {@link PromoterFenceFilter} (which keys off {@code ROLE_PROMOTER}) ignores it. The
 * tenant context is the key's organisation for the request, cleared in {@code finally}.
 * Off partner paths this filter does nothing, so a key opens no other route.
 *
 * <p>Order of checks: (1) the client IP's failed-auth budget, before any lookup, so key
 * guessing is throttled before it costs a hash lookup; (2) the key, through
 * {@link PromoterApiKeyService#authenticate} with the tenant context cleared (it is
 * cross-tenant and re-checks the promoter's role, status and organisation every call);
 * a presented key that fails spends one unit of that IP's budget; (3) the key's own
 * request budget. Every key failure answers the same 401 body, so missing, unknown,
 * revoked, expired and dead-promoter keys cannot be told apart.
 *
 * <p>Constructed in {@code SecurityConfig}, not a {@code @Component}, so Boot does not
 * also register it as a plain servlet filter outside the security chain. The buckets
 * are per instance, in memory, per backend process.
 */
@Slf4j
public class PartnerApiKeyFilter extends OncePerRequestFilter {

    public static final String PARTNER_AUTHORITY = "PARTNER_API";
    public static final String API_KEY_HEADER = "X-Api-Key";
    public static final String PARTNER_PREFIX = "/api/v1/partner/";

    private static final PathPattern PARTNER_PATH = PathPatternParser.defaultInstance.parse("/api/v1/partner/**");

    private static final byte[] INVALID_KEY = "{\"error\":\"invalid_api_key\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RATE_LIMITED = "{\"error\":\"rate_limited\"}".getBytes(StandardCharsets.UTF_8);

    private final PromoterApiKeyService keys;
    private final int failedAuthPerMinute;
    private final int perKeyPerMinute;
    private final int perKeyBurst;

    private final ConcurrentHashMap<String, Bucket> failedAuthBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Bucket> keyBuckets = new ConcurrentHashMap<>();

    public PartnerApiKeyFilter(PromoterApiKeyService keys, int failedAuthPerMinute, int perKeyPerMinute,
                               int perKeyBurst) {
        this.keys = keys;
        this.failedAuthPerMinute = Math.max(1, failedAuthPerMinute);
        this.perKeyPerMinute = Math.max(1, perKeyPerMinute);
        this.perKeyBurst = Math.max(1, perKeyBurst);
    }

    /**
     * Whether {@code request} is on the partner API — matched on the path as the
     * dispatcher parses it (decoded, context path stripped), so a percent-encoded
     * spelling of the prefix cannot route to a partner controller without passing here.
     */
    public static boolean isPartnerPath(HttpServletRequest request) {
        return PARTNER_PATH.matches(PublicRateLimitFilter.requestPath(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isPartnerPath(request)) {
            chain.doFilter(request, response);
            return;
        }
        // Nothing from an earlier request on this pooled thread, and nothing from this
        // one afterwards; authenticate() is cross-tenant and must start with no tenant.
        TenantContextHolder.clear();
        try {
            authenticate(request, response, chain);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private void authenticate(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = PublicRateLimitFilter.clientIp(request);
        Bucket failed = failedAuthBuckets.computeIfAbsent(ip, k -> perMinute(failedAuthPerMinute, failedAuthPerMinute));
        EstimationProbe budget = failed.estimateAbilityToConsume(1);
        if (!budget.canBeConsumed()) {
            log.info("partner_api.rate_limited kind=failed_auth ip={}", ip);
            tooManyRequests(response, budget.getNanosToWaitForRefill());
            return;
        }

        String secret = request.getHeader(API_KEY_HEADER);
        if (secret == null || secret.isBlank()) {
            // No key is not a guess and costs no lookup: refused, budget untouched.
            invalidKey(response);
            return;
        }

        Optional<PromoterApiKeyService.AuthenticatedKey> found = keys.authenticate(secret.trim());
        if (found.isEmpty()) {
            failed.tryConsume(1);
            log.debug("partner_api.invalid_key ip={}", ip);
            invalidKey(response);
            return;
        }
        PromoterApiKeyService.AuthenticatedKey key = found.get();

        ConsumptionProbe probe = keyBuckets
                .computeIfAbsent(key.keyId(), k -> perMinute(perKeyBurst, perKeyPerMinute))
                .tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            log.info("partner_api.rate_limited kind=per_key keyId={}", key.keyId());
            tooManyRequests(response, probe.getNanosToWaitForRefill());
            return;
        }

        PartnerPrincipal principal = new PartnerPrincipal(key.keyId(), key.tenantId(), key.promoterUserId());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority(PARTNER_AUTHORITY))));
        SecurityContextHolder.setContext(context);
        TenantContextHolder.setTenantId(key.tenantId());

        chain.doFilter(request, response);
    }

    private static Bucket perMinute(int capacity, int refillPerMinute) {
        return Bucket.builder().addLimit(Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(refillPerMinute, Duration.ofMinutes(1))
                .build()).build();
    }

    private static void invalidKey(HttpServletResponse response) throws IOException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_KEY);
    }

    private static void tooManyRequests(HttpServletResponse response, long nanosToWait) throws IOException {
        long seconds = Math.max(1, (nanosToWait + 999_999_999L) / 1_000_000_000L);
        response.setHeader("Retry-After", Long.toString(seconds));
        write(response, 429, RATE_LIMITED);
    }

    private static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
