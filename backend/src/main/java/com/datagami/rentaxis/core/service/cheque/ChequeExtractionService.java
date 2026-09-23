package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.ChequeExtractionResponseDTO;
import com.datagami.rentaxis.api.dto.ChequeImageMetaDTO;
import com.datagami.rentaxis.core.service.BlobStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class ChequeExtractionService {

    private static final long MAX_BYTES = 10 * 1024 * 1024L;
    private static final Set<String> ALLOWED_TYPES =
            Set.of("image/jpeg", "image/jpg", "image/png", "image/heic", "image/heif");

    private final BlobStorageService blobStorage;
    private final ChequeExtractor extractor;
    private final com.datagami.rentaxis.domain.repository.ChequeImageUploadRepository uploads;

    public ChequeExtractionService(BlobStorageService blobStorage, ChequeExtractor extractor,
                                   com.datagami.rentaxis.domain.repository.ChequeImageUploadRepository uploads) {
        this.blobStorage = blobStorage;
        this.extractor = extractor;
        this.uploads = uploads;
    }

    public ChequeExtractionResponseDTO extractAndStore(UUID tenantId, MultipartFile file) {
        validate(file);

        var uploadResult = blobStorage.uploadCheque(tenantId, file);
        var uploadedAt = OffsetDateTime.now();
        // The server-issued path is the only one bulk-attach will accept (audit C-F2).
        var issued = new com.datagami.rentaxis.domain.entity.ChequeImageUpload();
        issued.setTenantId(tenantId);
        issued.setBlobPath(uploadResult.blobPath());
        issued.setImageUrl(uploadResult.url());
        issued.setUploadedBy(callerIdOrNull());
        uploads.save(issued);

        ChequeExtractor.ExtractionResult extractionResult;
        try {
            extractionResult = extractor.extract(file.getBytes(), file.getContentType());
        } catch (Exception e) {
            log.warn("Cheque extractor threw unexpectedly", e);
            extractionResult = new ChequeExtractor.ExtractionResult(null, List.of("Extraction failed unexpectedly"));
        }

        return new ChequeExtractionResponseDTO(
                new ChequeImageMetaDTO(uploadResult.url(), uploadResult.blobPath(), uploadedAt),
                extractionResult.extracted(),
                extractionResult.warnings()
        );
    }

    private static java.util.UUID callerIdOrNull() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        try {
            return auth == null ? null : java.util.UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required and must not be empty");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("file too large; max 10MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("Unsupported image type: " + contentType);
        }
    }
}
