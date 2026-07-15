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

    private final ConcurrentHashMap<String, Bucket> publicBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> otpBuckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();
        boolean isOtp = uri.startsWith(OTP_PATH_PREFIX);
        boolean isPublic = uri.startsWith(PUBLIC_PATH_PREFIX);

        if (!isOtp && !isPublic) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = resolveClientIp(request);
        // Separate maps, so OTP traffic gets its own tighter budget and cannot be
        // starved by (or starve) unrelated /public/ traffic from the same IP.
        Bucket bucket = isOtp
                ? otpBuckets.computeIfAbsent(ip, k -> createOtpBucket())
                : publicBuckets.computeIfAbsent(ip, k -> createBucket());

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
