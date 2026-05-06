package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.RentReceiptPayload;
import com.datagami.rentaxis.core.email.outbox.EmailOutbox;
import com.datagami.rentaxis.core.email.outbox.EmailOutboxRepository;
import com.datagami.rentaxis.core.email.render.EmailRenderResult;
import com.datagami.rentaxis.core.email.render.EmailRenderer;
import com.datagami.rentaxis.core.email.render.EmailTemplateContext;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailDispatcher {

    private final RecipientResolver recipientResolver;
    private final EmailPreferenceService preferenceService;
    private final TenantBrandingResolver brandingResolver;
    private final EmailRenderer renderer;
    private final EmailOutboxRepository outboxRepository;
    private final TenantFeatureService tenantFeatureService;
    private final ObjectMapper objectMapper;

    @Value("${NEXT_PUBLIC_API_URL:https://rentaxis.uaenorth.cloudapp.azure.com}")
    private String portalBaseUrl;

    @Value("${rentaxis.email.outbox.enabled:true}")
    private boolean enabled;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEmailEvent(EmailEvent event) {
        if (!enabled) return;

        // Per-tenant kill-switch: gate email dispatch on the EMAIL_NOTIFICATIONS feature flag.
        // Defaults to OFF — flipped on per-tenant from the admin UI during phased rollout.
        if (!tenantFeatureService.isEnabled(event.getTenantId(), TenantFeature.EMAIL_NOTIFICATIONS)) {
            log.info("email.dispatch.skipped reason=feature_disabled tenant_id={} event_type={}",
                    event.getTenantId(), event.getType());
            return;
        }

        List<ResolvedRecipient> recipients;
        try {
            recipients = recipientResolver.resolve(event.getType(), event.getPayload());
        } catch (Exception e) {
            log.error("email.dispatch.resolve_failed event_type={} tenant_id={} dedup_key={} error={}",
                    event.getType(), event.getTenantId(), event.getDedupKey(), e.getMessage(), e);
            return;
        }
        TenantBranding branding = brandingResolver.resolve(event.getTenantId());

        for (ResolvedRecipient recipient : recipients) {
            try {
                if (!preferenceService.shouldSend(recipient.userId(), event.getType().category())) {
                    log.info("email.dispatch.skipped reason=opt-out user_id={} event_type={}", recipient.userId(), event.getType());
                    continue;
                }

                String unsubscribeToken = preferenceService.unsubscribeToken(recipient.userId());
                String unsubscribeUrl = portalBaseUrl + "/api/v1/email/unsubscribe?token=" + unsubscribeToken;

                String localeLang = recipient.locale().getLanguage();
                Map<String, Object> payloadVars = PayloadVarsExtractor.extract(
                        event.getType(), event.getPayload(), portalBaseUrl, localeLang);

                EmailTemplateContext ctx = new EmailTemplateContext(
                        event.getType(),
                        recipient.locale(),
                        recipient.userId(),
                        recipient.name(),
                        recipient.email(),
                        portalBaseUrl,
                        branding,
                        unsubscribeUrl,
                        payloadVars,
                        event.getPayload());

                EmailRenderResult rendered = renderer.render(ctx);

                EmailOutbox row = new EmailOutbox();
                row.setTenantId(event.getTenantId());
                row.setEventType(event.getType().name());
                row.setEventCategory(event.getType().category().name());
                row.setRecipientUserId(recipient.userId());
                row.setRecipientEmail(recipient.email());
                row.setRecipientLocale(localeLang);
                row.setSubject(rendered.subject());
                row.setBodyHtml(rendered.html());
                row.setBodyText(rendered.text());
                row.setDedupKey(event.getDedupKey());

                if (event.getPayload() instanceof RentReceiptPayload rr && rr.pdfBase64() != null) {
                    String filename = rr.pdfFileName() != null ? rr.pdfFileName() : "rent-receipt.pdf";
                    try {
                        Map<String, String> attachment = Map.of(
                                "name", filename,
                                "contentType", "application/pdf",
                                "base64", rr.pdfBase64());
                        row.setAttachments(objectMapper.writeValueAsString(List.of(attachment)));
                    } catch (JsonProcessingException jpe) {
                        log.warn("email.dispatch.attachment_serialize_failed event_type={} tenant_id={} error={}",
                                event.getType(), event.getTenantId(), jpe.getMessage());
                    }
                }

                enqueue(row);
            } catch (Exception e) {
                log.error("email.dispatch.failed event_type={} tenant_id={} recipient={} dedup_key={} error={}",
                        event.getType(), event.getTenantId(), recipient.email(), event.getDedupKey(), e.getMessage(), e);
            }
        }

        log.info("email.dispatch.enqueued event_type={} recipients={} tenant_id={}",
                event.getType(), recipients.size(), event.getTenantId());
    }

    private void enqueue(EmailOutbox row) {
        try {
            outboxRepository.save(row);
        } catch (DataIntegrityViolationException e) {
            log.info("email.dispatch.dedup_collision dedup_key={}", row.getDedupKey());
        }
    }
}
