package com.datagami.rentaxis.core.service.otp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring tests for the {@code gatepass.otp.channel} switch.
 *
 * <p>Uses {@link ApplicationContextRunner} rather than {@code @SpringBootTest}
 * deliberately: this needs to prove what happens under the <b>prod</b> profile,
 * and booting the real app under that profile would drag in prod datasource and
 * ACS config. Registering just the two senders keeps the assertion about bean
 * selection and nothing else.
 *
 * <p>No Azure credentials are needed even for the whatsapp case: the ACS sender
 * builds its client in {@code @PostConstruct} and only warns when the connection
 * string is blank (mirroring {@code AzureAcsEmailSender}), so the bean starts and
 * fails at send time instead. That is what lets this test select the real sender
 * without a dummy connection string or a mock.
 */
class OtpSenderSelectionTest {

    /**
     * Mirrors how {@code OtpLoginService} consumes the sender — constructor
     * injection of a required {@link OtpSender}. That is what turns "no sender
     * bean" into "the context refuses to start", so the prod guard has to be
     * asserted through a consumer like this, not just by counting beans.
     */
    @Configuration
    static class RequiresAnOtpSender {
        @Bean
        String otpSenderConsumer(OtpSender sender) {
            return sender.getClass().getSimpleName();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(LoggingOtpSender.class, AcsWhatsAppOtpSender.class);

    @Test
    void devWithoutAnExplicitChannelGetsTheLoggingSender() {
        runner.run(ctx -> assertThat(ctx)
                .hasSingleBean(LoggingOtpSender.class)
                .doesNotHaveBean(AcsWhatsAppOtpSender.class));
    }

    @Test
    void devWithChannelLogGetsTheLoggingSender() {
        // The real dev path: application.yml resolves the property to `log`, so it
        // is present rather than missing.
        runner.withPropertyValues("gatepass.otp.channel=log")
                .run(ctx -> assertThat(ctx)
                        .hasSingleBean(LoggingOtpSender.class)
                        .doesNotHaveBean(AcsWhatsAppOtpSender.class));
    }

    @Test
    void whatsappChannelSelectsTheAcsSenderWithoutContactingAzure() {
        runner.withPropertyValues("gatepass.otp.channel=whatsapp")
                .run(ctx -> assertThat(ctx)
                        .hasSingleBean(AcsWhatsAppOtpSender.class)
                        .doesNotHaveBean(LoggingOtpSender.class));
    }

    /**
     * Task 5's I3 fix. In prod, {@code application.yml} resolves an unset
     * {@code GATEPASS_OTP_CHANNEL} to {@code log} — and the logging sender is
     * {@code @Profile("!prod")}, so nothing supplies an {@link OtpSender} and the
     * context must die at startup. The failure mode this prevents is far worse
     * than downtime: prod would otherwise write every guard's live login code to
     * the log while sending no message.
     */
    @Test
    void prodWithoutARealChannelFailsFastInsteadOfLoggingCodes() {
        runner.withUserConfiguration(RequiresAnOtpSender.class)
                .withPropertyValues("spring.profiles.active=prod", "gatepass.otp.channel=log")
                .run(ctx -> assertThat(ctx)
                        .as("prod must not start without a real OTP channel")
                        .hasFailed());

        // Same outcome when the property is absent entirely (matchIfMissing).
        runner.withUserConfiguration(RequiresAnOtpSender.class)
                .withPropertyValues("spring.profiles.active=prod")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void prodWithWhatsappChannelStarts() {
        runner.withUserConfiguration(RequiresAnOtpSender.class)
                .withPropertyValues("spring.profiles.active=prod", "gatepass.otp.channel=whatsapp")
                .run((AssertableApplicationContext ctx) -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(AcsWhatsAppOtpSender.class);
                    assertThat(ctx).doesNotHaveBean(LoggingOtpSender.class);
                    assertThat(ctx.getBean("otpSenderConsumer")).isEqualTo("AcsWhatsAppOtpSender");
                });
    }
}
