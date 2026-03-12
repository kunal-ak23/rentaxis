package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.LeaseDocumentDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.DocumentType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.LeaseDocumentRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ContractGenerationService {

    private final LeaseRepository leaseRepository;
    private final LeaseDocumentRepository leaseDocumentRepository;

    @Value("${rentaxis.contracts.storage-path:./data/contracts}")
    private String storagePath;

    @Value("${AZURE_STORAGE_CONNECTION_STRING:}")
    private String azureConnectionString;

    @Value("${AZURE_STORAGE_CONTAINER_PREFIX:tenant-}")
    private String containerPrefix;

    public ContractGenerationService(LeaseRepository leaseRepository,
                                     LeaseDocumentRepository leaseDocumentRepository) {
        this.leaseRepository = leaseRepository;
        this.leaseDocumentRepository = leaseDocumentRepository;
    }

    private boolean useAzureStorage() {
        return azureConnectionString != null && !azureConnectionString.isBlank();
    }

    @Transactional
    public LeaseDocumentDTO generateContract(UUID leaseId) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(lease.getTenantId())) {
            throw new NotFoundException("Lease not found");
        }

        if (lease.getStatus() != LeaseStatus.DRAFT) {
            throw new BusinessRuleViolationException("Contract can only be generated for DRAFT leases");
        }

        // Load template
        String template;
        try {
            ClassPathResource resource = new ClassPathResource("templates/contract-template.html");
            template = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load contract template", e);
        }

        // Populate template
        Unit unit = lease.getUnit();
        Property property = unit.getProperty();
        Renter renter = lease.getRenter();

        String contractNumber = "RA-" + lease.getId().toString().substring(0, 8).toUpperCase();

        String html = template
                .replace("{{CONTRACT_NUMBER}}", contractNumber)
                .replace("{{LANDLORD_NAME}}", property.getNameEn())
                .replace("{{RENTER_NAME_EN}}", renter.getNameEn())
                .replace("{{RENTER_NAME_AR}}", renter.getNameAr() != null ? renter.getNameAr() : "")
                .replace("{{RENTER_EMAIL}}", renter.getEmail() != null ? renter.getEmail() : "N/A")
                .replace("{{RENTER_PHONE}}", renter.getPhone() != null ? renter.getPhone() : "N/A")
                .replace("{{PROPERTY_NAME}}", property.getNameEn())
                .replace("{{UNIT_NUMBER}}", unit.getUnitNumber())
                .replace("{{EMIRATE}}", property.getEmirate() != null ? property.getEmirate().name().replace('_', ' ') : "")
                .replace("{{ADDRESS}}", property.getAddress() != null ? property.getAddress() : "")
                .replace("{{RENT_AMOUNT}}", lease.getRentAmount().toPlainString())
                .replace("{{DEPOSIT_AMOUNT}}", lease.getDepositAmount().toPlainString())
                .replace("{{PAYMENT_TERMS}}", String.valueOf(lease.getPaymentTerms() != null ? lease.getPaymentTerms() : 1))
                .replace("{{EJARI_NUMBER}}", lease.getEjariNumber() != null ? lease.getEjariNumber() : "N/A")
                .replace("{{START_DATE}}", lease.getStartDate().toString())
                .replace("{{END_DATE}}", lease.getEndDate().toString());

        // Generate PDF to byte array
        String fileName = contractNumber + "-" + System.currentTimeMillis() + ".pdf";
        byte[] pdfBytes = renderPdf(html);

        // Store PDF
        String documentUrl;
        if (useAzureStorage()) {
            documentUrl = uploadToAzure(lease.getTenantId(), fileName, pdfBytes);
        } else {
            documentUrl = saveToLocalDisk(fileName, pdfBytes);
        }

        // Save document record
        LeaseDocument doc = new LeaseDocument();
        doc.setLease(lease);
        doc.setDocumentUrl(documentUrl);
        doc.setType(DocumentType.CONTRACT);
        LeaseDocument savedDoc = leaseDocumentRepository.save(doc);

        // Transition lease to PENDING_SIGNATURE
        lease.setStatus(LeaseStatus.PENDING_SIGNATURE);
        leaseRepository.save(lease);

        log.info("Contract generated for lease {} at {}", leaseId, documentUrl);

        return mapToDTO(savedDoc);
    }

    private byte[] renderPdf(String html) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();

            // Register fonts for Arabic support
            try {
                ClassPathResource arabicFont = new ClassPathResource("fonts/NotoSansArabic.ttf");
                ClassPathResource latinFont = new ClassPathResource("fonts/NotoSans.ttf");
                builder.useFont(() -> {
                    try { return arabicFont.getInputStream(); } catch (IOException ex) { throw new UncheckedIOException(ex); }
                }, "Noto Sans Arabic");
                builder.useFont(() -> {
                    try { return latinFont.getInputStream(); } catch (IOException ex) { throw new UncheckedIOException(ex); }
                }, "Noto Sans");
            } catch (Exception e) {
                log.warn("Could not load custom fonts, Arabic text may not render: {}", e.getMessage());
            }

            builder.withHtmlContent(html, null);
            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PDF contract", e);
        }
    }

    private String uploadToAzure(UUID tenantId, String fileName, byte[] pdfBytes) {
        String containerName = containerPrefix + tenantId.toString();
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(azureConnectionString)
                .buildClient();

        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
        if (!containerClient.exists()) {
            containerClient.create();
        }

        String blobPath = "contracts/" + fileName;
        BlobClient blobClient = containerClient.getBlobClient(blobPath);
        blobClient.upload(new ByteArrayInputStream(pdfBytes), pdfBytes.length, true);

        String url = blobClient.getBlobUrl();
        log.info("Uploaded contract to Azure Blob: {}", url);
        return url;
    }

    private String saveToLocalDisk(String fileName, byte[] pdfBytes) {
        Path dirPath = Path.of(storagePath);
        try {
            Files.createDirectories(dirPath);
            Path filePath = dirPath.resolve(fileName);
            Files.write(filePath, pdfBytes);
            return filePath.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to save contract to disk", e);
        }
    }

    @Transactional(readOnly = true)
    public List<LeaseDocumentDTO> getDocuments(UUID leaseId) {
        return leaseDocumentRepository.findByLeaseId(leaseId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public byte[] getDocumentContent(UUID docId) {
        LeaseDocument doc = leaseDocumentRepository.findById(docId)
                .orElseThrow(() -> new NotFoundException("Document not found"));

        String url = doc.getDocumentUrl();

        // Azure Blob URL
        if (url.startsWith("https://") && url.contains(".blob.core.windows.net")) {
            return downloadFromAzure(url);
        }

        // Local file
        File file = new File(url);
        if (!file.exists()) {
            throw new RuntimeException("Document file not found");
        }
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read document file", e);
        }
    }

    /**
     * @deprecated Use getDocumentContent() instead. Kept for backward compatibility.
     */
    @Transactional(readOnly = true)
    public File getDocumentFile(UUID docId) {
        LeaseDocument doc = leaseDocumentRepository.findById(docId)
                .orElseThrow(() -> new NotFoundException("Document not found"));

        String url = doc.getDocumentUrl();

        // For Azure URLs, download to temp file
        if (url.startsWith("https://")) {
            byte[] content = downloadFromAzure(url);
            try {
                Path tempFile = Files.createTempFile("contract-", ".pdf");
                Files.write(tempFile, content);
                return tempFile.toFile();
            } catch (IOException e) {
                throw new RuntimeException("Failed to create temp file for document", e);
            }
        }

        File file = new File(url);
        if (!file.exists()) {
            throw new RuntimeException("Document file not found on disk");
        }
        return file;
    }

    private byte[] downloadFromAzure(String blobUrl) {
        BlobClient blobClient = new BlobServiceClientBuilder()
                .connectionString(azureConnectionString)
                .buildClient()
                .getBlobContainerClient(extractContainerName(blobUrl))
                .getBlobClient(extractBlobPath(blobUrl));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        blobClient.downloadStream(baos);
        return baos.toByteArray();
    }

    private String extractContainerName(String blobUrl) {
        // URL format: https://<account>.blob.core.windows.net/<container>/<path>
        String path = blobUrl.split(".blob.core.windows.net/")[1];
        return path.split("/")[0];
    }

    private String extractBlobPath(String blobUrl) {
        String path = blobUrl.split(".blob.core.windows.net/")[1];
        int firstSlash = path.indexOf('/');
        return path.substring(firstSlash + 1);
    }

    private LeaseDocumentDTO mapToDTO(LeaseDocument doc) {
        LeaseDocumentDTO dto = new LeaseDocumentDTO();
        dto.setId(doc.getId());
        dto.setLeaseId(doc.getLease().getId());
        dto.setDocumentUrl(doc.getDocumentUrl());
        dto.setType(doc.getType());
        return dto;
    }
}
