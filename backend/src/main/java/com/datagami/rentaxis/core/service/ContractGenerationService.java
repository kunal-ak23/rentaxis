package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.LeaseDocumentDTO;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.DocumentType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.LeaseDocumentRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContractGenerationService {

    private final LeaseRepository leaseRepository;
    private final LeaseDocumentRepository leaseDocumentRepository;

    @Value("${rentaxis.contracts.storage-path:./data/contracts}")
    private String storagePath;

    @Transactional
    public LeaseDocumentDTO generateContract(UUID leaseId) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new com.datagami.rentaxis.api.exception.NotFoundException("Lease not found"));
        UUID tenantId = com.datagami.rentaxis.core.tenant.TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(lease.getTenantId())) {
            throw new com.datagami.rentaxis.api.exception.NotFoundException("Lease not found");
        }

        if (lease.getStatus() != LeaseStatus.DRAFT) {
            throw new com.datagami.rentaxis.api.exception.BusinessRuleViolationException("Contract can only be generated for DRAFT leases");
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

        // Create storage directory
        Path dirPath = Paths.get(storagePath);
        try {
            Files.createDirectories(dirPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create contracts directory", e);
        }

        // Generate PDF
        String fileName = contractNumber + "-" + System.currentTimeMillis() + ".pdf";
        Path filePath = dirPath.resolve(fileName);

        try (OutputStream os = new FileOutputStream(filePath.toFile())) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(os);
            builder.run();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PDF contract", e);
        }

        // Save document record
        LeaseDocument doc = new LeaseDocument();
        doc.setLease(lease);
        doc.setDocumentUrl(filePath.toString());
        doc.setType(DocumentType.CONTRACT);
        LeaseDocument savedDoc = leaseDocumentRepository.save(doc);

        // Transition lease to PENDING_SIGNATURE
        lease.setStatus(LeaseStatus.PENDING_SIGNATURE);
        leaseRepository.save(lease);

        log.info("Contract generated for lease {} at {}", leaseId, filePath);

        return mapToDTO(savedDoc);
    }

    @Transactional(readOnly = true)
    public List<LeaseDocumentDTO> getDocuments(UUID leaseId) {
        return leaseDocumentRepository.findByLeaseId(leaseId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public File getDocumentFile(UUID docId) {
        LeaseDocument doc = leaseDocumentRepository.findById(docId)
                .orElseThrow(() -> new com.datagami.rentaxis.api.exception.NotFoundException("Document not found"));
        File file = new File(doc.getDocumentUrl());
        if (!file.exists()) {
            throw new RuntimeException("Document file not found on disk");
        }
        return file;
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
