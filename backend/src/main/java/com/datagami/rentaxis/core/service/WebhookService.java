package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.VerifyPaymentRequestDTO;
import com.datagami.rentaxis.core.service.gateway.PaymentGatewayFactory;
import com.datagami.rentaxis.core.service.gateway.PaymentGatewayProvider;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.OnlinePayment;
import com.datagami.rentaxis.domain.entity.TenantGatewayConfig;
import com.datagami.rentaxis.domain.entity.WebhookLog;
import com.datagami.rentaxis.domain.repository.OnlinePaymentRepository;
import com.datagami.rentaxis.domain.repository.TenantGatewayConfigRepository;
import com.datagami.rentaxis.domain.repository.WebhookLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookService {

    private final OnlinePaymentRepository onlinePaymentRepository;
    private final WebhookLogRepository webhookLogRepository;
    private final TenantGatewayConfigRepository tenantGatewayConfigRepository;
    private final EncryptionService encryptionService;
    private final PaymentGatewayFactory paymentGatewayFactory;
    private final OnlinePaymentService onlinePaymentService;

    @Transactional
    public void processRazorpayWebhook(String payload, String signature) {
        // Log the webhook
        WebhookLog webhookLog = new WebhookLog();
        webhookLog.setGatewayCode("RAZORPAY");
        webhookLog.setPayloadJson(payload);
        webhookLog.setSignatureHeader(signature);
        webhookLog.setProcessed(false);
        webhookLog.setCreatedAt(Instant.now());

        try {
            // Parse payload to extract order_id
            JSONObject json = new JSONObject(payload);
            String eventType = json.optString("event", "unknown");
            webhookLog.setEventType(eventType);

            // Extract order_id from payload
            JSONObject paymentEntity = json
                    .optJSONObject("payload")
                    .optJSONObject("payment")
                    .optJSONObject("entity");

            if (paymentEntity == null) {
                webhookLog.setProcessingResult("No payment entity found in payload");
                webhookLogRepository.save(webhookLog);
                return;
            }

            String orderId = paymentEntity.optString("order_id");
            String paymentId = paymentEntity.optString("id");

            // Look up OnlinePayment via unfiltered native query (no tenant filter)
            Optional<OnlinePayment> optPayment = onlinePaymentRepository.findByGatewayOrderIdUnfiltered(orderId);
            if (optPayment.isEmpty()) {
                webhookLog.setProcessingResult("No online payment found for order: " + orderId);
                webhookLogRepository.save(webhookLog);
                return;
            }

            OnlinePayment onlinePayment = optPayment.get();
            UUID tenantId = onlinePayment.getTenantId();
            webhookLog.setTenantId(tenantId);

            // Set tenant context for downstream operations
            TenantContextHolder.setTenantId(tenantId);

            // Verify webhook signature
            List<TenantGatewayConfig> configs = tenantGatewayConfigRepository.findByIsActiveTrue();
            if (configs.isEmpty()) {
                webhookLog.setProcessingResult("No active gateway config found for tenant");
                webhookLogRepository.save(webhookLog);
                return;
            }

            TenantGatewayConfig config = configs.get(0);
            if (config.getWebhookSecretEncrypted() != null) {
                String webhookSecret = encryptionService.decrypt(config.getWebhookSecretEncrypted());
                PaymentGatewayProvider provider = paymentGatewayFactory.getProvider("RAZORPAY");
                boolean signatureValid = provider.verifyWebhookSignature(payload, signature, webhookSecret);
                if (!signatureValid) {
                    webhookLog.setProcessingResult("Webhook signature verification failed");
                    webhookLogRepository.save(webhookLog);
                    return;
                }
            }

            // Process payment.captured event
            if ("payment.captured".equals(eventType)) {
                VerifyPaymentRequestDTO verifyRequest = new VerifyPaymentRequestDTO();
                verifyRequest.setGatewayOrderId(orderId);
                verifyRequest.setGatewayPaymentId(paymentId);
                // Webhooks don't have the client-side signature, so we use the webhook signature
                verifyRequest.setGatewaySignature(signature);

                onlinePaymentService.verifyPayment(verifyRequest);
                webhookLog.setProcessingResult("Payment captured successfully");
            } else {
                webhookLog.setProcessingResult("Event type not handled: " + eventType);
            }

            webhookLog.setProcessed(true);
        } catch (Exception e) {
            log.error("Error processing Razorpay webhook", e);
            webhookLog.setProcessingResult("Error: " + e.getMessage());
        } finally {
            webhookLogRepository.save(webhookLog);
            TenantContextHolder.clear();
        }
    }
}
