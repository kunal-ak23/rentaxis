package com.datagami.rentaxis.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PublicRateLimitFilter extends OncePerRequestFilter {

    /**
     * Guard OTP login. Scoped to {@code /api/auth/otp/} specifically rather than
     * all of {@code /api/auth/**}: these endpoints are permitAll and pre-auth, so
     * without an IP limit the only bound on code guessing is the per-phone cap in
     * OtpLoginService. Widening this to {@code /api/auth/**} would also throttle
     * {@code /login}, which is a behaviour change and out of scope here.
     */
    private static final String OTP_PATH_PREFIX = "/api/auth/otp/";

    private static final String PUBLIC_PATH_PREFIX = "/public/";

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
    private static final String SCAN_PATH = "/api/v1/gatepass/scan";

    private final ConcurrentHashMap<String, Bucket> publicBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> otpBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> scanBuckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();
        boolean isOtp = uri.startsWith(OTP_PATH_PREFIX);
        boolean isPublic = uri.startsWith(PUBLIC_PATH_PREFIX);
        boolean isScan = "POST".equals(request.getMethod()) && SCAN_PATH.equals(uri);

        if (!isOtp && !isPublic && !isScan) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = resolveClientIp(request);
        // Separate maps, so OTP traffic gets its own tighter budget and cannot be
        // starved by (or starve) unrelated /public/ traffic from the same IP.
        Bucket bucket;
        if (isOtp) {
            bucket = otpBuckets.computeIfAbsent(ip, k -> createOtpBucket());
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
     * Deliberately far tighter than the general public bucket: a legitimate guard
     * login is two calls (request + verify), and a retry or two on top. 10/min
     * leaves ample headroom for a shared gatehouse NAT while removing the
     * high-volume online guessing that 60/min would still permit.
     */
    private Bucket createOtpBucket() {
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
