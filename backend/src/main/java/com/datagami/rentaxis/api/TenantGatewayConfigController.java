package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PaymentGatewayDTO;
import com.datagami.rentaxis.api.dto.TenantGatewayConfigDTO;
import com.datagami.rentaxis.core.service.TenantGatewayConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/gateway-config")
@RequiredArgsConstructor
public class TenantGatewayConfigController {

    private final TenantGatewayConfigService tenantGatewayConfigService;

    @GetMapping("/gateways")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<List<PaymentGatewayDTO>> getAvailableGateways() {
        return ResponseEntity.ok(tenantGatewayConfigService.getAvailableGateways());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'RENTER')")
    public ResponseEntity<TenantGatewayConfigDTO> getActiveConfig() {
        TenantGatewayConfigDTO config = tenantGatewayConfigService.getActiveConfig();
        if (config == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(config);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<TenantGatewayConfigDTO> saveConfig(@RequestBody TenantGatewayConfigDTO dto) {
        return ResponseEntity.ok(tenantGatewayConfigService.saveConfig(dto));
    }

    @PostMapping("/test")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<Map<String, Object>> testConnection() {
        String result = tenantGatewayConfigService.testConnection();
        boolean success = result.startsWith("Connection successful");
        return ResponseEntity.ok(Map.of("success", success, "message", result));
    }
}
