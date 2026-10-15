package com.datagami.rentaxis.core.service.auth;

import com.datagami.rentaxis.core.service.UserService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * Public "forgot password" (2026-10-15). The endpoint always gives the same
 * answer, and this hands the real work to {@code passwordResetExecutor} so the
 * answer also comes back in the same time whether or not the email has a login
 * (no enumeration by response or by timing).
 *
 * <p>Two limits: per client IP in {@code PublicRateLimitFilter}, and here per
 * email address ({@code app.rate-limit.forgot-password-per-email-per-hour},
 * default 3), so one inbox cannot be flooded from many addresses. A request over
 * the email limit is dropped silently — refusing it out loud would say the
 * address had been asked about.
 */
@Service
public class ForgotPasswordService {

    private static final Logger AUDIT = LoggerFactory.getLogger("security.audit");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserService userService;
    private final Executor executor;
    /**
     * Review P3-1: bounded. An address's bucket is dropped an hour after its last
     * request (by then it would have refilled anyway), and the map never holds more
     * than {@link #MAX_TRACKED} addresses — so rotating through emails cannot grow
     * the heap. Evicting early under that cap only ever gives an address a fresh
     * budget, which the per-IP limit still bounds.
     */
    static final long MAX_TRACKED = 100_000;
    private final com.github.benmanes.caffeine.cache.Cache<String, Bucket> perEmail;

    @Value("${app.rate-limit.forgot-password-per-email-per-hour:3}")
    private int perEmailPerHour = 3;

    @org.springframework.beans.factory.annotation.Autowired
    public ForgotPasswordService(UserService userService,
                                 @Qualifier("passwordResetExecutor") Executor executor) {
        this(userService, executor, com.github.benmanes.caffeine.cache.Ticker.systemTicker());
    }

    ForgotPasswordService(UserService userService, Executor executor,
                          com.github.benmanes.caffeine.cache.Ticker ticker) {
        this.userService = userService;
        this.executor = executor;
        this.perEmail = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofHours(1))
                .maximumSize(MAX_TRACKED)
                .ticker(ticker)
                .build();
    }

    /** For the bound's test. */
    long trackedAddresses() {
        perEmail.cleanUp();
        return perEmail.estimatedSize();
    }

    /** Returns at once and never says what happened. */
    public void request(String rawEmail) {
        if (rawEmail == null) return;
        String email = rawEmail.trim().toLowerCase();
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) return;
        Bucket bucket = perEmail.get(email, k -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(perEmailPerHour)
                        .refillGreedy(perEmailPerHour, Duration.ofHours(1)).build())
                .build());
        if (!bucket.tryConsume(1)) {
            AUDIT.info("forgot-password throttled-per-email");
            return;
        }
        executor.execute(() -> {
            try {
                userService.sendForgottenPasswordLink(email);
            } catch (RuntimeException e) {
                LoggerFactory.getLogger(ForgotPasswordService.class)
                        .warn("forgot-password failed: {}", e.getClass().getSimpleName());
            }
        });
    }
}
