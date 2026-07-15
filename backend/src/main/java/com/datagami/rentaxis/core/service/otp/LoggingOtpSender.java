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
 * names a real channel: no {@link OtpSender} bean exists, and
 * {@link OtpDeliveryListener}'s constructor injection — the only required
 * consumer of {@link OtpSender}, since {@code OtpLoginService} now publishes an
 * event instead of sending — fails loudly at startup. Relaxing that injection to
 * {@code ObjectProvider} or {@code required = false} would silently disarm this
 * guard; {@code OtpSenderSelectionTest} registers the real listener so that
 * change goes red.
 *
 * <p>Note this is a backstop, not the guard that fires on the real deploy path:
 * {@code deploy.yml} and {@code docker-compose.prod.yml} both default the channel
 * to {@code whatsapp}, so prod never resolves to {@code log} to begin with. The
 * check that actually protects prod is {@link AcsWhatsAppOtpSender}'s startup
 * validation of the channel id. This one covers an operator explicitly setting
 * {@code GATEPASS_OTP_CHANNEL=log} in prod.
 *
 * <p>The prod profile string is {@code prod} (see {@code infra/envs/prod.env.template}
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
