package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.ChequeExtractionResponseDTO;
import com.datagami.rentaxis.api.dto.ChequeMultiExtractionResponseDTO;
import com.datagami.rentaxis.core.service.cheque.ChequeExtractionService;
import com.datagami.rentaxis.core.service.cheque.ChequeMultiExtractionService;
import com.datagami.rentaxis.core.service.cheque.ChequeUploadRefusedException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/cheques")
public class ChequeExtractionController {

    private final ChequeExtractionService service;
    private final ChequeMultiExtractionService multiService;

    public ChequeExtractionController(ChequeExtractionService service, ChequeMultiExtractionService multiService) {
        this.service = service;
        this.multiService = multiService;
    }

    @PostMapping(value = "/extract", consumes = "multipart/form-data")
    // Staff who attach scans to a lease's cheques. TENANT_USER has no cheque
    // register to attach to, so this was free storage and OCR spend (audit C-F4).
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER')")
    public ResponseEntity<ChequeExtractionResponseDTO> extract(@RequestPart("file") MultipartFile file) {
        var tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new IllegalArgumentException("Tenant context is required");
        }
        return ResponseEntity.ok(service.extractAndStore(tenantId, file));
    }

    /**
     * Every cheque in one photo or PDF, each with its own server-issued image
     * (a crop when the photo holds several). {@code /extract} above is kept
     * unchanged for the mobile app and the single-cheque scanner, which read one
     * cheque per call and are already in users' hands.
     */
    @PostMapping(value = "/extract-many", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER')")
    public ResponseEntity<ChequeMultiExtractionResponseDTO> extractMany(@RequestPart("file") MultipartFile file) {
        var tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new IllegalArgumentException("Tenant context is required");
        }
        return ResponseEntity.ok(multiService.extractAndStore(tenantId, file));
    }

    @ExceptionHandler(ChequeUploadRefusedException.class)
    public ResponseEntity<Map<String, String>> handleRefused(ChequeUploadRefusedException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case ChequeUploadRefusedException.FILE_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case ChequeUploadRefusedException.BUSY -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of("error", ex.getMessage(), "code", ex.getCode()));
    }

    /** Code the web maps to "storage not reachable, nothing was saved" (tutorial 15). */
    public static final String STORAGE_UNAVAILABLE = "cheque_storage_unavailable";

    /**
     * Tutorial 15: blob storage down. A clear 503 the scan page can name, instead of a
     * bare 500 (or a hang) — nothing was stored: the multi-cheque path removes what it
     * had issued before rethrowing.
     */
    @ExceptionHandler(com.datagami.rentaxis.core.service.BlobStorageService.BlobStorageException.class)
    public ResponseEntity<Map<String, String>> handleStorage(
            com.datagami.rentaxis.core.service.BlobStorageService.BlobStorageException ex) {
        org.slf4j.LoggerFactory.getLogger(ChequeExtractionController.class).warn("Cheque scan storage failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "Cheque storage is not reachable right now, so nothing was saved. Try again in a few minutes.",
                "code", STORAGE_UNAVAILABLE));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleValidation(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.valueOf(413))
                .body(Map.of("error", "File too large; max 10MB",
                        "code", ChequeUploadRefusedException.FILE_TOO_LARGE));
    }
}
