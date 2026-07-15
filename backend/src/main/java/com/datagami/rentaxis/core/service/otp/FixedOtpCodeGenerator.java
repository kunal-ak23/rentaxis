package com.datagami.rentaxis.core.service.otp;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Development-only {@link OtpCodeGenerator}: every guard's login code is the
 * configured constant.
 *
 * <p>Exists because WhatsApp delivery is not live yet (Meta Business
 * Verification pending), so a guard logging in locally has no channel to receive
 * a real code on. {@link LoggingOtpSender} covers that by printing the code, but
 * it means reading backend logs on every login while holding the phone the app
 * runs on. Pinning the code removes the log round-trip entirely.
 *
 * <p>Active only when {@code gatepass.otp.dev-fixed-code} is set <b>and</b> the
 * profile is not {@code prod}. {@code @Primary} means it displaces
 * {@link SecureRandomOtpCodeGenerator} rather than replacing it — the SecureRandom
 * bean is unconditional, so when this one is absent injection still resolves.
 * That is exactly what makes the prod guard below safe.
 *
 * <h2>Why this fails SAFE while the channel id fails LOUD</h2>
 * These two decisions look inconsistent and are not. Read this before "fixing"
 * either one to match the other.
 *
 * <p>Set {@code GATEPASS_OTP_DEV_FIXED_CODE} in prod and nothing breaks: the
 * {@code @Profile("!prod")} means this bean is never created, the unconditional
 * {@link SecureRandomOtpCodeGenerator} supplies real codes, and the property is
 * silently ignored (with a WARN — see {@link IgnoredInProdWarning}). Prod stays
 * <b>secure and up</b>.
 *
 * <p>{@link AcsWhatsAppOtpSender} does the opposite: a blank
 * {@code ACS_WHATSAPP_CHANNEL_ID} refuses to start the context. The difference is
 * what the two alternatives cost, not a difference of taste:
 *
 * <ul>
 *   <li><b>Here, ignoring the property is a correct, complete outcome.</b> Prod
 *       wants random codes; falling back to random codes <i>is</i> what prod
 *       wants. There is no broken state left behind and nothing for an operator
 *       to fix — the only cost is a confusing env var, which the WARN addresses.
 *       Failing to start instead would take a working deploy down over a setting
 *       whose absence changes nothing. Note the failure this <em>prevents</em> is
 *       the severe one: were the guard removed, a stray env var would pin every
 *       guard's live credential in prod to a known constant — a total
 *       authentication bypass for anyone who can read the deploy config.</li>
 *   <li><b>There, ignoring the setting leaves a silently-broken login.</b> The
 *       context boots, every OTP request answers 200 and commits a row, and the
 *       send throws on a pool thread. No guard can log in, the API reports
 *       success, and each retry burns an issuance slot. Nothing surfaces that to
 *       an operator, so refusing to start is the only signal that reaches one.</li>
 * </ul>
 *
 * <p>The rule that generates both, and the one to apply to the next such
 * decision: <b>fail loud when the alternative is a silent lie; fail safe when the
 * alternative is a correct fallback.</b> Neither is a house style to be applied
 * uniformly.
 */
@Component
@Primary
@Profile("!prod")
@ConditionalOnProperty(name = "gatepass.otp.dev-fixed-code")
@Slf4j
public class FixedOtpCodeGenerator implements OtpCodeGenerator {

    /**
     * Exactly what {@link SecureRandomOtpCodeGenerator} produces. Anything else
     * is unusable: the code has to be typed into a 6-box entry field, and a
     * mismatched length fails verification behind the module's constant "invalid
     * or expired code" — which is designed to tell an attacker nothing and would
     * therefore tell the developer nothing either.
     */
    private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");

    private final String code;

    public FixedOtpCodeGenerator(@Value("${gatepass.otp.dev-fixed-code:}") String code) {
        this.code = code;
    }

    /**
     * Fails the context on a malformed dev code, rather than letting it surface
     * as an unexplainable failed login five minutes later.
     *
     * <p>Safe to throw despite the fail-safe posture documented on the class:
     * this bean only exists when a developer explicitly set the property on a
     * non-prod profile, so the blast radius is the developer who just typed it.
     * The prod case never reaches here — the bean is not created at all.
     */
    @PostConstruct
    void init() {
        if (!SIX_DIGITS.matcher(code == null ? "" : code).matches()) {
            throw new IllegalStateException(
                    "gatepass.otp.dev-fixed-code (GATEPASS_OTP_DEV_FIXED_CODE) must be exactly 6 digits, "
                            + "but was '" + code + "'. Guard login codes are 6 digits; a shorter or "
                            + "non-numeric value cannot be entered in the app and would fail verification "
                            + "with a generic 'invalid or expired code'.");
        }
        log.warn("""
                ================================================================
                FIXED DEV OTP CODE IS ACTIVE (gatepass.otp.dev-fixed-code).
                All OTPs are fixed to a known value — every security guard can be
                logged into with one constant. This is a development convenience
                and must never be used outside development.
                ================================================================""");
    }

    @Override
    public String generate() {
        return code;
    }

    /**
     * The prod half of the guard: says out loud that the property was ignored.
     *
     * <p>{@link FixedOtpCodeGenerator} cannot warn about being disabled in prod —
     * under that profile it does not exist, which is the entire point. So the
     * warning needs its own bean with the mirrored condition:
     * {@code @Profile("prod")} and the same property. It deliberately holds no
     * behaviour beyond the log line.
     *
     * <p>Without this, an operator who set {@code GATEPASS_OTP_DEV_FIXED_CODE} in
     * prod gets total silence and two readings of it, both wrong: that the fixed
     * code is in effect (it is not — codes are random), or that the var does
     * something else. The failure being safe does not make it worth hiding; a
     * setting that silently does nothing is a config bug to be fixed, and the
     * operator can only fix what they can see. Lives here rather than in its own
     * file so that removing the profile guard and removing its warning are one
     * diff, not two.
     */
    @Component
    @Profile("prod")
    @ConditionalOnProperty(name = "gatepass.otp.dev-fixed-code")
    @Slf4j
    public static class IgnoredInProdWarning {

        @PostConstruct
        void warn() {
            log.warn("gatepass.otp.dev-fixed-code (GATEPASS_OTP_DEV_FIXED_CODE) is set, but this is the "
                    + "prod profile — the setting is IGNORED and OTP codes are randomly generated as "
                    + "normal. The fixed-code generator is @Profile(\"!prod\") by design and cannot be "
                    + "enabled here. Unset the variable to remove this warning.");
        }
    }
}
