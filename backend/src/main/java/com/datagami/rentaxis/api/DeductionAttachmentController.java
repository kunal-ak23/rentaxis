package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.DeductionAttachmentDTO;
import com.datagami.rentaxis.core.service.DeductionAttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
        return ResponseEntity.status(HttpStatus.CREATED).body(attachmentService.uploadAttachment(deductionId, name, file));
    }

    @GetMapping("/deductions/{deductionId}/attachments")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<DeductionAttachmentDTO>> list(@PathVariable UUID deductionId) {
        return ResponseEntity.ok(attachmentService.getAttachments(deductionId));
    }

    @GetMapping("/attachments/{id}/download")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<Resource> download(@PathVariable UUID id) throws IOException {
        DeductionAttachmentDTO meta = attachmentService.getAttachmentById(id);
        InputStreamResource resource = new InputStreamResource(attachmentService.downloadAttachmentStream(id));
        String contentType = meta.getFileType() != null ? meta.getFileType() : "application/octet-stream";
        String safeName = meta.getName().replaceAll("[\"\\r\\n\\\\/:*?<>|]", "_");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeName + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

    @DeleteMapping("/attachments/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        attachmentService.deleteAttachment(id);
        return ResponseEntity.noContent().build();
    }
}
