package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.DeductionAttachmentDTO;
import com.datagami.rentaxis.core.service.DeductionAttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/settlements")
@RequiredArgsConstructor
public class DeductionAttachmentController {

    private final DeductionAttachmentService attachmentService;

    @PostMapping("/deductions/{deductionId}/attachments")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<DeductionAttachmentDTO> upload(
            @PathVariable UUID deductionId,
            @RequestParam("name") String name,
            @RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(attachmentService.uploadAttachment(deductionId, name, file));
    }

    @GetMapping("/deductions/{deductionId}/attachments")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<DeductionAttachmentDTO>> list(@PathVariable UUID deductionId) {
        return ResponseEntity.ok(attachmentService.getAttachments(deductionId));
    }

    @GetMapping("/attachments/{id}/download")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        byte[] content = attachmentService.downloadAttachment(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=document")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }

    @DeleteMapping("/attachments/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        attachmentService.deleteAttachment(id);
        return ResponseEntity.ok().build();
    }
}
