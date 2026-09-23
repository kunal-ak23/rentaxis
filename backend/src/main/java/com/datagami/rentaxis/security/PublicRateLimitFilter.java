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
    private static final PathPattern APPLE_AUTH_PATH = PARSER.parse("/api/auth/apple");
    private static final PathPattern REGISTRATION_PATH = PARSER.parse("/api/auth/register");

    /**
     * Password login and invite redemption (audit A-F4). Both are unauthenticated
     * and answer a guess: login a password, set-password (and its GET validate) a
     * 256-bit invite token. The token is not guessable, so that bucket is about
     * noise; login is the one an attacker would hammer. Keyed on the client IP,
     * which the web's NextAuth call forwards (X-Forwarded-For) so web logins are
     * not all counted against the web container's address.
     */
    private static final PathPattern LOGIN_PATH = PARSER.parse("/api/auth/login");
    private static final PathPattern SET_PASSWORD_PATH = PARSER.parse("/api/auth/set-password/**");

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
    private final ConcurrentHashMap<String, Bucket> loginBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> setPasswordBuckets = new ConcurrentHashMap<>();

    /**
     * Logins per minute per client IP. 20 leaves room for an office NAT and a few
     * typos per person while turning an online guessing run into a trickle.
     * A property, not a constant, only so the test JVM (hundreds of logins from
     * 127.0.0.1 across cached contexts) can raise it; prod keeps the default.
     */
    @org.springframework.beans.factory.annotation.Value("${app.rate-limit.login-per-minute:20}")
    private int loginPerMinute = 20;

    @org.springframework.beans.factory.annotation.Value("${app.rate-limit.set-password-per-minute:10}")
    private int setPasswordPerMinute = 10;

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
        boolean isAppleAuth = "POST".equals(request.getMethod()) && APPLE_AUTH_PATH.matches(path);
        boolean isRegistration =
                "POST".equals(request.getMethod()) && REGISTRATION_PATH.matches(path);
        boolean isPublic = PUBLIC_PATH.matches(path);
        boolean isScan = "POST".equals(request.getMethod()) && SCAN_PATH.matches(path);
        boolean isPromoEvents =
                "POST".equals(request.getMethod()) && PROMO_EVENTS_PATH.matches(path);
        boolean isLogin = "POST".equals(request.getMethod()) && LOGIN_PATH.matches(path);
        boolean isSetPassword = SET_PASSWORD_PATH.matches(path);

        if (!isFirebaseAuth && !isAppleAuth && !isRegistration && !isPublic && !isScan && !isPromoEvents
                && !isLogin && !isSetPassword) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = resolveClientIp(request);
        // Separate maps, so auth traffic gets its own tighter budget and cannot be
        // starved by (or starve) unrelated /public/ traffic from the same IP.
        Bucket bucket;
        if (isRegistration) {
            bucket = registrationBuckets.computeIfAbsent(ip, k -> createRegistrationBucket());
        } else if (isLogin) {
            bucket = loginBuckets.computeIfAbsent(ip, k -> perMinute(loginPerMinute));
        } else if (isSetPassword) {
            bucket = setPasswordBuckets.computeIfAbsent(ip, k -> perMinute(setPasswordPerMinute));
        } else if (isFirebaseAuth || isAppleAuth) {
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

    private static Bucket perMinute(int n) {
        return Bucket.builder().addLimit(Bandwidth.builder()
                .capacity(n)
                .refillGreedy(n, Duration.ofMinutes(1))
                .build()).build();
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
        return clientIp(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"));
    }

    /**
     * The address a bucket is keyed on.
     *
     * <p>{@code X-Forwarded-For} is honoured only when the connection comes from
     * a trusted proxy — loopback or a private network, which in production is
     * Caddy or the web container on the Docker network (the backend is not
     * published to the internet). Anyone else's header is their own claim, and
     * rotating it used to buy a fresh login budget per request, because the
     * <em>left-most</em> entry — the one the client writes — was taken.</p>
     *
     * <p>From a trusted proxy the <em>right-most</em> entry is used: the address
     * that proxy itself saw. Caddy (2.5+, no {@code trusted_proxies}) replaces
     * any incoming header with the peer address, and the web server forwards the
     * header Caddy gave it, so today there is one entry; if a proxy ever appends
     * instead, the right-most entry is still the one a client cannot forge.</p>
     */
    static String clientIp(String remoteAddr, String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank() || !isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty()) {
                return hop;
            }
        }
        return remoteAddr;
    }

    /** Loopback, RFC 1918 / IPv6 unique-local, or link-local: a peer on our own network. */
    static boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        // Only IP literals: never let a hostname trigger a DNS lookup here.
        if (!remoteAddr.matches("[0-9a-fA-F:.%]+") || !remoteAddr.matches(".*[.:].*")) {
            return false;
        }
        try {
            java.net.InetAddress a = java.net.InetAddress.getByName(remoteAddr);
            if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) {
                return true;
            }
            byte[] b = a.getAddress();
            return b.length == 16 && (b[0] & 0xFE) == 0xFC; // fc00::/7
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }
}
