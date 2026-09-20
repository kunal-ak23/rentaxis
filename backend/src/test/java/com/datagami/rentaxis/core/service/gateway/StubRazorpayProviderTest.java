package com.datagami.rentaxis.core.service.gateway;

import com.datagami.rentaxis.api.dto.CreateOrderResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stub exists so the walkthrough can drive the online-payment path without a
 * live gateway. Two things have to stay true: it is never wired by accident, and
 * it does not weaken the webhook's signature gate.
 */
class StubRazorpayProviderTest {

    private static final String SECRET = "whsec_walkthrough_secret";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(RazorpayProvider.class, StubRazorpayProvider.class);

    // ---- wiring: the default must always be the real gateway ---------------

    @Test
    @DisplayName("with no property set, only the real provider is wired")
    void theRealProviderIsTheDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(RazorpayProvider.class);
            assertThat(ctx).doesNotHaveBean(StubRazorpayProvider.class);
        });
    }

    @Test
    @DisplayName("stub.enabled=false is still the real provider")
    void anExplicitFalseIsTheRealProvider() {
        runner.withPropertyValues("rentaxis.gateway.stub.enabled=false").run(ctx -> {
            assertThat(ctx).hasSingleBean(RazorpayProvider.class);
            assertThat(ctx).doesNotHaveBean(StubRazorpayProvider.class);
        });
    }

    @Test
    @DisplayName("stub.enabled=true swaps the providers rather than adding one")
    void theStubReplacesTheRealProvider() {
        runner.withPropertyValues("rentaxis.gateway.stub.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(StubRazorpayProvider.class);
            // Two beans answering to RAZORPAY would make PaymentGatewayFactory's
            // findFirst() pick one by bean-ordering accident.
            assertThat(ctx).doesNotHaveBean(RazorpayProvider.class);
        });
    }

    @Test
    @DisplayName("the factory resolves RAZORPAY to the stub when it is enabled")
    void theFactoryResolvesToTheStub() {
        runner.withPropertyValues("rentaxis.gateway.stub.enabled=true")
                .withUserConfiguration(PaymentGatewayFactory.class)
                .run(ctx -> assertThat(ctx.getBean(PaymentGatewayFactory.class).getProvider("RAZORPAY"))
                        .isInstanceOf(StubRazorpayProvider.class));
    }

    // ---- order creation: no network, real minor units ----------------------

    @Test
    @DisplayName("createOrder reports the amount in minor units, as the capture check compares it")
    void createOrderReportsMinorUnits() {
        CreateOrderResponseDTO order = new StubRazorpayProvider()
                .createOrder(new BigDecimal("12000.00"), "AED", "receipt-1", "rzp_test_key", "secret");

        assertThat(order.getAmount()).isEqualTo(1_200_000L);
        assertThat(order.getCurrency()).isEqualTo("AED");
        assertThat(order.getGatewayCode()).isEqualTo("RAZORPAY");
        assertThat(order.getGatewayKey()).isEqualTo("rzp_test_key");
        assertThat(order.getOrderId()).startsWith("order_stub_");
    }

    @Test
    @DisplayName("each order id is distinct, so orders can be looked up one by one")
    void orderIdsAreDistinct() {
        StubRazorpayProvider stub = new StubRazorpayProvider();
        String first = stub.createOrder(BigDecimal.ONE, "AED", "r1", "k", "s").getOrderId();
        String second = stub.createOrder(BigDecimal.ONE, "AED", "r2", "k", "s").getOrderId();
        assertThat(first).isNotEqualTo(second);
    }

    // ---- signatures stay real ----------------------------------------------

    @Test
    @DisplayName("a webhook signed with the tenant's secret verifies")
    void aCorrectlySignedWebhookVerifies() {
        String payload = "{\"event\":\"payment.captured\"}";
        assertThat(new StubRazorpayProvider().verifyWebhookSignature(payload, hmac(payload, SECRET), SECRET))
                .isTrue();
    }

    @Test
    @DisplayName("a webhook signed with the wrong secret is refused")
    void aWebhookSignedWithAnotherSecretIsRefused() {
        String payload = "{\"event\":\"payment.captured\"}";
        assertThat(new StubRazorpayProvider()
                .verifyWebhookSignature(payload, hmac(payload, "someone-elses-secret"), SECRET))
                .isFalse();
    }

    @Test
    @DisplayName("a tampered payload is refused even with a once-valid signature")
    void aTamperedPayloadIsRefused() {
        String signed = "{\"amount\":100}";
        String signature = hmac(signed, SECRET);
        assertThat(new StubRazorpayProvider().verifyWebhookSignature("{\"amount\":999999}", signature, SECRET))
                .isFalse();
    }

    @Test
    @DisplayName("a missing or blank secret refuses rather than passing")
    void aBlankSecretRefuses() {
        String payload = "{}";
        StubRazorpayProvider stub = new StubRazorpayProvider();
        assertThat(stub.verifyWebhookSignature(payload, hmac(payload, SECRET), "")).isFalse();
        assertThat(stub.verifyWebhookSignature(payload, hmac(payload, SECRET), null)).isFalse();
        assertThat(stub.verifyWebhookSignature(payload, null, SECRET)).isFalse();
    }

    @Test
    @DisplayName("the payment signature follows Razorpay's orderId|paymentId scheme")
    void thePaymentSignatureUsesRazorpaysScheme() {
        StubRazorpayProvider stub = new StubRazorpayProvider();
        String signature = hmac("order_1|pay_1", SECRET);

        assertThat(stub.verifyPaymentSignature("order_1", "pay_1", signature, SECRET)).isTrue();
        assertThat(stub.verifyPaymentSignature("order_1", "pay_OTHER", signature, SECRET)).isFalse();
        assertThat(stub.verifyPaymentSignature("order_OTHER", "pay_1", signature, SECRET)).isFalse();
    }

    /** The same computation a gateway (and the walkthrough spec) performs. */
    private static String hmac(String content, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
