package com.datagami.rentaxis.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PublicRateLimitFilter extends OncePerRequestFilter {

    /**
     * Patterns, not raw-string comparisons — see {@link #resolvePath}. These are parsed
     * and matched exactly as {@code @RequestMapping} values are, so what this filter
     * thinks a request is cannot drift from what the dispatcher routes it to.
     */
    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

    /**
     * Firebase token exchange is permitAll and pre-auth. Firebase performs the
     * SMS-code throttling and verification; this bucket limits replay/noise at
     * the RentAxis exchange endpoint without affecting password login.
     */
    private static final PathPattern FIREBASE_AUTH_PATH = PARSER.parse("/api/v1/auth/firebase");
    private static final PathPattern LEGACY_FIREBASE_AUTH_PATH = PARSER.parse("/api/auth/firebase");

    private static final PathPattern PUBLIC_PATH = PARSER.parse("/public/**");

    /**
     * Gate-pass scanning. Authenticated (SECURITY_GUARD only), unlike the two
     * prefixes above — it is throttled anyway because the credential it accepts is
     * an 8-digit numeric code (~26.6 bits), and the reply to a correct guess is
     * guest PII: name, phone, vehicle, purpose. A compromised or rogue guard
     * account can therefore mine live codes for guests it has no legitimate reason
     * to see, and the per-scan authorization checks in GatePassScanService bound
     * *which* passes resolve, not how fast they can be probed. An exact match
     * rather than a prefix: this is the only gate-pass endpoint taking a guessable
     * credential.
     */
    private static final PathPattern SCAN_PATH = PARSER.parse("/api/v1/gatepass/scan");

    private final ConcurrentHashMap<String, Bucket> publicBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> firebaseAuthBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> scanBuckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // Parsed once and shared: parseAndCache re-parses and overwrites on every call,
        // so asking it per pattern would parse the same URI three times a request.
        PathContainer path = resolvePath(request);
        boolean isFirebaseAuth =
                "POST".equals(request.getMethod())
                        && (FIREBASE_AUTH_PATH.matches(path) || LEGACY_FIREBASE_AUTH_PATH.matches(path));
        boolean isPublic = PUBLIC_PATH.matches(path);
        boolean isScan = "POST".equals(request.getMethod()) && SCAN_PATH.matches(path);

        if (!isFirebaseAuth && !isPublic && !isScan) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = resolveClientIp(request);
        // Separate maps, so auth traffic gets its own tighter budget and cannot be
        // starved by (or starve) unrelated /public/ traffic from the same IP.
        Bucket bucket;
        if (isFirebaseAuth) {
            bucket = firebaseAuthBuckets.computeIfAbsent(ip, k -> createFirebaseAuthBucket());
        } else if (isScan) {
            bucket = scanBuckets.computeIfAbsent(ip, k -> createScanBucket());
        } else {
            bucket = publicBuckets.computeIfAbsent(ip, k -> createBucket());
        }

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setContentType("text/plain");
            response.getWriter().write("Rate limit exceeded");
        }
    }

    private Bucket createBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(60)
                .refillGreedy(60, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * A legitimate guard login is one exchange. Ten per minute leaves ample
     * headroom for a shared gatehouse NAT while limiting token replay/noise.
     */
    private Bucket createFirebaseAuthBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(10)
                .refillGreedy(10, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * 30/min for gate-pass scans.
     *
     * <p><b>Why it cannot impede a real guard.</b> A busy gate is a few scans per
     * minute — even a rush of one arrival every 5 seconds is 12/min, so 30/min
     * leaves ~2.5x headroom for one guard, and absorbs the two or three guards a
     * single gatehouse NAT typically shares. A large site running more than a
     * handful of concurrent gates behind one public IP would feel this; that is the
     * accepted cost of an IP-keyed bucket, and the note below explains why the
     * better key is not available here.
     *
     * <p><b>What it bounds.</b> A rogue guard is capped at ~43k guesses/day against
     * a 10^8 code space. For a tenant holding ~100 live codes that is an expected
     * yield well under one hit per day, versus effectively unlimited harvesting
     * with no throttle. It does not make enumeration impossible — nothing IP-keyed
     * can — it makes it slow enough to be worth detecting.
     *
     * <p><b>That ~100 assumes rejections carry no PII.</b> The arithmetic counts only
     * live codes at the guard's own properties as hits, which holds because a scan
     * rejected for an unassigned property returns the verdict and nothing else
     * ({@code GatePassScanService.scan}). Were a rejection to describe its guest again,
     * the target set would widen to every live code in the tenant and this bucket would
     * be sized against the wrong number — treat that blinding and this limit as one
     * control, not two.
     *
     * <p><b>Why keyed on IP and not the guard's user id</b>, which would be both
     * tighter and NAT-proof: SecurityConfig runs this filter <i>before</i>
     * ApiSecurityFilter (deliberately — abusive IPs must be throttled before any
     * token parsing or DB work), so no SecurityContext exists yet to read a user id
     * from. Moving the check after auth would trade that property away.
     */
    private Bucket createScanBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(30)
                .refillGreedy(30, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * The path these patterns are matched against — <b>parsed as the dispatcher parses
     * it</b>, so the two cannot disagree about what request this is.
     *
     * <p>This is load-bearing, not tidying. {@code getRequestURI()} is raw per the
     * Servlet spec, but Spring routes on the decoded path. Comparing a constant against
     * the raw URI therefore let {@code POST /api/v1/gatepass/%73can} slip past every
     * check here — no bucket consumed — after which the dispatcher decoded {@code %73}
     * to {@code s} and ran the scan handler anyway. StrictHttpFirewall permits
     * {@code %73}, so nothing downstream caught it: the limit was <i>absent</i>, not
     * loosened. The prefixes were exposed the same way to an encoding inside the prefix
     * ({@code /api/%61uth/firebase}), hence one shared mechanism rather than a
     * scan-only patch.
     *
     * <p>Matching {@code PathPattern} against the parsed {@code RequestPath} is what
     * makes filter and dispatcher agree <i>by construction</i> rather than by two
     * decoders that happen to concur — note in particular that the decoded form lives in
     * each segment's {@code valueToMatch()}, while {@code PathContainer.value()} is
     * still the raw text, so "just decode the string" quietly reintroduces the bug.
     * {@code parseAndCache} is safe this early: DispatcherServlet reuses a cached path
     * instead of re-parsing, which is exactly what Spring's own
     * {@code ServletRequestPathFilter} exists to do.
     *
     * <p>{@code pathWithinApplication()} also strips any context path, which is what
     * these patterns are written relative to (they mirror {@code @RequestMapping}
     * values). The app has no context path today, so coverage is identical to the raw
     * URI's; if one is ever configured this stays correct, where {@code getRequestURI()}
     * would silently stop matching and disable every limit here.
     *
     * <p>A URI that will not parse cannot be routed to a handler either, so it is failed
     * closed onto the raw value rather than waved through.
     */
    private PathContainer resolvePath(HttpServletRequest request) {
        try {
            return ServletRequestPathUtils.parseAndCache(request).pathWithinApplication();
        } catch (RuntimeException ex) {
            return PathContainer.parsePath(request.getRequestURI());
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // X-Forwarded-For may be a comma-separated list; take first entry
            int commaIdx = forwarded.indexOf(',');
            return commaIdx >= 0 ? forwarded.substring(0, commaIdx).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }
}
