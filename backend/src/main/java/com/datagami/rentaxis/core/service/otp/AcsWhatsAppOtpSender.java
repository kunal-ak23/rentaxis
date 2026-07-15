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

    /** WhatsApp authentication templates are language-scoped; the approved one is en. */
    private static final String LANGUAGE = "en";

    private final String connectionString;
    private final String channelRegistrationId;
    private final String templateName;

    private NotificationMessagesClient client;

    @Autowired
    public AcsWhatsAppOtpSender(
            @Value("${AZURE_COMMUNICATION_CONNECTION_STRING:}") String connectionString,
            @Value("${gatepass.otp.whatsapp-channel-id:}") String channelRegistrationId,
            @Value("${gatepass.otp.template-name:gatepass_otp}") String templateName) {
        this.connectionString = connectionString;
        this.channelRegistrationId = channelRegistrationId;
        this.templateName = templateName;
    }

    /**
     * Test seam. {@link NotificationMessagesClient} is final with a
     * package-private constructor and is only obtainable from a builder that
     * demands a real connection string, so this constructor is the only way to
     * exercise {@link #send} without standing up Azure.
     */
    AcsWhatsAppOtpSender(NotificationMessagesClient client, String channelRegistrationId, String templateName) {
        this.connectionString = null;
        this.channelRegistrationId = channelRegistrationId;
        this.templateName = templateName;
        this.client = client;
    }

    /**
     * Builds the client if configured, and only warns if not — deliberately
     * mirroring {@code AzureAcsEmailSender}. Missing ACS config must not stop the
     * context: the gate pass module is one feature among many, and a deploy that
     * forgot one env var should degrade to "OTPs fail, logged loudly", not
     * "nobody can use RentAxis". Sends then fail fast in {@link #send}.
     *
     * <p>Note this is the opposite call from {@link LoggingOtpSender}'s
     * {@code @Profile("!prod")} guard, and both are right: an unconfigured real
     * sender fails visibly and delivers nothing, while a dev sender left on in
     * prod would silently print live credentials to the log and look healthy.
     */
    @PostConstruct
    void init() {
        if (connectionString == null || connectionString.isBlank()) {
            log.warn("AZURE_COMMUNICATION_CONNECTION_STRING not set - WhatsApp OTPs will fail at send time");
            return;
        }
        if (channelRegistrationId == null || channelRegistrationId.isBlank()) {
            log.warn("gatepass.otp.whatsapp-channel-id (ACS_WHATSAPP_CHANNEL_ID) not set "
                    + "- WhatsApp OTPs will fail at send time");
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

        MessageTemplate template = new MessageTemplate(templateName, LANGUAGE)
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
            log.warn("otp.whatsapp.send_failed phone={} template={} error={}",
                    phoneNumber, templateName, e.getMessage());
            throw new IllegalStateException("WhatsApp OTP delivery failed for " + phoneNumber, e);
        }
    }
}
