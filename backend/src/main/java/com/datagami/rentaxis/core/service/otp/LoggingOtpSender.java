package com.datagami.rentaxis.core.service.otp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development / default {@link OtpSender}: writes the code to the log instead
 * of delivering it, so the guard app can be exercised locally without an SMS
 * or WhatsApp provider configured.
 *
 * <p>Active when {@code gatepass.otp.channel} is {@code log} or unset. This
 * logs a live credential at WARN by design — deployments that set the
 * property to {@code whatsapp} get the real sender instead and never reach
 * this class.
 */
@Component
@ConditionalOnProperty(name = "gatepass.otp.channel", havingValue = "log", matchIfMissing = true)
@Slf4j
public class LoggingOtpSender implements OtpSender {

    @Override
    public void send(String phoneNumber, String code) {
        log.warn("DEV OTP for {}: {}", phoneNumber, code);
    }
}
