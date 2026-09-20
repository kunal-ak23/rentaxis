package com.datagami.rentaxis.core.service;

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

/**
 * Razorpay's own report of what happened to a payment.
 *
 * <p><b>The signature is the only authorisation this path has.</b> A webhook
 * arrives with no user and no session, so {@code ChequeService}'s gateway guard
 * treats it as a trusted internal caller — which is sound precisely because
 * {@link #verifyDelivery} has already proved the payload came from the gateway.
 * Everything that can change money therefore lives strictly <em>after</em> that
 * check returns clean, and the lookup before it reads state without touching it.
 * Reordering those two is not a refactor; it is handing the register to anyone who
 * can POST to {@code /api/webhooks/razorpay}.</p>
 *
 * <p><b>An unverifiable delivery is refused, not waved through.</b> An
 * organisation that has not configured a webhook secret cannot have its deliveries
 * checked, so they are recorded and dropped. Treating "no secret" as "no check
 * needed" would leave the register open on exactly the tenants nobody has
 * finished setting up.</p>
 */
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
        WebhookLog webhookLog = new WebhookLog();
        webhookLog.setGatewayCode("RAZORPAY");
        webhookLog.setPayloadJson(payload);
        webhookLog.setSignatureHeader(signature);
        webhookLog.setProcessed(false);
        webhookLog.setCreatedAt(Instant.now());

        try {
            JSONObject json = new JSONObject(payload);
            String eventType = json.optString("event", "unknown");
            webhookLog.setEventType(eventType);

            // Step by step rather than chained: a delivery missing any one of these
            // levels — a probe, a different event shape, a truncated body — used to
            // NPE on the next optJSONObject and be reported as a 500 "Error: null".
            JSONObject payloadNode = json.optJSONObject("payload");
            JSONObject paymentNode = payloadNode == null ? null : payloadNode.optJSONObject("payment");
            JSONObject paymentEntity = paymentNode == null ? null : paymentNode.optJSONObject("entity");

            if (paymentEntity == null) {
                webhookLog.setProcessingResult("Malformed payload: no payment entity");
                webhookLog.setProcessed(true);
                return;
            }

            String orderId = paymentEntity.optString("order_id");
            String paymentId = paymentEntity.optString("id");

            // Which tenant this delivery belongs to. Read through the unfiltered
            // native query because no tenant context exists yet — and read-only:
            // nothing below this point mutates anything until the signature holds.
            Optional<OnlinePayment> optPayment = onlinePaymentRepository.findByGatewayOrderIdUnfiltered(orderId);
            if (optPayment.isEmpty()) {
                webhookLog.setProcessingResult("No online payment found for order: " + orderId);
                return;
            }

            OnlinePayment onlinePayment = optPayment.get();
            UUID tenantId = onlinePayment.getTenantId();
            webhookLog.setTenantId(tenantId);
            TenantContextHolder.setTenantId(tenantId);

            // ---------------------------------------------------------------
            // The gate. Nothing above it changes state; nothing below it runs
            // without a signature this tenant's own secret vouches for.
            // ---------------------------------------------------------------
            String rejection = verifyDelivery(payload, signature);
            if (rejection != null) {
                webhookLog.setProcessingResult(rejection);
                return;
            }

            switch (eventType) {
                case "payment.captured" -> {
                    // What the gateway says it actually took, in the currency's
                    // smallest unit, passed through for the money check. -1 stands
                    // for "the delivery carried no amount"; 0 is a real (absurd)
                    // figure and must not be confused with absent.
                    long minorUnits = paymentEntity.optLong("amount", -1L);
                    String unapplied = onlinePaymentService.captureFromWebhook(
                            onlinePayment.getId(), paymentId,
                            minorUnits < 0 ? null : minorUnits,
                            blankToNull(paymentEntity.optString("currency", null)));
                    // Processed either way, and 200 either way. An unappliable
                    // capture is a recorded fact, not a delivery to retry: the
                    // gateway redelivering it forever would neither create the
                    // missing instalment nor issue the refund that is owed.
                    webhookLog.setProcessingResult(unapplied == null
                            ? "Payment captured successfully"
                            : "Captured but not applied: " + unapplied);
                }
                case "payment.failed" -> {
                    // Processed either way: a failure report for a payment that
                    // already captured is a recorded, deliberate no-op.
                    String ignored = onlinePaymentService.failFromWebhook(onlinePayment.getId(),
                            failureReason(paymentEntity));
                    webhookLog.setProcessingResult(ignored == null
                            ? "Payment failed; the register row was released"
                            : ignored);
                }
                default -> webhookLog.setProcessingResult("Event type not handled: " + eventType);
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

    /**
     * Null when the delivery is provably Razorpay's; otherwise the reason it is
     * not, for the audit row.
     *
     * <p>Returns rather than throws so the handler records the rejection and
     * answers 200: a gateway that is told "500" retries the same unverifiable
     * payload forever.</p>
     */
    private String verifyDelivery(String payload, String signature) {
        List<TenantGatewayConfig> configs = tenantGatewayConfigRepository.findByIsActiveTrueOrderByCreatedAtAscIdAsc();
        if (configs.isEmpty()) {
            return "No active gateway config found for tenant";
        }
        TenantGatewayConfig config = configs.get(0);
        String secret = config.getWebhookSecretEncrypted();
        if (secret == null || secret.isBlank()) {
            return "No webhook secret configured for this tenant; the delivery cannot be verified";
        }
        PaymentGatewayProvider provider = paymentGatewayFactory.getProvider("RAZORPAY");
        boolean valid = provider.verifyWebhookSignature(payload, signature, encryptionService.decrypt(secret));
        return valid ? null : "Webhook signature verification failed";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String failureReason(JSONObject paymentEntity) {
        String description = paymentEntity.optString("error_description", null);
        if (description != null && !description.isBlank()) {
            return description;
        }
        String code = paymentEntity.optString("error_code", null);
        return code != null && !code.isBlank() ? code : "Gateway reported the payment failed";
    }
}
