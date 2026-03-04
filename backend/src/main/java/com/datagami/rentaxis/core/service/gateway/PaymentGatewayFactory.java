package com.datagami.rentaxis.core.service.gateway;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class PaymentGatewayFactory {
    private final List<PaymentGatewayProvider> providers;

    public PaymentGatewayProvider getProvider(String gatewayCode) {
        return providers.stream()
                .filter(p -> p.getGatewayCode().equalsIgnoreCase(gatewayCode))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Unsupported gateway: " + gatewayCode));
    }
}
