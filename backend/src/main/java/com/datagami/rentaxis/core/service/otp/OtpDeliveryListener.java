package com.datagami.rentaxis.core.service.otp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Delivers issued login OTPs <em>after</em> {@link OtpLoginService#requestOtp}'s
 * transaction commits, and off the request thread.
 *
 * <h2>Why this exists</h2>
 * Delivery used to run inline inside {@code requestOtp}'s {@code @Transactional}.
 * That was harmless only because the sole implementation was
 * {@link LoggingOtpSender}, which makes no network call and cannot throw. With a
 * real sender ({@link AcsWhatsAppOtpSender}) it would be two live defects:
 *
 * <ol>
 *   <li>a pooled DB connection held open across an HTTP round-trip to Meta; and</li>
 *   <li><b>the serious one</b> — a throwing send would roll back the transaction
 *       and take the {@code login_otps} row with it. The request throttle counts
 *       <em>rows</em> ({@code countByPhoneNumberAndCreatedAtAfter}), so a sender
 *       that is failing — exactly when an attacker is hammering the endpoint, or
 *       when ACS is down — would leave zero rows behind and thereby switch the
 *       issuance throttle off completely. A broken dependency must not silently
 *       disable a control.</li>
 * </ol>
 *
 * <h2>Why an event, not an outbox</h2>
 * {@code core/email/} is the in-repo precedent for durable delivery
 * (event → {@code EmailOutbox} row → {@link
 * com.datagami.rentaxis.core.email.outbox.EmailOutboxWorker} with retries), and
 * it is deliberately <b>not</b> followed here. Two reasons, in order:
 *
 * <ul>
 *   <li><b>An outbox row would persist the plaintext code.</b> The outbox stores
 *       a rendered message body; for an OTP that body <em>is</em> the credential.
 *       That directly contradicts the "codes are bcrypt-hashed at rest" guarantee
 *       on {@link OtpLoginService} and would turn {@code email_outbox} — a table
 *       with an admin read API over it ({@code EmailOutboxAdminController}) —
 *       into a store of live guard credentials. Hashing is not an option: the
 *       sender needs the plaintext.</li>
 *   <li><b>Durable retry is the wrong shape for a 5-minute secret.</b> The worker
 *       ticks every 30s and retries on failure; a retry landing after
 *       {@code CODE_TTL_MINUTES} delivers a code that can no longer be used. The
 *       user-visible recovery for a lost OTP already exists and is better: tap
 *       "resend" and get a fresh code. That is not true of an email, which is why
 *       email earns an outbox and this does not.</li>
 * </ul>
 *
 * <h2>Tradeoff accepted</h2>
 * Delivery now happens after the row is committed, so a send failure means the
 * user has <b>burned a throttle slot and received no code</b> — up to
 * {@code MAX_REQUESTS_PER_WINDOW} times, after which they are throttled out for
 * the rest of the window despite never having seen a code. That is the correct
 * direction to fail: throttle integrity is a security control, delivery is a
 * convenience, and the alternative (rolling the row back) trades a real control
 * away for it. Send failures are logged at WARN with the phone number for exactly
 * this reason — a guard reporting "no code arrives" is diagnosable from the logs.
 *
 * <p>An in-memory event is also lossy in one more way: a JVM crash in the window
 * between commit and send drops the code silently. Same recovery — resend.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OtpDeliveryListener {

    private final OtpSender sender;

    /**
     * {@link TransactionPhase#AFTER_COMMIT} guarantees the {@code login_otps} row
     * is durable before any send is attempted; {@link Async} takes the send off
     * the request thread so it contributes nothing to response latency (see the
     * timing-oracle note on {@link OtpLoginService}).
     *
     * <p>Both are load-bearing. Dropping {@code @Async} keeps the row safe but
     * puts a network round-trip back on the request path, re-widening the timing
     * difference between a known and an unknown phone to something trivially
     * measurable. Dropping {@code AFTER_COMMIT} for a plain {@code @EventListener}
     * would fire the send before commit and reintroduce the rollback defect this
     * class exists to prevent.
     *
     * <p>Nothing is rethrown: this runs on a pool thread after the response is
     * already on its way, so there is no caller left to handle it, and letting it
     * escape only reaches {@code AsyncUncaughtExceptionHandler}. The code is never
     * logged — see {@link AcsWhatsAppOtpSender} for the same rule at the boundary.
     */
    @Async("otpExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOtpRequested(OtpRequestedEvent event) {
        try {
            sender.send(event.phoneNumber(), event.code());
        } catch (Exception e) {
            // The OTP row is already committed, so the throttle holds regardless.
            log.warn("otp.delivery_failed phone={} error={}", event.phoneNumber(), e.getMessage());
        }
    }
}
