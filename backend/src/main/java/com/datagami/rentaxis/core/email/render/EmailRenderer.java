package com.datagami.rentaxis.core.email.render;

import com.datagami.rentaxis.core.email.event.payload.LegacyNotificationPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;
import java.util.NoSuchElementException;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailRenderer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TemplateEngine emailTemplateEngine;

    @Qualifier("emailMessageSource")
    private final MessageSource messageSource;

    public EmailRenderResult render(EmailTemplateContext ctx) {
        // Legacy fallback: NotificationService publishes EmailEvents wrapping
        // a LegacyNotificationPayload(userId, title, body, referenceType,
        // referenceId). We render those through a generic template and use the
        // payload's title as the subject, bypassing per-event i18n keys.
        String templateName;
        String subject;
        if (ctx.rawPayload() instanceof LegacyNotificationPayload legacy) {
            templateName = "email/events/legacy_notification";
            subject = legacy.title() == null ? ctx.type().name() : legacy.title();
        } else {
            templateName = "email/events/" + ctx.type().snake();
            String subjectKey = "email." + ctx.type().snake() + ".subject";
            Object[] subjectArgs = (Object[]) ctx.payloadVars().getOrDefault("__subjectArgs", new Object[0]);
            try {
                subject = messageSource.getMessage(subjectKey, subjectArgs, ctx.locale());
            } catch (NoSuchElementException | org.springframework.context.NoSuchMessageException e) {
                log.warn("Missing email i18n key {} for locale {} - falling back to event name",
                        subjectKey, ctx.locale());
                subject = ctx.type().name();
            }
        }

        Context tlCtx = new Context(ctx.locale());
        tlCtx.setVariable("recipient", Map.of(
                "name", ctx.recipientName() == null ? "" : ctx.recipientName(),
                "email", ctx.recipientEmail() == null ? "" : ctx.recipientEmail()));
        tlCtx.setVariable("portalBaseUrl", ctx.portalBaseUrl());
        tlCtx.setVariable("tenantBranding", ctx.tenantBranding());
        tlCtx.setVariable("unsubscribeUrl", ctx.unsubscribeUrl());
        tlCtx.setVariable("layout",
                ctx.locale().getLanguage().equals("ar") ? "email/layout/master-rtl" : "email/layout/master");
        ctx.payloadVars().forEach(tlCtx::setVariable);

        String html = emailTemplateEngine.process(templateName, tlCtx);
        String text = htmlToPlainText(html);

        return new EmailRenderResult(subject, html, text, null);
    }

    public String renderAttachmentsJson(Object attachmentsList) {
        if (attachmentsList == null) return null;
        try {
            return JSON.writeValueAsString(attachmentsList);
        } catch (Exception e) {
            log.warn("Failed to serialize attachments: {}", e.getMessage());
            return null;
        }
    }

    private String htmlToPlainText(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
