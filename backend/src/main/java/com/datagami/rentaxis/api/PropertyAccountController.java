package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.ledger.RoleMappingDTO;
import com.datagami.rentaxis.api.dto.ledger.TemplateRowDTO;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
public class PropertyAccountController {

    private final PropertyAccountService service;

    public PropertyAccountController(PropertyAccountService service) {
        this.service = service;
    }

    public record AccountIdBody(UUID accountId) {}

    @GetMapping("/api/v1/properties/{id}/accounts")
    public ResponseEntity<List<RoleMappingDTO>> mappings(@PathVariable UUID id) {
        service.requireOwnProperty(id);
        return ResponseEntity.ok(service.getMappings(id));
    }

    @PostMapping("/api/v1/properties/{id}/accounts/generate")
    public ResponseEntity<List<RoleMappingDTO>> generate(@PathVariable UUID id) {
        service.requireOwnProperty(id);
        return ResponseEntity.ok(service.generateMissing(id));
    }

    @PutMapping("/api/v1/properties/{id}/accounts/{role}")
    public ResponseEntity<RoleMappingDTO> set(@PathVariable UUID id, @PathVariable AccountRole role, @RequestBody AccountIdBody body) {
        service.requireOwnProperty(id);
        return ResponseEntity.ok(service.setMapping(id, role, body.accountId()));
    }

    @DeleteMapping("/api/v1/properties/{id}/accounts/{role}")
    public ResponseEntity<Void> clear(@PathVariable UUID id, @PathVariable AccountRole role) {
        service.requireOwnProperty(id);
        service.clearMapping(id, role);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/finance/account-template")
    public ResponseEntity<List<TemplateRowDTO>> template() {
        return ResponseEntity.ok(service.getTemplate());
    }

    @PutMapping("/api/v1/finance/account-template")
    public ResponseEntity<List<TemplateRowDTO>> saveTemplate(@RequestBody List<TemplateRowDTO> rows) {
        return ResponseEntity.ok(service.saveTemplate(rows));
    }

    @GetMapping("/api/v1/finance/default-accounts")
    public ResponseEntity<List<RoleMappingDTO>> defaults() {
        return ResponseEntity.ok(service.getTenantDefaults());
    }

    @PutMapping("/api/v1/finance/default-accounts/{role}")
    public ResponseEntity<RoleMappingDTO> setDefault(@PathVariable AccountRole role, @RequestBody AccountIdBody body) {
        return ResponseEntity.ok(service.setTenantDefault(role, body.accountId()));
    }

    /** The role vocabulary, so the web can render the mapping screen without hard-coding the enum. */
    @GetMapping("/api/v1/finance/account-roles")
    public ResponseEntity<List<Map<String, Object>>> roles() {
        return ResponseEntity.ok(Arrays.stream(AccountRole.values())
                .map(r -> Map.<String, Object>of("role", r.name(), "propertyScoped", r.isPropertyScoped())).toList());
    }
}
