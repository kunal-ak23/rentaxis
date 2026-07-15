package com.datagami.rentaxis.core.service.otp;

/**
 * Delivery channel for login OTP codes.
 *
 * <p>Implementations are selected by the {@code gatepass.otp.channel}
 * property: {@code log} (default, {@link LoggingOtpSender}) for local dev,
 * {@code whatsapp} for the ACS-backed sender.
 *
 * <p>Implementations receive the code in plaintext — it is never persisted
 * in that form — and must not log it outside of a dev-only channel.
 */
public interface OtpSender {

    /**
     * Delivers {@code code} to {@code phoneNumber}.
     *
     * @param phoneNumber E.164 normalized recipient, e.g. {@code +971501234567}
     * @param code        the plaintext 6-digit code
     */
    void send(String phoneNumber, String code);
}
