package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.LeaseAttachmentDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.core.util.AttachmentNames;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseAttachment;
import com.datagami.rentaxis.domain.repository.LeaseAttachmentRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LeaseAttachmentService {

    private final LeaseAttachmentRepository attachmentRepository;
    private final LeaseRepository leaseRepository;
    private final LeaseAccessPolicy leaseAccessPolicy;

    @Value("${AZURE_STORAGE_CONNECTION_STRING:}")
    private String azureConnectionString;

    @Value("${AZURE_STORAGE_CONTAINER_PREFIX:tenant-}")
    private String containerPrefix;

    @Value("${rentaxis.assets.storage-path:./data/assets}")
    private String localStoragePath;

    @Transactional
    public LeaseAttachmentDTO uploadAttachment(UUID leaseId, String docName, MultipartFile file) throws IOException {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        // Upload is a write: a manager only on their buildings (audit P1-4).
        leaseAccessPolicy.requireManageable(lease);

        byte[] bytes = file.getBytes();
        // The blob name keeps the original file's extension (or the one its type names),
        // so the download can add it to the name the user typed ("aadhar" → "aadhar.pdf").
        String contentType = AttachmentNames.uploadContentType(file.getOriginalFilename(), file.getContentType());
        String ext = AttachmentNames.uploadExtension(file.getOriginalFilename(), contentType);
        String fileName = UUID.randomUUID().toString().substring(0, 8) + ext;

        String fileUrl;
        if (azureConnectionString != null && !azureConnectionString.isBlank()) {
            fileUrl = uploadToAzure(fileName, bytes, contentType);
        } else {
            fileUrl = saveToLocal(fileName, bytes);
        }

        LeaseAttachment attachment = new LeaseAttachment();
        attachment.setLease(lease);
        attachment.setName(docName);
        attachment.setFileUrl(fileUrl);
        attachment.setFileType(contentType);
        attachment.setFileSize(file.getSize());
        attachment.setUploadedAt(Instant.now());

        return mapToDTO(attachmentRepository.save(attachment));
    }

    @Transactional(readOnly = true)
    public List<LeaseAttachmentDTO> getAttachments(UUID leaseId) {
        // The role gate on this endpoint includes RENTER, and nothing below it
        // asked whose lease this is — so any renter holding any lease id could
        // list that lease's contracts, ID scans and cheque images.
        var lease = leaseRepository.findById(leaseId).orElse(null);
        leaseAccessPolicy.requireReadable(lease);
        // PR #359 R1: after an assignment each renter sees their own side of the date.
        var window = leaseAccessPolicy.renterWindow(lease);

        return attachmentRepository.findByLeaseId(leaseId).stream()
                .filter(a -> window == null || !window.bounded() || window.contains(a.getUploadedAt() == null ? null
                        : a.getUploadedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate()))
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public byte[] downloadAttachment(UUID attachmentId) {
        LeaseAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment not found"));

        // Guarding the list alone would be pointless: the download takes an
        // attachment id directly, so it is reachable without ever listing.
        leaseAccessPolicy.requireReadable(attachment.getLease());
        leaseAccessPolicy.requireInRenterWindow(attachment.getLease(), attachment.getUploadedAt());

        String url = attachment.getFileUrl();

        if (url.startsWith("https://") && url.contains(".blob.core.windows.net")) {
            return downloadFromAzure(url);
        }

        try {
            return Files.readAllBytes(Path.of(localStoragePath).resolve(url.replace("/api/v1/assets/serve/", "")));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read attachment file", e);
        }
    }

    /**
     * The attachment's bytes with the name to save them as — the display name plus the
     * original file's extension — and the Content-Type to serve. Same access checks as
     * {@link #downloadAttachment}.
     */
    @Transactional(readOnly = true)
    public AttachmentNames.Download download(UUID attachmentId) {
        byte[] content = downloadAttachment(attachmentId);
        LeaseAttachment a = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment not found"));
        return AttachmentNames.download(content, a.getName(), a.getFileUrl(), a.getFileType(), "document");
    }

    @Transactional
    public void deleteAttachment(UUID attachmentId) {
        LeaseAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment not found"));
        if (!leaseAccessPolicy.canManage(attachment.getLease())) {
            throw new NotFoundException("Attachment not found");
        }
        attachmentRepository.delete(attachment);
    }

    // Reuse the existing download helper pattern
    private byte[] downloadFromAzure(String blobUrl) {
        String marker = ".blob.core.windows.net/";
        int idx = blobUrl.indexOf(marker);
        String path = blobUrl.substring(idx + marker.length());
        int slash = path.indexOf('/');
        String container = path.substring(0, slash);
        String blobPath = path.substring(slash + 1);

        BlobClient blobClient = new BlobServiceClientBuilder()
                .connectionString(azureConnectionString)
                .buildClient()
                .getBlobContainerClient(container)
                .getBlobClient(blobPath);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        blobClient.downloadStream(baos);
        return baos.toByteArray();
    }

    private String uploadToAzure(String fileName, byte[] bytes, String contentType) {
        UUID tenantId = TenantContextHolder.getTenantId();
        String containerName = tenantId != null ? containerPrefix + tenantId : "shared";

        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(azureConnectionString)
                .buildClient();

        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
        if (!containerClient.exists()) {
            containerClient.create();
        }

        String blobPath = "lease-docs/" + fileName;
        BlobClient blobClient = containerClient.getBlobClient(blobPath);
        blobClient.upload(new ByteArrayInputStream(bytes), bytes.length, true);

        return blobServiceClient.getAccountUrl() + "/" + containerName + "/" + blobPath;
    }

    private String saveToLocal(String fileName, byte[] bytes) throws IOException {
        Path dirPath = Path.of(localStoragePath, "lease-docs");
        Files.createDirectories(dirPath);
        Path filePath = dirPath.resolve(fileName);
        Files.write(filePath, bytes);
        return "/api/v1/assets/serve/lease-docs/" + fileName;
    }

    private LeaseAttachmentDTO mapToDTO(LeaseAttachment a) {
        LeaseAttachmentDTO dto = new LeaseAttachmentDTO();
        dto.setId(a.getId());
        dto.setLeaseId(a.getLease().getId());
        dto.setName(a.getName());
        dto.setDownloadName(AttachmentNames.downloadName(a.getName(), a.getFileUrl(), a.getFileType(), null, "document"));
        dto.setFileUrl(a.getFileUrl());
        dto.setFileType(a.getFileType());
        dto.setFileSize(a.getFileSize());
        dto.setUploadedAt(a.getUploadedAt());
        return dto;
    }
}
