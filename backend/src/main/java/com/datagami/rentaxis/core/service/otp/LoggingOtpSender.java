package com.datagami.rentaxis.core.service.otp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Development / default {@link OtpSender}: writes the code to the log instead
 * of delivering it, so the guard app can be exercised locally without an SMS
 * or WhatsApp provider configured.
 *
 * <p>Active when {@code gatepass.otp.channel} is {@code log} or unset — but
 * never under the {@code prod} profile. That exclusion is deliberate and
 * matters: {@code matchIfMissing = true} means an unset property selects this
 * sender, and the property is currently set in no environment file. Without
 * {@code @Profile("!prod")}, the moment a real sender exists prod would
 * silently keep this one — printing every guard's live credential into the
 * logs (anyone with log access could then log in as any guard) while sending
 * no message at all. Failing to start is the strictly better outcome.
 *
 * <p>So in prod the context refuses to start unless {@code gatepass.otp.channel}
 * explicitly names a real channel: no {@link OtpSender} bean exists, and
 * {@code OtpLoginService}'s constructor injection fails loudly at startup.
 * The prod profile string is {@code prod} (see {@code infra/envs/prod.env.template}
 * and {@code .github/workflows/deploy.yml}).
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(name = "gatepass.otp.channel", havingValue = "log", matchIfMissing = true)
@Slf4j
public class LoggingOtpSender implements OtpSender {

    @Override
    public void send(String phoneNumber, String code) {
        log.warn("DEV OTP for {}: {}", phoneNumber, code);
    }
}
