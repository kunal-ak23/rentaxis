package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PaymentGatewayDTO;
import com.datagami.rentaxis.api.dto.TenantGatewayConfigDTO;
import com.datagami.rentaxis.domain.entity.PaymentGateway;
import com.datagami.rentaxis.domain.entity.TenantGatewayConfig;
import com.datagami.rentaxis.domain.repository.PaymentGatewayRepository;
import com.datagami.rentaxis.domain.repository.TenantGatewayConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TenantGatewayConfigService {

    private final TenantGatewayConfigRepository tenantGatewayConfigRepository;
    private final PaymentGatewayRepository paymentGatewayRepository;
    private final EncryptionService encryptionService;

    @Transactional(readOnly = true)
    public TenantGatewayConfigDTO getActiveConfig() {
        List<TenantGatewayConfig> configs = tenantGatewayConfigRepository.findByIsActiveTrue();
        if (configs.isEmpty()) {
            return null;
        }
        TenantGatewayConfig config = configs.get(0);
        return mapToDTO(config);
    }

    @Transactional
    public TenantGatewayConfigDTO saveConfig(TenantGatewayConfigDTO dto) {
        PaymentGateway gateway = paymentGatewayRepository.findById(dto.getGatewayId())
                .orElseThrow(() -> new RuntimeException("Payment gateway not found"));

        TenantGatewayConfig config;
        List<TenantGatewayConfig> existingConfigs = tenantGatewayConfigRepository.findByIsActiveTrue();
        if (!existingConfigs.isEmpty()) {
            config = existingConfigs.get(0);
        } else {
            config = new TenantGatewayConfig();
            config.setCreatedAt(Instant.now());
        }

        config.setGateway(gateway);
        config.setApiKeyEncrypted(encryptionService.encrypt(dto.getApiKey()));
        config.setApiSecretEncrypted(encryptionService.encrypt(dto.getApiSecret()));
        if (dto.getWebhookSecret() != null && !dto.getWebhookSecret().isEmpty()) {
            config.setWebhookSecretEncrypted(encryptionService.encrypt(dto.getWebhookSecret()));
        }
        config.setIsActive(dto.getIsActive() != null ? dto.getIsActive() : true);
        config.setIsTestMode(dto.getIsTestMode() != null ? dto.getIsTestMode() : true);
        config.setUpdatedAt(Instant.now());

        TenantGatewayConfig saved = tenantGatewayConfigRepository.save(config);
        return mapToDTO(saved);
    }

    @Transactional(readOnly = true)
    public String testConnection() {
        List<TenantGatewayConfig> configs = tenantGatewayConfigRepository.findByIsActiveTrue();
        if (configs.isEmpty()) {
            return "No active gateway configuration found";
        }
        TenantGatewayConfig config = configs.get(0);
        try {
            String apiKey = encryptionService.decrypt(config.getApiKeyEncrypted());
            String apiSecret = encryptionService.decrypt(config.getApiSecretEncrypted());
            com.razorpay.RazorpayClient client = new com.razorpay.RazorpayClient(apiKey, apiSecret);
            // Try fetching orders to validate credentials
            client.orders.fetchAll();
            return "Connection successful";
        } catch (Exception e) {
            return "Connection failed: " + e.getMessage();
        }
    }

    @Transactional(readOnly = true)
    public List<PaymentGatewayDTO> getAvailableGateways() {
        return paymentGatewayRepository.findByIsActiveTrue().stream()
                .map(this::mapGatewayToDTO)
                .collect(Collectors.toList());
    }

    private TenantGatewayConfigDTO mapToDTO(TenantGatewayConfig config) {
        TenantGatewayConfigDTO dto = new TenantGatewayConfigDTO();
        dto.setId(config.getId());
        dto.setGatewayId(config.getGateway().getId());
        dto.setGatewayCode(config.getGateway().getCode());
        dto.setGatewayName(config.getGateway().getName());

        // Mask the API key for GET responses
        try {
            String decryptedKey = encryptionService.decrypt(config.getApiKeyEncrypted());
            if (decryptedKey != null && decryptedKey.length() > 8) {
                dto.setApiKeyMasked(decryptedKey.substring(0, 8) + "****");
            } else {
                dto.setApiKeyMasked("****");
            }
        } catch (Exception e) {
            dto.setApiKeyMasked("****");
        }
        // Never return secrets in GET response
        dto.setApiKey(null);
        dto.setApiSecret(null);
        dto.setWebhookSecret(null);
        dto.setHasWebhookSecret(config.getWebhookSecretEncrypted() != null && !config.getWebhookSecretEncrypted().isEmpty());
        dto.setIsActive(config.getIsActive());
        dto.setIsTestMode(config.getIsTestMode());
        return dto;
    }

    private PaymentGatewayDTO mapGatewayToDTO(PaymentGateway gateway) {
        PaymentGatewayDTO dto = new PaymentGatewayDTO();
        dto.setId(gateway.getId());
        dto.setCode(gateway.getCode());
        dto.setName(gateway.getName());
        dto.setDescription(gateway.getDescription());
        dto.setIsActive(gateway.getIsActive());
        dto.setSdkJsUrl(gateway.getSdkJsUrl());
        dto.setSupportedCurrencies(gateway.getSupportedCurrencies());
        return dto;
    }
}
