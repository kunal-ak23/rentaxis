package com.datagami.rentaxis.core.email.send;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.communication.email.models.EmailAttachment;
import com.azure.communication.email.models.EmailMessage;
import com.azure.communication.email.models.EmailSendResult;
import com.datagami.rentaxis.core.email.outbox.EmailOutbox;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class AzureAcsEmailSender implements EmailSender {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${AZURE_COMMUNICATION_CONNECTION_STRING:}")
    private String connectionString;

    @Value("${AZURE_EMAIL_SENDER:}")
    private String sender;

    private EmailClient client;

    @PostConstruct
    void init() {
        if (connectionString == null || connectionString.isBlank()) {
            log.warn("AZURE_COMMUNICATION_CONNECTION_STRING not set - emails will fail at send time");
            return;
        }
        client = new EmailClientBuilder().connectionString(connectionString).buildClient();
    }

    @Override
    public SendResult send(EmailOutbox row) {
        if (client == null) throw new IllegalStateException("Azure ACS not configured");
        if (sender == null || sender.isBlank()) throw new IllegalStateException("AZURE_EMAIL_SENDER not set");

        EmailMessage message = new EmailMessage()
                .setSenderAddress(sender)
                .setToRecipients(row.getRecipientEmail())
                .setSubject(row.getSubject())
                .setBodyHtml(row.getBodyHtml())
                .setBodyPlainText(row.getBodyText() == null ? "" : row.getBodyText());

        List<EmailAttachment> attachments = parseAttachments(row.getAttachments());
        if (!attachments.isEmpty()) message.setAttachments(attachments);

        var poller = client.beginSend(message);
        EmailSendResult result = poller.waitForCompletion().getValue();
        return new SendResult(result.getId(), String.valueOf(result.getStatus()));
    }

    private List<EmailAttachment> parseAttachments(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, String>> parsed = JSON.readValue(json, new TypeReference<>() {});
            return parsed.stream()
                    .filter(a -> a.containsKey("base64"))
                    .map(a -> new EmailAttachment(
                            a.get("name"),
                            a.get("contentType"),
                            com.azure.core.util.BinaryData.fromBytes(Base64.getDecoder().decode(a.get("base64")))))
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to parse attachments JSON: {}", e.getMessage());
            return List.of();
        }
    }
}
