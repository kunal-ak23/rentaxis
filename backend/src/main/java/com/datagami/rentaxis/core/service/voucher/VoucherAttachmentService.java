package com.datagami.rentaxis.core.service.voucher;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.datagami.rentaxis.api.dto.voucher.VoucherAttachmentDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Voucher;
import com.datagami.rentaxis.domain.entity.VoucherAttachment;
import com.datagami.rentaxis.domain.entity.enums.VoucherStatus;
import com.datagami.rentaxis.domain.repository.VoucherAttachmentRepository;
import com.datagami.rentaxis.domain.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Invoice scans and payment proofs. Storage is the same two-mode arrangement as
 * {@code DeductionAttachmentService} — Azure blob under {@code tenant-<uuid>} when a
 * connection string is configured, local disk otherwise — so nothing new has to be
 * provisioned for this feature.
 *
 * <p>The allowed type list is narrower than the deduction one on purpose: a voucher
 * attachment is paperwork (a PDF or a photo of an invoice), never a video walkthrough.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoucherAttachmentService {

    private final VoucherAttachmentRepository attachments;
    private final VoucherRepository vouchers;

    private static final int MAX_ATTACHMENTS_PER_VOUCHER = 10;
    private static final long MAX_FILE_SIZE = 25L * 1024 * 1024;   // 25MB — an invoice scan, not a video
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/png", "image/heic", "image/heif", "image/webp");

    @Value("${AZURE_STORAGE_CONNECTION_STRING:}")
    private String azureConnectionString;

    @Value("${AZURE_STORAGE_CONTAINER_PREFIX:tenant-}")
    private String containerPrefix;

    @Value("${rentaxis.assets.storage-path:./data/assets}")
    private String localStoragePath;

    /**
     * Allowed on a DRAFT or POSTED voucher (spec/brief): evidence may arrive after
     * posting, e.g. a scanned receipt filed a day later. A REVERSED voucher's
     * attachments are read-only — {@link #requireMutable} refuses it.
     */
    @Transactional
    public VoucherAttachmentDTO upload(UUID voucherId, String docName, MultipartFile file) throws IOException {
        Voucher v = requireVoucher(voucherId);
        requireMutable(v);

        if (attachments.countByVoucherId(voucherId) >= MAX_ATTACHMENTS_PER_VOUCHER) {
            throw new BusinessRuleViolationException(
                    "Maximum " + MAX_ATTACHMENTS_PER_VOUCHER + " attachments per voucher");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessRuleViolationException("File size exceeds maximum of 25MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new BusinessRuleViolationException("File type not allowed. Accepted: PDF, JPEG, PNG, HEIC, WEBP");
        }

        // The storage key is a fresh UUID, never the client-supplied filename or any
        // path derived from it — only the extension (itself just a suffix, never a
        // path segment) is taken from what the client sent.
        String fileName = UUID.randomUUID() + extension(file.getOriginalFilename());
        String fileUrl = (azureConnectionString != null && !azureConnectionString.isBlank())
                ? uploadToAzure(v.getId(), fileName, file.getInputStream(), file.getSize())
                : saveToLocal(v.getId(), fileName, file.getInputStream());

        VoucherAttachment a = new VoucherAttachment();
        a.setVoucherId(voucherId);
        a.setName(docName);
        a.setFileUrl(fileUrl);
        a.setFileType(contentType);
        a.setFileSize(file.getSize());
        a.setUploadedAt(Instant.now());
        return VoucherAttachmentDTO.of(attachments.save(a));
    }

    @Transactional(readOnly = true)
    public List<VoucherAttachmentDTO> list(UUID voucherId) {
        requireVoucher(voucherId);
        return attachments.findByVoucherIdOrderByUploadedAtAsc(voucherId).stream()
                .map(VoucherAttachmentDTO::of).toList();
    }

    @Transactional(readOnly = true)
    public VoucherAttachmentDTO get(UUID attachmentId) {
        return VoucherAttachmentDTO.of(requireAttachment(attachmentId));
    }

    @Transactional(readOnly = true)
    public InputStream download(UUID attachmentId) throws IOException {
        VoucherAttachment a = requireAttachment(attachmentId);
        String url = a.getFileUrl();
        if (url.startsWith("https://") && url.contains(".blob.core.windows.net")) {
            return downloadFromAzure(url);
        }
        return Files.newInputStream(Path.of(localStoragePath)
                .resolve(url.replace("/api/v1/assets/serve/", "")));
    }

    @Transactional
    public void delete(UUID attachmentId) {
        VoucherAttachment a = requireAttachment(attachmentId);
        requireMutable(requireVoucher(a.getVoucherId()));
        attachments.delete(a);
    }

    /** REVERSED is the terminal, corrected state of a document — its paper trail is frozen with it. */
    private void requireMutable(Voucher v) {
        if (v.getStatus() == VoucherStatus.REVERSED) {
            throw new BusinessRuleViolationException("Voucher "
                    + (v.getVoucherNumber() == null ? v.getId() : v.getVoucherNumber())
                    + " is REVERSED; its attachments are read-only");
        }
    }

    private Voucher requireVoucher(UUID voucherId) {
        Voucher v = vouchers.findById(voucherId).orElseThrow(() -> new NotFoundException("Voucher not found"));
        UUID current = TenantContextHolder.getTenantId();
        if (current != null && !current.equals(v.getTenantId())) throw new NotFoundException("Voucher not found");
        return v;
    }

    private VoucherAttachment requireAttachment(UUID attachmentId) {
        VoucherAttachment a = attachments.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment not found"));
        UUID current = TenantContextHolder.getTenantId();
        if (current != null && !current.equals(a.getTenantId())) throw new NotFoundException("Attachment not found");
        return a;
    }

    private String uploadToAzure(UUID voucherId, String fileName, InputStream in, long size) {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) throw new IllegalStateException("Cannot upload: tenant context is not set");
        String containerName = containerPrefix + tenantId;
        BlobServiceClient svc = new BlobServiceClientBuilder().connectionString(azureConnectionString).buildClient();
        BlobContainerClient container = svc.getBlobContainerClient(containerName);
        if (!container.exists()) container.create();
        String blobPath = "vouchers/" + voucherId + "/" + fileName;
        container.getBlobClient(blobPath).upload(in, size, true);
        return svc.getAccountUrl() + "/" + containerName + "/" + blobPath;
    }

    private InputStream downloadFromAzure(String blobUrl) {
        String marker = ".blob.core.windows.net/";
        String path = blobUrl.substring(blobUrl.indexOf(marker) + marker.length());
        int slash = path.indexOf('/');
        BlobClient blob = new BlobServiceClientBuilder().connectionString(azureConnectionString).buildClient()
                .getBlobContainerClient(path.substring(0, slash))
                .getBlobClient(path.substring(slash + 1));
        return blob.openInputStream();
    }

    private String saveToLocal(UUID voucherId, String fileName, InputStream in) throws IOException {
        Path dir = Path.of(localStoragePath, "vouchers", voucherId.toString());
        Files.createDirectories(dir);
        Files.copy(in, dir.resolve(fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return "/api/v1/assets/serve/vouchers/" + voucherId + "/" + fileName;
    }

    private String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }
}
