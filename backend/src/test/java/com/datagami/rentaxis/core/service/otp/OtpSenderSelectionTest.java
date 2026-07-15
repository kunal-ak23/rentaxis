package com.datagami.rentaxis.core.service.otp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
 * <p>The whatsapp cases supply dummy ACS values. They have to: the sender's
 * {@code @PostConstruct} throws on a blank channel id (that is the fail-fast this
 * class pins), and the client builder needs a parseable connection string. The
 * values below never leave the JVM — no client call is made, only construction.
 */
class OtpSenderSelectionTest {

    /**
     * Shape-valid but entirely fake. {@code NotificationMessagesClientBuilder}
     * parses the connection string at build time (and base64-decodes the key), so
     * it must look real; nothing here ever contacts the endpoint.
     */
    private static final String DUMMY_CONNECTION_STRING =
            "endpoint=https://localhost.communication.azure.com/;accesskey=bm90LWEtcmVhbC1hY2Nlc3Mta2V5";

    private static final String DUMMY_CHANNEL_ID = "00000000-0000-0000-0000-000000000000";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LoggingOtpSender.class, AcsWhatsAppOtpSender.class);

    /**
     * Registers the real consumer of {@link OtpSender} — {@link OtpDeliveryListener}
     * takes one by required constructor injection. That is what turns "no sender
     * bean" into "the context refuses to start", so the prod guard is asserted
     * through the actual injection point rather than a test-local stand-in: if
     * someone relaxes the listener to {@code ObjectProvider<OtpSender>} or
     * {@code required = false}, these tests go red, which is the point.
     */
    private ApplicationContextRunner withRealConsumer() {
        return runner.withUserConfiguration(OtpDeliveryListener.class);
    }

    private ApplicationContextRunner withAcsConfigured(ApplicationContextRunner r) {
        return r.withPropertyValues(
                "AZURE_COMMUNICATION_CONNECTION_STRING=" + DUMMY_CONNECTION_STRING,
                "gatepass.otp.whatsapp-channel-id=" + DUMMY_CHANNEL_ID);
    }

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
        withAcsConfigured(runner.withPropertyValues("gatepass.otp.channel=whatsapp"))
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
        withRealConsumer()
                .withPropertyValues("spring.profiles.active=prod", "gatepass.otp.channel=log")
                .run(ctx -> assertThat(ctx)
                        .as("prod must not start without a real OTP channel")
                        .hasFailed()
                        // Pinned to the cause, not just "it failed": the assertion has
                        // to distinguish "no OtpSender bean to inject" from the context
                        // dying for some incidental reason.
                        .getFailure()
                        .isInstanceOf(UnsatisfiedDependencyException.class)
                        .rootCause()
                        .isInstanceOf(NoSuchBeanDefinitionException.class));

        // Same outcome when the property is absent entirely (matchIfMissing).
        withRealConsumer()
                .withPropertyValues("spring.profiles.active=prod")
                .run(ctx -> assertThat(ctx)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(NoSuchBeanDefinitionException.class));
    }

    @Test
    void prodWithWhatsappChannelStarts() {
        withAcsConfigured(withRealConsumer())
                .withPropertyValues("spring.profiles.active=prod", "gatepass.otp.channel=whatsapp")
                .run((AssertableApplicationContext ctx) -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(AcsWhatsAppOtpSender.class);
                    assertThat(ctx).doesNotHaveBean(LoggingOtpSender.class);
                    assertThat(ctx).hasSingleBean(OtpDeliveryListener.class);
                });
    }

    /**
     * The whatsapp sender must not start without a channel id.
     *
     * <p>This is the deploy path, not a hypothetical: both {@code deploy.yml} and
     * {@code docker-compose.prod.yml} default the channel to {@code whatsapp}, so
     * the {@code :log} fallback never applies in prod and the guard above never
     * fires. If a blank {@code ACS_WHATSAPP_CHANNEL_ID} merely warned, prod would
     * boot, every {@code POST /api/auth/otp/request} would answer 200 and commit a
     * row, and the send would throw on a pool thread — no guard could ever log in
     * while the API reported success, and each attempt would burn an issuance slot.
     * Refusing to start is the only signal that reaches an operator.
     *
     * <p>Safe to throw here precisely because the bean is
     * {@code @ConditionalOnProperty(havingValue = "whatsapp")}-gated: it exists
     * only when someone asked for WhatsApp OTPs, so it cannot take down a deploy
     * that never wanted the feature.
     */
    @Test
    void whatsappChannelWithoutAChannelIdFailsFast() {
        runner.withPropertyValues(
                        "gatepass.otp.channel=whatsapp",
                        "AZURE_COMMUNICATION_CONNECTION_STRING=" + DUMMY_CONNECTION_STRING)
                .run(ctx -> assertThat(ctx)
                        .as("a blank ACS_WHATSAPP_CHANNEL_ID must stop the context, not warn")
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("ACS_WHATSAPP_CHANNEL_ID"));
    }
}
