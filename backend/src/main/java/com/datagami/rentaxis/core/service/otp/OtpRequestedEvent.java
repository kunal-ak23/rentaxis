package com.datagami.rentaxis.core.service.otp;

/**
 * Published by {@link OtpLoginService#requestOtp} once a code has been issued,
 * and consumed by {@link OtpDeliveryListener} after the transaction commits.
 *
 * <p><b>This carries a live credential in plaintext.</b> It exists only in
 * memory, only between the commit and the send, and is never persisted or
 * serialized — that is the whole reason delivery is an event rather than an
 * outbox row (see {@link OtpDeliveryListener}). Do not add it to a durable
 * queue, do not log it, and do not give this type a {@code toString} that
 * renders {@link #code()} — the record's generated one already does, so keep
 * it out of log statements.
 *
 * @param phoneNumber E.164 normalized recipient
 * @param code        the plaintext 6-digit code
 */
record OtpRequestedEvent(String phoneNumber, String code) {
}
