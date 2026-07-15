package com.datagami.rentaxis.core.service.otp;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * The real {@link OtpCodeGenerator}: a uniformly random 6-digit code from
 * {@link SecureRandom}. This is what runs in prod, and — unless
 * {@code gatepass.otp.dev-fixed-code} is set — in dev too.
 *
 * <p>Carries no {@code @Profile} and no {@code @ConditionalOnProperty}: it must
 * exist in every context, because it is the fallback that
 * {@link FixedOtpCodeGenerator} displaces via {@code @Primary} rather than
 * replaces. That is what lets the fixed generator be absent under {@code prod}
 * without leaving {@code OtpLoginService} with nothing to inject.
 *
 * <p>{@link SecureRandom#nextInt(int)} rather than {@code Math.random()} or a
 * plain {@link java.util.Random}: the code is a login credential, and a
 * predictable PRNG stream would let an attacker who observes one code compute
 * the next. The bound of {@code 1_000_000} with {@code %06d} formatting keeps the
 * distribution uniform across the full {@code 000000}-{@code 999999} space —
 * note the leading zeros are significant, so the value is a String throughout and
 * must never be round-tripped through an int.
 */
@Component
public class SecureRandomOtpCodeGenerator implements OtpCodeGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String generate() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
