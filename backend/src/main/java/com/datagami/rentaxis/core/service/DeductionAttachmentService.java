package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.DeductionAttachmentDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LeaseSettlement;
import com.datagami.rentaxis.domain.entity.LeaseSettlementDeduction;
import com.datagami.rentaxis.domain.entity.SettlementDeductionAttachment;
import com.datagami.rentaxis.domain.entity.enums.SettlementStatus;
import com.datagami.rentaxis.domain.repository.LeaseSettlementDeductionRepository;
import com.datagami.rentaxis.domain.repository.LeaseSettlementRepository;
import com.datagami.rentaxis.domain.repository.SettlementDeductionAttachmentRepository;
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
public class DeductionAttachmentService {

    private final SettlementDeductionAttachmentRepository attachmentRepository;
    private final LeaseSettlementDeductionRepository deductionRepository;
    private final LeaseSettlementRepository settlementRepository;

    private static final int MAX_ATTACHMENTS_PER_DEDUCTION = 10;
    private static final long MAX_FILE_SIZE = 250L * 1024 * 1024; // 250MB

    @Value("${AZURE_STORAGE_CONNECTION_STRING:}")
    private String azureConnectionString;

    @Value("${AZURE_STORAGE_CONTAINER_PREFIX:tenant-}")
    private String containerPrefix;

    @Value("${rentaxis.assets.storage-path:./data/assets}")
    private String localStoragePath;

    @Transactional
    public DeductionAttachmentDTO uploadAttachment(UUID deductionId, String docName, MultipartFile file) throws IOException {
        LeaseSettlementDeduction deduction = deductionRepository.findById(deductionId)
                .orElseThrow(() -> new NotFoundException("Deduction not found"));

        UUID currentTenantId = TenantContextHolder.getTenantId();
        if (currentTenantId != null && !currentTenantId.equals(deduction.getTenantId())) {
            throw new NotFoundException("Deduction not found");
        }

        settlementRepository.findById(deduction.getSettlementId())
                .orElseThrow(() -> new NotFoundException("Settlement not found"));

        long count = attachmentRepository.countByDeductionId(deductionId);
        if (count >= MAX_ATTACHMENTS_PER_DEDUCTION) {
            throw new IllegalStateException("Maximum " + MAX_ATTACHMENTS_PER_DEDUCTION + " attachments per deduction");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalStateException("File size exceeds maximum of 250MB");
        }

        log.info("Uploading attachment for deduction {} - name: {}, size: {} bytes", deductionId, docName, file.getSize());
        byte[] bytes = file.getBytes();
        String ext = getExtension(file.getOriginalFilename());
        String fileName = UUID.randomUUID() + ext;

        String fileUrl;
        if (azureConnectionString != null && !azureConnectionString.isBlank()) {
            fileUrl = uploadToAzure(deductionId, fileName, bytes, file.getContentType());
        } else {
            fileUrl = saveToLocal(deductionId, fileName, bytes);
        }

        SettlementDeductionAttachment attachment = new SettlementDeductionAttachment();
        attachment.setDeductionId(deductionId);
        attachment.setName(docName);
        attachment.setFileUrl(fileUrl);
        attachment.setFileType(file.getContentType());
        attachment.setFileSize(file.getSize());
        attachment.setUploadedAt(Instant.now());

        return mapToDTO(attachmentRepository.save(attachment));
    }

    @Transactional(readOnly = true)
    public List<DeductionAttachmentDTO> getAttachments(UUID deductionId) {
        return attachmentRepository.findByDeductionIdOrderByUploadedAtAsc(deductionId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    public byte[] downloadAttachment(UUID attachmentId) {
        SettlementDeductionAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment not found"));

        String url = attachment.getFileUrl();
        if (url.startsWith("https://") && url.contains(".blob.core.windows.net")) {
            return downloadFromAzure(url);
        }

        try {
            return Files.readAllBytes(Path.of(localStoragePath).resolve(
                    url.replace("/api/v1/assets/serve/", "")));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read attachment file", e);
        }
    }

    @Transactional
    public void deleteAttachment(UUID attachmentId) {
        SettlementDeductionAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment not found"));

        UUID currentTenantId = TenantContextHolder.getTenantId();
        if (currentTenantId != null && !currentTenantId.equals(attachment.getTenantId())) {
            throw new NotFoundException("Attachment not found");
        }

        LeaseSettlementDeduction deduction = deductionRepository.findById(attachment.getDeductionId())
                .orElseThrow(() -> new NotFoundException("Deduction not found"));
        LeaseSettlement settlement = settlementRepository.findById(deduction.getSettlementId())
                .orElseThrow(() -> new NotFoundException("Settlement not found"));

        if (settlement.getStatus() == SettlementStatus.FINALIZED) {
            throw new IllegalStateException("Cannot delete attachments from a finalized settlement");
        }

        attachmentRepository.delete(attachment);
    }

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

    private String uploadToAzure(UUID deductionId, String fileName, byte[] bytes, String contentType) {
        UUID tenantId = TenantContextHolder.getTenantId();
        String containerName = tenantId != null ? containerPrefix + tenantId : "shared";

        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(azureConnectionString)
                .buildClient();

        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
        if (!containerClient.exists()) {
            containerClient.create();
        }

        String blobPath = "settlement-deductions/" + deductionId + "/" + fileName;
        BlobClient blobClient = containerClient.getBlobClient(blobPath);
        blobClient.upload(new ByteArrayInputStream(bytes), bytes.length, true);

        return blobServiceClient.getAccountUrl() + "/" + containerName + "/" + blobPath;
    }

    private String saveToLocal(UUID deductionId, String fileName, byte[] bytes) throws IOException {
        Path dirPath = Path.of(localStoragePath, "settlement-deductions", deductionId.toString());
        Files.createDirectories(dirPath);
        Path filePath = dirPath.resolve(fileName);
        Files.write(filePath, bytes);
        return "/api/v1/assets/serve/settlement-deductions/" + deductionId + "/" + fileName;
    }

    private String getExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    private DeductionAttachmentDTO mapToDTO(SettlementDeductionAttachment a) {
        DeductionAttachmentDTO dto = new DeductionAttachmentDTO();
        dto.setId(a.getId());
        dto.setDeductionId(a.getDeductionId());
        dto.setName(a.getName());
        dto.setFileUrl(a.getFileUrl());
        dto.setFileType(a.getFileType());
        dto.setFileSize(a.getFileSize());
        dto.setUploadedAt(a.getUploadedAt());
        return dto;
    }
}
