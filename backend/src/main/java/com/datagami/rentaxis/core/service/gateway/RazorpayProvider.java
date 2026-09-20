package com.datagami.rentaxis.core.service.gateway;

import com.datagami.rentaxis.api.dto.CreateOrderResponseDTO;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@ConditionalOnProperty(name = "rentaxis.gateway.stub.enabled", havingValue = "false", matchIfMissing = true)
public class RazorpayProvider implements PaymentGatewayProvider {

    @Override
    public String getGatewayCode() {
        return "RAZORPAY";
    }

    @Override
    public CreateOrderResponseDTO createOrder(BigDecimal amount, String currency, String receiptId, String apiKey, String apiSecret) {
        try {
            RazorpayClient client = new RazorpayClient(apiKey, apiSecret);
            JSONObject options = new JSONObject();
            long amountInSmallestUnit = amount.multiply(BigDecimal.valueOf(100)).longValue();
            options.put("amount", amountInSmallestUnit);
            options.put("currency", currency);
            options.put("receipt", receiptId);

            Order order = client.orders.create(options);

            CreateOrderResponseDTO response = new CreateOrderResponseDTO();
            response.setOrderId(order.get("id"));
            response.setAmount(amountInSmallestUnit);
            response.setCurrency(currency);
            response.setGatewayKey(apiKey);
            response.setGatewayCode("RAZORPAY");
            return response;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Razorpay order: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean verifyPaymentSignature(String orderId, String paymentId, String signature, String apiSecret) {
        try {
            JSONObject options = new JSONObject();
            options.put("razorpay_order_id", orderId);
            options.put("razorpay_payment_id", paymentId);
            options.put("razorpay_signature", signature);
            return Utils.verifyPaymentSignature(options, apiSecret);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signatureHeader, String webhookSecret) {
        try {
            return Utils.verifyWebhookSignature(payload, signatureHeader, webhookSecret);
        } catch (Exception e) {
            return false;
        }
    }
}
