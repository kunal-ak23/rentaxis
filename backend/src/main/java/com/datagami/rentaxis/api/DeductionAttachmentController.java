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
    public ResponseEntity<Resource> download(@PathVariable UUID id,
                                             @org.springframework.web.bind.annotation.RequestParam(
                                                     value = "inline", defaultValue = "false") boolean inline)
            throws IOException {
        DeductionAttachmentDTO meta = attachmentService.getAttachmentById(id);
        InputStreamResource resource = new InputStreamResource(attachmentService.downloadAttachmentStream(id));
        String declared = meta.getFileType() != null ? meta.getFileType() : "application/octet-stream";
        String safeName = meta.getName().replaceAll("[\"\\r\\n\\\\/:*?<>|]", "_");
        // PR #401 R1 P3-4: the settlement preview frames a PDF scan, which an
        // "attachment" answer turns into a download. Inline only on request and
        // only for a PDF or raster image type; anything else (the type is the
        // uploader's claim) stays a download, and nosniff stops a browser
        // second-guessing the type either way.
        String type = declared.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
        boolean showInline = inline && INLINE_TYPES.contains(type);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        (showInline ? "inline" : "attachment") + "; filename=\"" + safeName + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(showInline ? type : declared))
                .body(resource);
    }

    static final java.util.Set<String> INLINE_TYPES = java.util.Set.of(
            "application/pdf", "image/png", "image/jpeg", "image/gif", "image/webp");

    @DeleteMapping("/attachments/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        attachmentService.deleteAttachment(id);
        return ResponseEntity.noContent().build();
    }
}
