package com.datagami.rentaxis.core.service.otp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.azure.communication.messages.NotificationMessagesClient;
import com.azure.communication.messages.models.MessageTemplate;
import com.azure.communication.messages.models.MessageTemplateQuickAction;
import com.azure.communication.messages.models.MessageTemplateText;
import com.azure.communication.messages.models.NotificationContent;
import com.azure.communication.messages.models.TemplateNotificationContent;
import com.azure.communication.messages.models.channels.WhatsAppMessageButtonSubType;
import com.azure.communication.messages.models.channels.WhatsAppMessageTemplateBindings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link AcsWhatsAppOtpSender}, against a mocked ACS client —
 * a real send costs money and needs a Meta-approved template, so the contract we
 * can actually pin here is the request we hand to the SDK.
 *
 * <p>{@link NotificationMessagesClient} is final; Mockito's inline mock maker
 * (the default since Mockito 5) handles that. The sender's package-private
 * constructor is the seam that lets the mock in — the public one only takes a
 * connection string and would build a real client.
 *
 * <p>The assertions worth keeping: the code must land in <em>both</em> the body
 * and the copy-code button (an authentication template renders neither without
 * the other), the bindings must reference the values by name, and the code must
 * never appear in a log line or an exception message.
 */
@ExtendWith(MockitoExtension.class)
class AcsWhatsAppOtpSenderTest {

    private static final String PHONE = "+971501234567";
    private static final String CODE = "428913";
    private static final String CHANNEL_ID = "8e1b0c1a-0000-4000-8000-abcdefabcdef";
    private static final String TEMPLATE = "gatepass_otp";

    @Mock NotificationMessagesClient client;

    private AcsWhatsAppOtpSender sender;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        sender = new AcsWhatsAppOtpSender(client, CHANNEL_ID, TEMPLATE);

        logs = new ListAppender<>();
        logs.start();
        logger = (Logger) LoggerFactory.getLogger(AcsWhatsAppOtpSender.class);
        logger.addAppender(logs);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private TemplateNotificationContent captureSent() {
        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(client).send(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(TemplateNotificationContent.class);
        return (TemplateNotificationContent) captor.getValue();
    }

    private String renderedLogs() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void sendsApprovedTemplateToTheRecipientOnTheConfiguredChannel() {
        sender.send(PHONE, CODE);

        TemplateNotificationContent sent = captureSent();
        assertThat(sent.getChannelRegistrationId()).isEqualTo(CHANNEL_ID);
        assertThat(sent.getTo()).containsExactly(PHONE);

        MessageTemplate template = sent.getTemplate();
        assertThat(template.getName()).isEqualTo(TEMPLATE);
        assertThat(template.getLanguage()).isEqualTo("en");
    }

    @Test
    void codeReachesBothTheBodyAndTheCopyCodeButton() {
        sender.send(PHONE, CODE);

        MessageTemplate template = captureSent().getTemplate();

        // Body component: the digits the guard reads.
        MessageTemplateText body = template.getValues().stream()
                .filter(MessageTemplateText.class::isInstance)
                .map(MessageTemplateText.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no body text value in the template"));
        assertThat(body.getText()).isEqualTo(CODE);

        // Button component: WhatsApp authentication templates are rejected by Meta
        // without it, and it is what makes "copy code" work.
        MessageTemplateQuickAction button = template.getValues().stream()
                .filter(MessageTemplateQuickAction.class::isInstance)
                .map(MessageTemplateQuickAction.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no quick-action button value in the template"));
        assertThat(button.getPayload()).isEqualTo(CODE);

        // Both must be bound under the same ref name as the values above, or ACS
        // cannot match them to the approved template's components.
        assertThat(template.getBindings()).isInstanceOf(WhatsAppMessageTemplateBindings.class);
        WhatsAppMessageTemplateBindings bindings = (WhatsAppMessageTemplateBindings) template.getBindings();

        assertThat(bindings.getBody()).hasSize(1);
        assertThat(bindings.getBody().getFirst().getRefValue()).isEqualTo(body.getRefValue());

        assertThat(bindings.getButtons()).hasSize(1);
        assertThat(bindings.getButtons().getFirst().getRefValue()).isEqualTo(button.getRefValue());
        assertThat(bindings.getButtons().getFirst().getSubType()).isEqualTo(WhatsAppMessageButtonSubType.URL);
    }

    @Test
    void successfulSendNeverLogsTheCode() {
        sender.send(PHONE, CODE);

        assertThat(renderedLogs())
                .as("the OTP is a live credential and must never reach the log")
                .doesNotContain(CODE);
        assertThat(renderedLogs()).contains(PHONE); // phone is fine, and needed to diagnose
    }

    @Test
    void acsFailureIsWrappedAndLoggedWithoutTheCode() {
        doThrow(new RuntimeException("ACS 400: template gatepass_otp not approved"))
                .when(client).send(any());

        assertThatThrownBy(() -> sender.send(PHONE, CODE))
                .isInstanceOf(IllegalStateException.class)
                // The message propagates to AsyncUncaughtExceptionHandler and gets
                // logged with a stack trace, so it must not carry the credential.
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(CODE))
                .satisfies(e -> assertThat(e.getCause().getMessage()).doesNotContain(CODE))
                .hasMessageContaining(PHONE)
                .hasRootCauseMessage("ACS 400: template gatepass_otp not approved");

        String rendered = renderedLogs();
        assertThat(rendered).doesNotContain(CODE);
        // A failure has to be diagnosable: who didn't get a code, and why.
        assertThat(rendered).contains(PHONE);
        assertThat(rendered).contains("not approved");
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN);
    }

    @Test
    void sendFailsFastWhenChannelIdIsMissing() {
        // Startup only warns about this (see init()), so the send is where an
        // unconfigured deploy has to surface — not silently succeed.
        AcsWhatsAppOtpSender unconfigured = new AcsWhatsAppOtpSender(client, "  ", TEMPLATE);

        assertThatThrownBy(() -> unconfigured.send(PHONE, CODE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACS_WHATSAPP_CHANNEL_ID");
    }
}
