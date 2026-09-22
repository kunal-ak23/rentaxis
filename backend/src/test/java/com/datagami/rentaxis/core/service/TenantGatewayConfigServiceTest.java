package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.TenantGatewayConfigDTO;
import com.datagami.rentaxis.domain.entity.PaymentGateway;
import com.datagami.rentaxis.domain.entity.TenantGatewayConfig;
import com.datagami.rentaxis.domain.repository.PaymentGatewayRepository;
import com.datagami.rentaxis.domain.repository.TenantGatewayConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantGatewayConfigServiceTest {

    @Mock
    TenantGatewayConfigRepository tenantGatewayConfigRepository;

    @Mock
    PaymentGatewayRepository paymentGatewayRepository;

    @Mock
    com.datagami.rentaxis.domain.repository.AccountRepository accountRepository;

    @Mock
    EncryptionService encryptionService;

    TenantGatewayConfigService service;

    private PaymentGateway gateway;
    private TenantGatewayConfig existingConfig;

    @BeforeEach
    void setUp() {
        service = new TenantGatewayConfigService(
                tenantGatewayConfigRepository, paymentGatewayRepository, accountRepository, encryptionService);

        gateway = new PaymentGateway();
        gateway.setId(UUID.randomUUID());
        gateway.setCode("RAZORPAY");
        gateway.setName("Razorpay");

        existingConfig = new TenantGatewayConfig();
        existingConfig.setId(UUID.randomUUID());
        existingConfig.setGateway(gateway);
        existingConfig.setApiKeyEncrypted("enc-old-key");
        existingConfig.setApiSecretEncrypted("enc-old-secret");

        lenient().when(paymentGatewayRepository.findById(gateway.getId())).thenReturn(Optional.of(gateway));
        lenient().when(tenantGatewayConfigRepository.save(any(TenantGatewayConfig.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // mapToDTO decrypts the stored key to build the masked form
        lenient().when(encryptionService.decrypt(anyString())).thenReturn("rzp_test_1234567890");
    }

    private TenantGatewayConfigDTO dto(String apiKey, String apiSecret) {
        TenantGatewayConfigDTO dto = new TenantGatewayConfigDTO();
        dto.setGatewayId(gateway.getId());
        dto.setApiKey(apiKey);
        dto.setApiSecret(apiSecret);
        dto.setIsTestMode(true);
        return dto;
    }

    @Test
    void saveConfig_keepsExistingCredentials_whenAbsent() {
        when(tenantGatewayConfigRepository.findByIsActiveTrueOrderByCreatedAtAscIdAsc()).thenReturn(List.of(existingConfig));

        service.saveConfig(dto(null, null));

        assertThat(existingConfig.getApiKeyEncrypted()).isEqualTo("enc-old-key");
        assertThat(existingConfig.getApiSecretEncrypted()).isEqualTo("enc-old-secret");
        verify(encryptionService, never()).encrypt(any());
        verify(tenantGatewayConfigRepository).save(existingConfig);
    }

    @Test
    void saveConfig_keepsExistingCredentials_whenBlank() {
        when(tenantGatewayConfigRepository.findByIsActiveTrueOrderByCreatedAtAscIdAsc()).thenReturn(List.of(existingConfig));

        service.saveConfig(dto("", ""));

        assertThat(existingConfig.getApiKeyEncrypted()).isEqualTo("enc-old-key");
        assertThat(existingConfig.getApiSecretEncrypted()).isEqualTo("enc-old-secret");
        verify(encryptionService, never()).encrypt(any());
    }

    @Test
    void saveConfig_ignoresRoundTrippedMaskedKey() {
        when(tenantGatewayConfigRepository.findByIsActiveTrueOrderByCreatedAtAscIdAsc()).thenReturn(List.of(existingConfig));

        // A client echoing back the masked key from the GET response must not overwrite the real one
        service.saveConfig(dto("rzp_test****", null));

        assertThat(existingConfig.getApiKeyEncrypted()).isEqualTo("enc-old-key");
        assertThat(existingConfig.getApiSecretEncrypted()).isEqualTo("enc-old-secret");
        verify(encryptionService, never()).encrypt(any());
    }

    @Test
    void saveConfig_updatesCredentials_whenProvided() {
        when(tenantGatewayConfigRepository.findByIsActiveTrueOrderByCreatedAtAscIdAsc()).thenReturn(List.of(existingConfig));
        when(encryptionService.encrypt("rzp_live_newkey")).thenReturn("enc-new-key");
        when(encryptionService.encrypt("newsecret")).thenReturn("enc-new-secret");

        service.saveConfig(dto("rzp_live_newkey", "newsecret"));

        assertThat(existingConfig.getApiKeyEncrypted()).isEqualTo("enc-new-key");
        assertThat(existingConfig.getApiSecretEncrypted()).isEqualTo("enc-new-secret");
    }

    @Test
    void saveConfig_updatesTestModeOnly_whenCredentialsOmitted() {
        existingConfig.setIsTestMode(true);
        when(tenantGatewayConfigRepository.findByIsActiveTrueOrderByCreatedAtAscIdAsc()).thenReturn(List.of(existingConfig));

        TenantGatewayConfigDTO update = dto(null, null);
        update.setIsTestMode(false);
        TenantGatewayConfigDTO result = service.saveConfig(update);

        assertThat(existingConfig.getIsTestMode()).isFalse();
        assertThat(existingConfig.getApiKeyEncrypted()).isEqualTo("enc-old-key");
        assertThat(result.getApiKey()).isNull();
        assertThat(result.getApiSecret()).isNull();
    }

    @Test
    void saveConfig_rejectsNewConfig_withoutCredentials() {
        when(tenantGatewayConfigRepository.findByIsActiveTrueOrderByCreatedAtAscIdAsc()).thenReturn(List.of());

        assertThatThrownBy(() -> service.saveConfig(dto("", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("API key and API secret are required");
        verify(tenantGatewayConfigRepository, never()).save(any());
    }
}
