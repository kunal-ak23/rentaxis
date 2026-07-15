package com.datagami.rentaxis.core.service.otp;

import com.azure.communication.messages.NotificationMessagesClient;
import com.azure.communication.messages.NotificationMessagesClientBuilder;
import com.azure.communication.messages.models.MessageTemplate;
import com.azure.communication.messages.models.MessageTemplateQuickAction;
import com.azure.communication.messages.models.MessageTemplateText;
import com.azure.communication.messages.models.MessageTemplateValue;
import com.azure.communication.messages.models.TemplateNotificationContent;
import com.azure.communication.messages.models.channels.WhatsAppMessageButtonSubType;
import com.azure.communication.messages.models.channels.WhatsAppMessageTemplateBindings;
import com.azure.communication.messages.models.channels.WhatsAppMessageTemplateBindingsButton;
import com.azure.communication.messages.models.channels.WhatsAppMessageTemplateBindingsComponent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Delivers login OTPs over WhatsApp via Azure Communication Services.
 *
 * <p>Selected by {@code gatepass.otp.channel=whatsapp}; the default ({@code log})
 * selects {@link LoggingOtpSender} instead. Uses the same ACS resource — and the
 * same {@code AZURE_COMMUNICATION_CONNECTION_STRING} — as
 * {@link com.datagami.rentaxis.core.email.send.AzureAcsEmailSender}, so there is
 * one credential to rotate, not two.
 *
 * <h2>Never call this on the request thread</h2>
 * {@link #send} makes a synchronous HTTP call to ACS. It is invoked from
 * {@link OtpDeliveryListener} after the issuing transaction has committed, and
 * must stay there: called inline it would hold a DB connection across the
 * round-trip and, worse, roll the {@code login_otps} row back on failure —
 * disabling the issuance throttle, which counts rows. That listener is also what
 * keeps this off the response path, so ACS latency never becomes a timing oracle.
 *
 * <h2>The code must not reach the logs</h2>
 * The OTP is a live credential. Nothing here logs it, and failures are logged
 * with the phone number and the ACS error only — never the code, and never the
 * built {@link TemplateNotificationContent}, whose {@code toString}/serialized
 * form contains it. {@link #send} throws on failure; the caller logs and drops.
 * Do not add the code to an exception message: those propagate to
 * {@code AsyncUncaughtExceptionHandler} and get logged with a stack trace.
 *
 * <h2>Template contract</h2>
 * Requires a Meta-approved WhatsApp <b>authentication</b> template — by default
 * named {@code gatepass_otp} ({@code GATEPASS_OTP_TEMPLATE_NAME}) — on the
 * channel identified by {@code ACS_WHATSAPP_CHANNEL_ID}. Authentication
 * templates bind the code twice: once into the body and once into the
 * copy-code/URL button. Both bindings reference the same parameter name
 * ({@value #PARAM}), which is what ties the {@link MessageTemplateValue}s to the
 * template's components. A mismatch between these names and the approved
 * template is rejected by ACS at send time, not at startup.
 *
 * <p>The template's language tag is configurable
 * ({@code GATEPASS_OTP_TEMPLATE_LANGUAGE}, default {@code en}) rather than
 * hardcoded, because Meta's template editor emits {@code en} for "English" but
 * {@code en_US} for "English (US)" — and templates are language-scoped, so
 * picking the wrong one is a send-time rejection with no startup signal. Making
 * it config means an {@code en_US} approval is an env change, not a redeploy.
 * See {@code docs/runbooks/whatsapp-otp-setup.md}.
 */
@Component
@ConditionalOnProperty(name = "gatepass.otp.channel", havingValue = "whatsapp")
@Slf4j
public class AcsWhatsAppOtpSender implements OtpSender {

    /**
     * Parameter name shared by the body value, the button value, and both
     * bindings. Arbitrary, but it must match on all four or ACS rejects the send.
     */
    private static final String PARAM = "otp";

    private final String connectionString;
    private final String channelRegistrationId;
    private final String templateName;
    private final String templateLanguage;

    private NotificationMessagesClient client;

    @Autowired
    public AcsWhatsAppOtpSender(
            @Value("${AZURE_COMMUNICATION_CONNECTION_STRING:}") String connectionString,
            @Value("${gatepass.otp.whatsapp-channel-id:}") String channelRegistrationId,
            @Value("${gatepass.otp.template-name:gatepass_otp}") String templateName,
            @Value("${gatepass.otp.template-language:en}") String templateLanguage) {
        this.connectionString = connectionString;
        this.channelRegistrationId = channelRegistrationId;
        this.templateName = templateName;
        this.templateLanguage = templateLanguage;
    }

    /**
     * Test seam. {@link NotificationMessagesClient} is final with a
     * package-private constructor and is only obtainable from a builder that
     * demands a real connection string, so this constructor is the only way to
     * exercise {@link #send} without standing up Azure.
     */
    AcsWhatsAppOtpSender(NotificationMessagesClient client, String channelRegistrationId,
                         String templateName, String templateLanguage) {
        this.connectionString = null;
        this.channelRegistrationId = channelRegistrationId;
        this.templateName = templateName;
        this.templateLanguage = templateLanguage;
        this.client = client;
    }

    /**
     * Validates config at startup. The two settings are treated differently on
     * purpose — the asymmetry is the whole point of this method.
     *
     * <h3>A blank channel id throws</h3>
     * There is no configuration in which this bean exists and a blank channel id
     * is survivable. It is {@code @ConditionalOnProperty(havingValue = "whatsapp")}
     * -gated, so it only exists because a deploy explicitly asked for WhatsApp
     * OTPs, and throwing here cannot reach any other feature. Warning instead
     * produced the worst available outcome: {@code deploy.yml} and
     * {@code docker-compose.prod.yml} both default the channel to {@code whatsapp}
     * (so {@code application.yml}'s {@code :log} fallback — and with it
     * {@link LoggingOtpSender}'s {@code @Profile("!prod")} guard — is dead through
     * the deploy path), while an unset {@code ACS_WHATSAPP_CHANNEL_ID} secret
     * renders empty. The context booted, every {@code POST /api/auth/otp/request}
     * answered 200 and committed a {@code login_otps} row, and the send threw on a
     * pool thread into {@link OtpDeliveryListener}'s catch — one WARN. No guard
     * could log in, the API reported success, and because delivery is correctly
     * after-commit, each retry burned an issuance slot: a guard tapping resend was
     * throttled out having never seen a code. A dead feature that reports success
     * is worse than a deploy that stops.
     *
     * <h3>A blank connection string only warns</h3>
     * That credential is <b>shared</b> with
     * {@link com.datagami.rentaxis.core.email.send.AzureAcsEmailSender}, so the
     * "degrade, don't take down RentAxis" argument genuinely applies to it and the
     * behaviour deliberately mirrors that sender's. Sends then fail in
     * {@link #send}, which is why its null-client check stays.
     */
    @PostConstruct
    void init() {
        if (channelRegistrationId == null || channelRegistrationId.isBlank()) {
            throw new IllegalStateException(
                    "gatepass.otp.whatsapp-channel-id (ACS_WHATSAPP_CHANNEL_ID) is not set, but "
                            + "gatepass.otp.channel=whatsapp. Set the channel registration id from the "
                            + "ACS resource's Channels tab - see docs/runbooks/whatsapp-otp-setup.md. "
                            + "Refusing to start: without it no guard can log in, yet every OTP request "
                            + "would still answer 200.");
        }
        if (connectionString == null || connectionString.isBlank()) {
            log.warn("AZURE_COMMUNICATION_CONNECTION_STRING not set - WhatsApp OTPs will fail at send time");
            return;
        }
        client = new NotificationMessagesClientBuilder().connectionString(connectionString).buildClient();
    }

    @Override
    public void send(String phoneNumber, String code) {
        if (client == null) {
            throw new IllegalStateException("Azure ACS messaging not configured");
        }
        if (channelRegistrationId == null || channelRegistrationId.isBlank()) {
            throw new IllegalStateException("ACS_WHATSAPP_CHANNEL_ID not set");
        }

        MessageTemplate template = new MessageTemplate(templateName, templateLanguage)
                .setValues(List.of(
                        new MessageTemplateText(PARAM, code),
                        // The copy-code button carries the code as its payload; ACS maps
                        // this onto the URL button's parameter for authentication templates.
                        new MessageTemplateQuickAction(PARAM).setPayload(code)))
                .setBindings(new WhatsAppMessageTemplateBindings()
                        .setBody(List.of(new WhatsAppMessageTemplateBindingsComponent(PARAM)))
                        .setButtons(List.of(new WhatsAppMessageTemplateBindingsButton(
                                WhatsAppMessageButtonSubType.URL, PARAM))));

        try {
            client.send(new TemplateNotificationContent(channelRegistrationId, List.of(phoneNumber), template));
            log.info("otp.whatsapp.sent phone={} template={}", phoneNumber, templateName);
        } catch (Exception e) {
            // Phone + error only. e.getMessage() is the ACS error, which does not
            // echo the template values back; do not widen this to log the request.
            // Language is included because a name/language mismatch against the
            // approved template (en vs en_US) is the likeliest cause and is
            // otherwise invisible.
            log.warn("otp.whatsapp.send_failed phone={} template={} lang={} error={}",
                    phoneNumber, templateName, templateLanguage, e.getMessage());
            throw new IllegalStateException("WhatsApp OTP delivery failed for " + phoneNumber, e);
        }
    }
}
