package com.datagami.rentaxis.core.service.otp;

/**
 * Source of the 6-digit login OTP code.
 *
 * <p>Exists to make the code itself substitutable in development, the same way
 * {@link OtpSender} makes delivery substitutable. {@link SecureRandomOtpCodeGenerator}
 * is the default and the only implementation that ever runs in prod;
 * {@link FixedOtpCodeGenerator} returns a known code so the guard app can be
 * exercised locally without reading backend logs for every login.
 *
 * <p><b>Implementations must return exactly 6 digits.</b> {@code OtpLoginService}
 * hashes whatever comes back and the client's verify compares against it, so a
 * shorter or non-numeric value is not rejected anywhere — it simply produces a
 * code that cannot be typed into a 6-box entry field, failing verification with
 * the module's deliberately uninformative "invalid or expired code". Both
 * implementations therefore validate their own output, and
 * {@link FixedOtpCodeGenerator} does it at startup rather than at first login.
 */
public interface OtpCodeGenerator {

    /**
     * Returns a fresh plaintext code.
     *
     * <p>Called inside {@code OtpLoginService.requestOtp}'s transaction — must
     * not block on the network. The returned value is a live credential: never
     * log it outside a dev-only implementation.
     */
    String generate();
}
