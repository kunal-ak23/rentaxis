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
    private static final PathPattern REGISTRATION_PATH = PARSER.parse("/api/auth/register");

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

    /**
     * Promotion impression/click ingest. Authenticated (RENTER only), throttled for
     * two reasons the per-request logic cannot cover.
     *
     * <p>First, the per-renter-per-day click cap in {@code PromotionFeedService} is
     * check-then-act: it reads today's count, compares, then inserts, inside a READ
     * COMMITTED transaction. Impressions have a real backstop — the partial unique
     * index {@code uq_promo_impression_per_day} — but clicks deliberately have none,
     * because a second tap is a genuine second tap. So concurrent requests all read
     * the same count and all pass the check, and the cap is multiplied by
     * parallelism. Clicks are the number a client is shown to justify an ad slot,
     * so that matters.
     *
     * <p>Second, {@code recordEvents} resolves full ad eligibility — a lease join
     * plus a scan with three correlated subqueries — before it can know whether any
     * ad id in the batch is real. A body of 50 random UUIDs therefore costs the
     * whole resolution and returns 202 having done nothing.
     *
     * <p>IP-keyed like the buckets above. The authenticated principal would be the
     * better key, but it comes from a client-supplied {@code X-User-Id} header that
     * is itself the subject of an open trust issue — an attacker who can forge it
     * can rotate it for a fresh bucket, so keying on it would weaken this rather
     * than strengthen it. Revisit once that is closed.
     */
    private static final PathPattern PROMO_EVENTS_PATH = PARSER.parse("/api/v1/promotions/events");

    private final ConcurrentHashMap<String, Bucket> publicBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> firebaseAuthBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> registrationBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> scanBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> promoEventBuckets = new ConcurrentHashMap<>();

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
        boolean isRegistration =
                "POST".equals(request.getMethod()) && REGISTRATION_PATH.matches(path);
        boolean isPublic = PUBLIC_PATH.matches(path);
        boolean isScan = "POST".equals(request.getMethod()) && SCAN_PATH.matches(path);
        boolean isPromoEvents =
                "POST".equals(request.getMethod()) && PROMO_EVENTS_PATH.matches(path);

        if (!isFirebaseAuth && !isRegistration && !isPublic && !isScan && !isPromoEvents) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = resolveClientIp(request);
        // Separate maps, so auth traffic gets its own tighter budget and cannot be
        // starved by (or starve) unrelated /public/ traffic from the same IP.
        Bucket bucket;
        if (isRegistration) {
            bucket = registrationBuckets.computeIfAbsent(ip, k -> createRegistrationBucket());
        } else if (isFirebaseAuth) {
            bucket = firebaseAuthBuckets.computeIfAbsent(ip, k -> createFirebaseAuthBucket());
        } else if (isScan) {
            bucket = scanBuckets.computeIfAbsent(ip, k -> createScanBucket());
        } else if (isPromoEvents) {
            bucket = promoEventBuckets.computeIfAbsent(ip, k -> createPromoEventsBucket());
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

    /**
     * 20/min for promotion event flushes.
     *
     * <p><b>Why it cannot impede a real renter.</b> The app flushes on carousel
     * dispose and on app background, so a busy session is a handful of requests a
     * minute. 20 leaves several times that headroom, and absorbs a few renters
     * sharing a building's NAT.
     *
     * <p><b>What it bounds.</b> Without it, the click cap is defeated outright by
     * parallelism — 200 concurrent requests each carrying 10 clicks for one ad all
     * read a count of zero and all insert. This does not make the cap exact; it
     * makes the residual small and slow enough to detect. The exact fix is a DB
     * backstop for clicks, which is tracked separately.
     */
    private Bucket createPromoEventsBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(20)
                .refillGreedy(20, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private Bucket createBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(60)
                .refillGreedy(60, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * Self-registration creates both a tenant and its first administrator, so
     * it needs a much tighter budget than ordinary public reads. Five attempts
     * per hour still allows corrections and small-office NAT sharing while
     * bounding anonymous tenant/database growth.
     */
    private Bucket createRegistrationBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(5)
                .refillGreedy(5, Duration.ofHours(1))
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
