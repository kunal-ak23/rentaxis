package com.datagami.rentaxis.core.service.gateway;

import com.datagami.rentaxis.api.dto.CreateOrderResponseDTO;

import java.math.BigDecimal;

public interface PaymentGatewayProvider {
    String getGatewayCode();

    CreateOrderResponseDTO createOrder(BigDecimal amount, String currency, String receiptId, String apiKey, String apiSecret);

    boolean verifyPaymentSignature(String orderId, String paymentId, String signature, String apiSecret);

    boolean verifyWebhookSignature(String payload, String signatureHeader, String webhookSecret);
}
