package com.datagami.rentaxis.core.service.gateway;

import com.datagami.rentaxis.api.dto.CreateOrderResponseDTO;

import java.math.BigDecimal;
import java.math.RoundingMode;

public interface PaymentGatewayProvider {
    String getGatewayCode();

    CreateOrderResponseDTO createOrder(BigDecimal amount, String currency, String receiptId, String apiKey, String apiSecret);

    boolean verifyPaymentSignature(String orderId, String paymentId, String signature, String apiSecret);

    boolean verifyWebhookSignature(String payload, String signatureHeader, String webhookSecret);

    /**
     * An amount in the currency's smallest unit (fils, paise) — how every gateway
     * on this interface talks about money, and the figure a capture is checked
     * against.
     *
     * <p><b>Exact or nothing.</b> This is one side of a money comparison, not a
     * display rounding: {@code OnlinePaymentService.unappliable} refuses a capture
     * whose reported minor units are not this number. {@code longValue()} truncated
     * anything it could not represent, so a three-decimal amount — an import, a
     * proration, a future currency with more than two places — would be charged one
     * figure and reconciled as another, silently. An amount this cannot represent
     * is a bug upstream and is raised as one.</p>
     *
     * @throws ArithmeticException when the amount carries precision finer than the
     *         two decimal places the register keeps money in.
     */
    static long minorUnits(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }
}
