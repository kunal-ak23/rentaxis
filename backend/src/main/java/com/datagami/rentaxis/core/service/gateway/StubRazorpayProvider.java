package com.datagami.rentaxis.core.service.gateway;

import com.datagami.rentaxis.api.dto.CreateOrderResponseDTO;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The RAZORPAY provider with its one network call removed, for environments that
 * must exercise the online-payment path without a live gateway account: the
 * recorded walkthrough and the local stack.
 *
 * <p><strong>Only order creation is stubbed.</strong> Both signature checks below
 * are the real HMAC-SHA256 computations Razorpay documents, over the same inputs
 * {@link RazorpayProvider} passes to {@code com.razorpay.Utils}. That is
 * deliberate: the webhook's signature is the ONLY thing standing between an
 * unauthenticated endpoint and the ledger (see {@code WebhookService}'s gate), so
 * a stub that waved it through would prove the opposite of what the walkthrough
 * exists to prove, and would be a hole if this were ever switched on by mistake.</p>
 *
 * <p>Off unless {@code rentaxis.gateway.stub.enabled=true} is set explicitly;
 * {@link RazorpayProvider} carries the inverse condition, so exactly one bean
 * answers to RAZORPAY and the default is always the real one.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rentaxis.gateway.stub.enabled", havingValue = "true")
public class StubRazorpayProvider implements PaymentGatewayProvider {

    @PostConstruct
    void announce() {
        log.warn("""
                ====================================================================
                RAZORPAY ORDER CREATION IS STUBBED (rentaxis.gateway.stub.enabled).
                No order reaches the gateway and no money can move. Signature
                verification is still real. Never enable this in production.
                ====================================================================""");
    }

    @Override
    public String getGatewayCode() {
        return "RAZORPAY";
    }

    /**
     * A synthetic order, with the amount in the currency's smallest unit exactly
     * as the real provider reports it — what the capture path checks the webhook's
     * reported amount against.
     */
    @Override
    public CreateOrderResponseDTO createOrder(BigDecimal amount, String currency, String receiptId,
                                              String apiKey, String apiSecret) {
        long amountInSmallestUnit = amount.multiply(BigDecimal.valueOf(100)).longValue();

        CreateOrderResponseDTO response = new CreateOrderResponseDTO();
        response.setOrderId("order_stub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14));
        response.setAmount(amountInSmallestUnit);
        response.setCurrency(currency);
        response.setGatewayKey(apiKey);
        response.setGatewayCode("RAZORPAY");
        log.info("Stubbed Razorpay order {} for {} {}", response.getOrderId(), amountInSmallestUnit, currency);
        return response;
    }

    /** Razorpay's scheme: HMAC-SHA256 of "{orderId}|{paymentId}" under the API secret. */
    @Override
    public boolean verifyPaymentSignature(String orderId, String paymentId, String signature, String apiSecret) {
        return matches(orderId + "|" + paymentId, signature, apiSecret);
    }

    /** Razorpay's scheme: HMAC-SHA256 of the raw body under the webhook secret. */
    @Override
    public boolean verifyWebhookSignature(String payload, String signatureHeader, String webhookSecret) {
        return matches(payload, signatureHeader, webhookSecret);
    }

    private static boolean matches(String signedContent, String presented, String secret) {
        if (signedContent == null || presented == null || secret == null || secret.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8)));
            // Constant-time: a timing oracle here would leak the signature byte by byte.
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    presented.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Stub signature verification failed to run: {}", e.getMessage());
            return false;
        }
    }
}
