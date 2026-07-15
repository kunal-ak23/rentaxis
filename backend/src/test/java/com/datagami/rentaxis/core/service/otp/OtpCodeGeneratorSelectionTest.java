package com.datagami.rentaxis.core.service.otp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring tests for the {@code gatepass.otp.dev-fixed-code} switch.
 *
 * <p>Mirrors {@link OtpSenderSelectionTest}'s {@link ApplicationContextRunner}
 * style for the same reason: the load-bearing assertion is about what happens
 * under the <b>prod</b> profile, and booting the real app under that profile
 * would drag in prod datasource and ACS config. Registering just the two
 * generators keeps each assertion about bean selection and nothing else.
 */
class OtpCodeGeneratorSelectionTest {

    private static final String DEV_CODE = "123456";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SecureRandomOtpCodeGenerator.class, FixedOtpCodeGenerator.class,
                    FixedOtpCodeGenerator.IgnoredInProdWarning.class);

    @Test
    void devWithAFixedCodeSetReturnsThatCode() {
        runner.withPropertyValues("gatepass.otp.dev-fixed-code=" + DEV_CODE)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(FixedOtpCodeGenerator.class);
                    // @Primary, so this is what OtpLoginService gets injected.
                    assertThat(ctx.getBean(OtpCodeGenerator.class).generate()).isEqualTo(DEV_CODE);
                });
    }

    /**
     * The SecureRandom bean is unconditional and stays in the context even when
     * the fixed one displaces it — that is what makes {@code @Primary} the right
     * mechanism here rather than a conditional on the SecureRandom bean, and it
     * is why the prod case below has something to fall back to.
     */
    @Test
    void theSecureRandomGeneratorIsNeverConditionalAwayByTheFixedOne() {
        runner.withPropertyValues("gatepass.otp.dev-fixed-code=" + DEV_CODE)
                .run(ctx -> assertThat(ctx).hasSingleBean(SecureRandomOtpCodeGenerator.class));
    }

    @Test
    void devWithoutAFixedCodeGetsSecureRandom() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(FixedOtpCodeGenerator.class);
            assertThat(ctx).hasSingleBean(SecureRandomOtpCodeGenerator.class);
            // Resolvable without ambiguity, and actually random.
            OtpCodeGenerator generator = ctx.getBean(OtpCodeGenerator.class);
            assertThat(generator).isInstanceOf(SecureRandomOtpCodeGenerator.class);
            assertThat(generator.generate()).matches("\\d{6}");
        });
    }

    /**
     * <b>The security test.</b> A fixed OTP in prod pins every guard's live login
     * credential to a known constant — anyone who can read the deploy config can
     * then log in as any guard. The {@code @Profile("!prod")} on
     * {@link FixedOtpCodeGenerator} is the only thing preventing that, so this
     * asserts the property is powerless under prod rather than merely unset.
     *
     * <p>Reverting that annotation must turn this red: the bean would load, and
     * {@code @Primary} would hand it to {@code OtpLoginService}.
     */
    @Test
    void prodNeverLoadsTheFixedGeneratorEvenWithThePropertySet() {
        runner.withPropertyValues("spring.profiles.active=prod",
                        "gatepass.otp.dev-fixed-code=" + DEV_CODE)
                .run(ctx -> {
                    assertThat(ctx)
                            .as("a fixed OTP code must be impossible under the prod profile")
                            .hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(FixedOtpCodeGenerator.class);

                    // The property is ignored, not honoured: prod gets real random codes.
                    OtpCodeGenerator generator = ctx.getBean(OtpCodeGenerator.class);
                    assertThat(generator).isInstanceOf(SecureRandomOtpCodeGenerator.class);
                    assertThat(generator.generate())
                            .as("prod must not issue the configured dev code")
                            .isNotEqualTo(DEV_CODE)
                            .matches("\\d{6}");

                    // ...and the operator is told, rather than left to guess which
                    // reading of the silence is right.
                    assertThat(ctx).hasSingleBean(FixedOtpCodeGenerator.IgnoredInProdWarning.class);
                });
    }

    /**
     * Fail-safe, not fail-loud — and deliberately the opposite of
     * {@code OtpSenderSelectionTest.prodWithoutARealChannelFailsFastInsteadOfLoggingCodes}.
     * Ignoring this property leaves prod correct (random codes), so taking the
     * deploy down over it would trade a working prod for a config typo. See the
     * asymmetry note on {@link FixedOtpCodeGenerator}.
     */
    @Test
    void prodStartsNormallyDespiteTheIgnoredProperty() {
        runner.withPropertyValues("spring.profiles.active=prod",
                        "gatepass.otp.dev-fixed-code=" + DEV_CODE)
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void aNonSixDigitDevCodeFailsStartup() {
        for (String bad : new String[] {"12345", "1234567", "abcdef", "12 34 56", "12345a"}) {
            runner.withPropertyValues("gatepass.otp.dev-fixed-code=" + bad)
                    .run(ctx -> assertThat(ctx)
                            .as("a dev code of '%s' must stop the context, not fail verification later", bad)
                            .hasFailed()
                            .getFailure()
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("GATEPASS_OTP_DEV_FIXED_CODE"));
        }
    }

    /**
     * Pins the reason {@code application.yml} documents this knob in a comment
     * instead of carrying {@code dev-fixed-code: ${GATEPASS_OTP_DEV_FIXED_CODE:}}
     * as a live key.
     *
     * <p>That placeholder resolves to an <b>empty string</b> when the variable is
     * unset, and an empty value is still a <i>present</i> property to
     * {@code @ConditionalOnProperty} — it only excludes the literal {@code false}.
     * So the live-key form would load this bean on every dev boot with
     * {@code code=""}, and the 6-digit validation would then fail the context for
     * every developer who never asked for the feature. This asserts the blank
     * property really does activate the bean, which is what makes the comment-only
     * form load-bearing rather than stylistic.
     */
    @Test
    void aBlankDevCodeStillActivatesTheBeanWhichIsWhyTheYamlKeyIsCommentedOut() {
        runner.withPropertyValues("gatepass.otp.dev-fixed-code=")
                .run(ctx -> assertThat(ctx)
                        .as("blank must not be silently treated as 'feature off' — see application.yml")
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class));
    }
}
