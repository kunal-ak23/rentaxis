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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 * PDF/PNG/JPEG only (security ruling, Task 5 fix round 1) — HEIC/HEIF/WEBP were
 * dropped along with the client-header-only trust: this class no longer has a
 * signature to check them against, and the web only ever offers these three
 * ({@code voucherRules.ts#ATTACHMENT_ACCEPT}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoucherAttachmentService {

    private final VoucherAttachmentRepository attachments;
    private final VoucherRepository vouchers;

    private static final int MAX_ATTACHMENTS_PER_VOUCHER = 10;
    // Aligned to spring.servlet.multipart.max-file-size (application.yml): the
    // global Tomcat/servlet limit wins regardless of what this constant says, so a
    // higher value here was dead code that could never be exercised, and an upload
    // between this value and the real one used to reach Tomcat's layer and surface
    // as an unhandled MaxUploadSizeExceededException -> raw 500 (security ruling).
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;   // 10MB
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/png");

    /** Storage-key prefix {@code AssetController.serveAsset} refuses to serve unauthenticated. */
    static final String PRIVATE_PREFIX = "private";

    // File-signature ("magic number") prefixes for the three accepted formats. The
    // client's declared Content-Type header is trivially spoofed -- an executable
    // renamed invoice.pdf with Content-Type: application/pdf sails through a
    // header-only check -- so the first bytes of the actual file are matched
    // against these instead (security ruling, Task 5 fix round 1).
    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

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
            throw new BusinessRuleViolationException("File size exceeds maximum of 10MB");
        }
        String declaredType = file.getContentType();
        if (declaredType == null || !ALLOWED_CONTENT_TYPES.contains(declaredType.toLowerCase())) {
            throw new BusinessRuleViolationException("File type not allowed. Accepted: PDF, JPEG, PNG");
        }

        // Read fully into memory (bounded above by MAX_FILE_SIZE, itself aligned to
        // the global 10MB multipart limit) so the same bytes can be sniffed for a
        // signature and then written, without needing MultipartFile's InputStream to
        // support being opened twice.
        byte[] bytes = file.getBytes();
        String detectedType = detectContentType(bytes);
        if (detectedType == null) {
            // Declared type passed, but the bytes are not really any of the three
            // accepted formats -- an executable, a script, or anything else wearing
            // a PDF/PNG/JPEG label.
            throw new BusinessRuleViolationException("File type not allowed. Accepted: PDF, JPEG, PNG");
        }

        // The storage key is a fresh UUID, never the client-supplied filename or any
        // path derived from it — only the extension (itself just a suffix, never a
        // path segment) is taken from what the client sent.
        String fileName = UUID.randomUUID() + extension(file.getOriginalFilename());
        String fileUrl = (azureConnectionString != null && !azureConnectionString.isBlank())
                ? uploadToAzure(v.getId(), fileName, new ByteArrayInputStream(bytes), bytes.length)
                : saveToLocal(v.getId(), fileName, new ByteArrayInputStream(bytes));

        VoucherAttachment a = new VoucherAttachment();
        a.setVoucherId(voucherId);
        a.setName(docName);
        a.setFileUrl(fileUrl);
        // The DETECTED type is stored, not the declared one: a real PNG mislabeled
        // image/jpeg is accepted (both are allowed formats, and the bytes are what
        // they are), but what gets served back as Content-Type is what the bytes
        // actually decode as, not what the uploader's browser happened to send.
        a.setFileType(detectedType);
        a.setFileSize(file.getSize());
        a.setUploadedAt(Instant.now());
        return VoucherAttachmentDTO.of(attachments.save(a));
    }

    /** {@code null} if {@code bytes} does not start with any of the three accepted formats' magic number. */
    private static String detectContentType(byte[] bytes) {
        if (startsWith(bytes, PDF_SIGNATURE)) return "application/pdf";
        if (startsWith(bytes, PNG_SIGNATURE)) return "image/png";
        if (startsWith(bytes, JPEG_SIGNATURE)) return "image/jpeg";
        return null;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data == null || data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
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
        if (v.getStatus() == VoucherStatus.REVERSED || v.getStatus() == VoucherStatus.VOID) {
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
        // Azure blob containers default to PublicAccessType.NONE, so a bare blob URL
        // is not fetchable there regardless — the "private/" prefix is added anyway
        // to keep the key shape identical to local-disk mode and not rely on that
        // default surviving future container-config changes.
        String blobPath = PRIVATE_PREFIX + "/vouchers/" + voucherId + "/" + fileName;
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
        // The "private/" segment is load-bearing: AssetController.serveAsset refuses
        // (404) any storage key whose first path segment is "private", so this file
        // can only ever be reached through VoucherController's authenticated
        // download endpoint (security ruling, Task 5 fix round 1).
        Path dir = Path.of(localStoragePath, PRIVATE_PREFIX, "vouchers", voucherId.toString());
        Files.createDirectories(dir);
        Files.copy(in, dir.resolve(fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return "/api/v1/assets/serve/" + PRIVATE_PREFIX + "/vouchers/" + voucherId + "/" + fileName;
    }

    private String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }
}
