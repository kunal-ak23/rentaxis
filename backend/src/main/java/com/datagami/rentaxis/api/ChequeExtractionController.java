package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.ChequeExtractionResponseDTO;
import com.datagami.rentaxis.core.service.cheque.ChequeExtractionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/cheques")
public class ChequeExtractionController {

    private final ChequeExtractionService service;

    public ChequeExtractionController(ChequeExtractionService service) {
        this.service = service;
    }

    @PostMapping(value = "/extract", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','PROPERTY_MANAGER','TENANT_USER')")
    public ResponseEntity<ChequeExtractionResponseDTO> extract(@RequestPart("file") MultipartFile file) {
        var tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new IllegalArgumentException("Tenant context is required");
        }
        return ResponseEntity.ok(service.extractAndStore(tenantId, file));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleValidation(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
