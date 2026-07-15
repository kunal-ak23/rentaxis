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
 *
 * <p><b>Calling contract.</b> Implementations are invoked by
 * {@link OtpDeliveryListener}, after the issuing transaction has committed and
 * on a pool thread — never inside {@code OtpLoginService.requestOtp}'s
 * transaction. Implementations may therefore block on the network and may throw;
 * neither can roll back the issued OTP row or delay the response. Do not call
 * {@link #send} from inside a transaction: doing so reintroduces both defects
 * that listener exists to prevent.
 */
public interface OtpSender {

    /**
     * Delivers {@code code} to {@code phoneNumber}.
     *
     * <p>May throw. The caller logs the failure and drops it — the user's
     * recovery is to request a new code.
     *
     * @param phoneNumber E.164 normalized recipient, e.g. {@code +971501234567}
     * @param code        the plaintext 6-digit code
     */
    void send(String phoneNumber, String code);
}
