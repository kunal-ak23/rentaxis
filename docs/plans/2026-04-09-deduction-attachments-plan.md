# Deduction Attachments & Draft Settlement Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add file attachments (images/videos/PDFs) to individual settlement deductions and introduce a DRAFT→FINALIZED settlement lifecycle.

**Architecture:** New `SettlementDeductionAttachment` entity with dedicated service/controller, following the existing `LeaseAttachment` pattern. `LeaseSettlement` gains a `status` field (DRAFT/FINALIZED). Web gets a full settlement page; mobile admin gets full settlement editing with camera/gallery/file upload.

**Tech Stack:** Java 21 + Spring Boot + Liquibase + Azure Blob Storage (backend), Next.js + TypeScript + Tailwind (web), Flutter + Riverpod + Dio (mobile)

---

## Task 1: Liquibase Migration — `settlement_deduction_attachments` table + `status` column

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/39-settlement-deduction-attachments.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml:85` (add include)

**Step 1: Create migration file**

```yaml
databaseChangeLog:
  - changeSet:
      id: 39-add-settlement-status
      author: rentaxis
      changes:
        - addColumn:
            tableName: lease_settlements
            columns:
              - column:
                  name: status
                  type: varchar(20)
                  defaultValue: FINALIZED
                  constraints:
                    nullable: false
      comment: "Add status column to lease_settlements. Default FINALIZED for existing rows."

  - changeSet:
      id: 39-create-settlement-deduction-attachments
      author: rentaxis
      changes:
        - createTable:
            tableName: settlement_deduction_attachments
            columns:
              - column:
                  name: id
                  type: uuid
                  defaultValueComputed: gen_random_uuid()
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: deduction_id
                  type: uuid
                  constraints:
                    nullable: false
                    foreignKeyName: fk_sda_deduction
                    references: lease_settlement_deductions(id)
              - column:
                  name: tenant_id
                  type: uuid
                  constraints:
                    nullable: false
              - column:
                  name: name
                  type: varchar(255)
                  constraints:
                    nullable: false
              - column:
                  name: file_url
                  type: varchar(1024)
                  constraints:
                    nullable: false
              - column:
                  name: file_type
                  type: varchar(100)
              - column:
                  name: file_size
                  type: bigint
              - column:
                  name: uploaded_at
                  type: timestamp
                  defaultValueComputed: now()
        - createIndex:
            tableName: settlement_deduction_attachments
            indexName: idx_sda_deduction_id
            columns:
              - column:
                  name: deduction_id
        - createIndex:
            tableName: settlement_deduction_attachments
            indexName: idx_sda_tenant_id
            columns:
              - column:
                  name: tenant_id
      comment: "Create settlement_deduction_attachments table for evidence files per deduction."
```

**Step 2: Add include to changelog master**

Add at line 86 of `db.changelog-master.yaml`:
```yaml
  - include:
      file: db/changelog/changesets/39-settlement-deduction-attachments.yaml
```

**Step 3: Verify migration runs**

Run: `cd backend && ./gradlew bootRun` (or just verify Liquibase changelog parsing)
Expected: Tables created, `status` column added to `lease_settlements` with default `FINALIZED`.

**Step 4: Commit**

```bash
git add backend/src/main/resources/db/changelog/changesets/39-settlement-deduction-attachments.yaml \
       backend/src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "feat: add migration for settlement deduction attachments and settlement status"
```

---

## Task 2: Backend — `SettlementStatus` enum + update `LeaseSettlement` entity

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/SettlementStatus.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/LeaseSettlement.java`

**Step 1: Create SettlementStatus enum**

```java
package com.datagami.rentaxis.domain.entity.enums;

public enum SettlementStatus {
    DRAFT,
    FINALIZED
}
```

**Step 2: Add status field to LeaseSettlement**

Add to `LeaseSettlement.java` after the `settledAt` field:

```java
@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 20)
private SettlementStatus status = SettlementStatus.DRAFT;
```

Import: `com.datagami.rentaxis.domain.entity.enums.SettlementStatus`

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/SettlementStatus.java \
       backend/src/main/java/com/datagami/rentaxis/domain/entity/LeaseSettlement.java
git commit -m "feat: add SettlementStatus enum and status field to LeaseSettlement"
```

---

## Task 3: Backend — `SettlementDeductionAttachment` entity + repository

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/SettlementDeductionAttachment.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/SettlementDeductionAttachmentRepository.java`

**Step 1: Create entity**

```java
package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "settlement_deduction_attachments")
@Getter
@Setter
public class SettlementDeductionAttachment extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "deduction_id", nullable = false)
    private UUID deductionId;

    @Column(nullable = false)
    private String name;

    @Column(name = "file_url", nullable = false, length = 1024)
    private String fileUrl;

    @Column(name = "file_type", length = 100)
    private String fileType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "uploaded_at")
    private Instant uploadedAt = Instant.now();
}
```

**Step 2: Create repository**

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.SettlementDeductionAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SettlementDeductionAttachmentRepository extends JpaRepository<SettlementDeductionAttachment, UUID> {
    List<SettlementDeductionAttachment> findByDeductionIdOrderByUploadedAtAsc(UUID deductionId);
    long countByDeductionId(UUID deductionId);
}
```

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/SettlementDeductionAttachment.java \
       backend/src/main/java/com/datagami/rentaxis/domain/repository/SettlementDeductionAttachmentRepository.java
git commit -m "feat: add SettlementDeductionAttachment entity and repository"
```

---

## Task 4: Backend — DTOs for deduction attachments and draft settlement

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/DeductionAttachmentDTO.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/SaveSettlementDTO.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/dto/SettlementResponseDTO.java`

**Step 1: Create DeductionAttachmentDTO**

```java
package com.datagami.rentaxis.api.dto;

import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data
public class DeductionAttachmentDTO {
    private UUID id;
    private UUID deductionId;
    private String name;
    private String fileUrl;
    private String fileType;
    private Long fileSize;
    private Instant uploadedAt;
}
```

**Step 2: Create SaveSettlementDTO**

```java
package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.DeductionCategory;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class SaveSettlementDTO {
    private String notes;
    private List<DeductionItemDTO> deductions;

    @Getter
    @Setter
    public static class DeductionItemDTO {
        @NotNull
        private DeductionCategory category;
        private String description;
        @NotNull
        private BigDecimal amount;
        private boolean autoCalculated;
    }
}
```

**Step 3: Update SettlementResponseDTO**

Replace the entire `SettlementResponseDTO.java` content. The key change is adding `status` to settlement data and `attachments` list to each deduction:

```java
package com.datagami.rentaxis.api.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class SettlementResponseDTO {
    private UUID id;
    private UUID leaseId;
    private BigDecimal depositAmount;
    private BigDecimal totalDeductions;
    private BigDecimal refundAmount;
    private String notes;
    private String status;
    private UUID settledBy;
    private String settledByName;
    private LocalDateTime settledAt;
    private LocalDateTime createdAt;
    private List<DeductionDTO> deductions;

    @Getter
    @Setter
    public static class DeductionDTO {
        private UUID id;
        private String category;
        private String description;
        private BigDecimal amount;
        private boolean autoCalculated;
        private List<DeductionAttachmentDTO> attachments;
    }
}
```

**Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/dto/DeductionAttachmentDTO.java \
       backend/src/main/java/com/datagami/rentaxis/api/dto/SaveSettlementDTO.java \
       backend/src/main/java/com/datagami/rentaxis/api/dto/SettlementResponseDTO.java
git commit -m "feat: add DTOs for deduction attachments and draft settlement"
```

---

## Task 5: Backend — `DeductionAttachmentService`

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/DeductionAttachmentService.java`

**Step 1: Create service**

Follow the exact pattern of `LeaseAttachmentService` but scoped to deductions. Blob path: `settlement-deductions/{tenantId}/{deductionId}/{uuid}.{ext}`.

```java
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

        LeaseSettlement settlement = settlementRepository.findById(deduction.getSettlementId())
                .orElseThrow(() -> new NotFoundException("Settlement not found"));

        // Check attachment count limit
        long count = attachmentRepository.countByDeductionId(deductionId);
        if (count >= MAX_ATTACHMENTS_PER_DEDUCTION) {
            throw new IllegalStateException("Maximum " + MAX_ATTACHMENTS_PER_DEDUCTION + " attachments per deduction");
        }

        // Check file size
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalStateException("File size exceeds maximum of 250MB");
        }

        byte[] bytes = file.getBytes();
        String ext = getExtension(file.getOriginalFilename());
        String fileName = UUID.randomUUID().toString().substring(0, 8) + ext;

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

    @Transactional(readOnly = true)
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

        // Check that settlement is still DRAFT
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
```

**Step 2: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/DeductionAttachmentService.java
git commit -m "feat: add DeductionAttachmentService with Azure Blob and local storage"
```

---

## Task 6: Backend — `DeductionAttachmentController`

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/DeductionAttachmentController.java`

**Step 1: Create controller**

```java
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
```

**Step 2: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/DeductionAttachmentController.java
git commit -m "feat: add DeductionAttachmentController for deduction file uploads"
```

---

## Task 7: Backend — Update `SettlementService` with draft/finalize flow

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/SettlementService.java`

**Step 1: Add `saveDraft` method**

Add these methods to `SettlementService`:

```java
@Transactional
public LeaseSettlement saveDraft(UUID leaseId, SaveSettlementDTO dto, UUID userId) {
    Lease lease = findLeaseWithTenantCheck(leaseId);
    BigDecimal depositAmount = lease.getDepositAmount() != null ? lease.getDepositAmount() : BigDecimal.ZERO;

    // Find existing draft or create new
    Optional<LeaseSettlement> existingOpt = leaseSettlementRepository.findByLeaseId(leaseId);
    LeaseSettlement settlement;

    if (existingOpt.isPresent()) {
        settlement = existingOpt.get();
        if (settlement.getStatus() == SettlementStatus.FINALIZED) {
            throw new IllegalStateException("Settlement is already finalized");
        }
        // Delete existing deductions (will be replaced)
        List<LeaseSettlementDeduction> oldDeductions =
                leaseSettlementDeductionRepository.findBySettlementIdOrderByCreatedAtAsc(settlement.getId());
        leaseSettlementDeductionRepository.deleteAll(oldDeductions);
    } else {
        settlement = new LeaseSettlement();
        settlement.setLeaseId(leaseId);
        settlement.setStatus(SettlementStatus.DRAFT);
    }

    settlement.setDepositAmount(depositAmount);
    settlement.setNotes(dto.getNotes());

    BigDecimal totalDeductions = BigDecimal.ZERO;
    if (dto.getDeductions() != null) {
        totalDeductions = dto.getDeductions().stream()
                .map(SaveSettlementDTO.DeductionItemDTO::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    settlement.setTotalDeductions(totalDeductions);
    settlement.setRefundAmount(depositAmount.subtract(totalDeductions));

    LeaseSettlement savedSettlement = leaseSettlementRepository.save(settlement);

    if (dto.getDeductions() != null) {
        for (SaveSettlementDTO.DeductionItemDTO item : dto.getDeductions()) {
            LeaseSettlementDeduction deduction = new LeaseSettlementDeduction();
            deduction.setSettlementId(savedSettlement.getId());
            deduction.setCategory(item.getCategory());
            deduction.setDescription(item.getDescription());
            deduction.setAmount(item.getAmount());
            deduction.setAutoCalculated(item.isAutoCalculated());
            leaseSettlementDeductionRepository.save(deduction);
        }
    }

    return savedSettlement;
}

@Transactional
public LeaseSettlement finalizeSettlement(UUID leaseId, UUID settledBy) {
    LeaseSettlement settlement = leaseSettlementRepository.findByLeaseId(leaseId)
            .orElseThrow(() -> new NotFoundException("No settlement found for this lease"));

    if (settlement.getStatus() == SettlementStatus.FINALIZED) {
        throw new IllegalStateException("Settlement is already finalized");
    }

    settlement.setStatus(SettlementStatus.FINALIZED);
    settlement.setSettledBy(settledBy);
    settlement.setSettledAt(LocalDateTime.now());

    return leaseSettlementRepository.save(settlement);
}
```

Add imports at top:
```java
import com.datagami.rentaxis.api.dto.SaveSettlementDTO;
import com.datagami.rentaxis.domain.entity.enums.SettlementStatus;
```

**Step 2: Update `createSettlement` to set status FINALIZED**

In the existing `createSettlement` method, add after `settlement.setSettledAt(LocalDateTime.now());`:
```java
settlement.setStatus(SettlementStatus.FINALIZED);
```

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/SettlementService.java
git commit -m "feat: add saveDraft and finalizeSettlement methods to SettlementService"
```

---

## Task 8: Backend — Update `LeaseController` with draft/finalize endpoints + response enrichment

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/LeaseController.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/SettlementService.java` (add response builder)

**Step 1: Add a response builder method to `SettlementService`**

```java
public SettlementResponseDTO buildSettlementResponse(UUID leaseId) {
    LeaseSettlement settlement = leaseSettlementRepository.findByLeaseId(leaseId)
            .orElseThrow(() -> new NotFoundException("Settlement not found"));

    List<LeaseSettlementDeduction> deductions =
            leaseSettlementDeductionRepository.findBySettlementIdOrderByCreatedAtAsc(settlement.getId());

    SettlementResponseDTO response = new SettlementResponseDTO();
    response.setId(settlement.getId());
    response.setLeaseId(settlement.getLeaseId());
    response.setDepositAmount(settlement.getDepositAmount());
    response.setTotalDeductions(settlement.getTotalDeductions());
    response.setRefundAmount(settlement.getRefundAmount());
    response.setNotes(settlement.getNotes());
    response.setStatus(settlement.getStatus().name());
    response.setSettledBy(settlement.getSettledBy());
    response.setSettledAt(settlement.getSettledAt());
    response.setCreatedAt(settlement.getCreatedAt());

    List<SettlementResponseDTO.DeductionDTO> deductionDTOs = deductions.stream().map(d -> {
        SettlementResponseDTO.DeductionDTO dto = new SettlementResponseDTO.DeductionDTO();
        dto.setId(d.getId());
        dto.setCategory(d.getCategory().name());
        dto.setDescription(d.getDescription());
        dto.setAmount(d.getAmount());
        dto.setAutoCalculated(d.isAutoCalculated());
        dto.setAttachments(deductionAttachmentService.getAttachments(d.getId()));
        return dto;
    }).collect(Collectors.toList());

    response.setDeductions(deductionDTOs);
    return response;
}
```

Inject `DeductionAttachmentService` into `SettlementService`:
```java
private final DeductionAttachmentService deductionAttachmentService;
```

Add import: `import java.util.stream.Collectors;`

**Step 2: Add new endpoints to `LeaseController`**

```java
@PostMapping("/{id}/settlement/draft")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
public ResponseEntity<SettlementResponseDTO> saveSettlementDraft(
        @PathVariable UUID id,
        @RequestBody SaveSettlementDTO dto,
        HttpServletRequest request) {
    String userIdStr = request.getHeader("X-User-Id");
    UUID userId = userIdStr != null ? UUID.fromString(userIdStr) : null;
    settlementService.saveDraft(id, dto, userId);
    return ResponseEntity.ok(settlementService.buildSettlementResponse(id));
}

@PostMapping("/{id}/settlement/finalize")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
public ResponseEntity<LeaseDTO> finalizeSettlement(
        @PathVariable UUID id,
        HttpServletRequest request) {
    String userIdStr = request.getHeader("X-User-Id");
    UUID settledBy = userIdStr != null ? UUID.fromString(userIdStr) : null;
    settlementService.finalizeSettlement(id, settledBy);
    // Terminate the lease
    return ResponseEntity.ok(leaseService.terminateLease(id, null));
}
```

Add import: `import com.datagami.rentaxis.api.dto.SaveSettlementDTO;`

**Step 3: Update existing GET settlement endpoint to use new response builder**

Replace the existing `getSettlement` method body:

```java
@GetMapping("/{id}/settlement")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
public ResponseEntity<SettlementResponseDTO> getSettlement(@PathVariable UUID id) {
    return settlementService.getSettlement(id)
            .map(settlement -> ResponseEntity.ok(settlementService.buildSettlementResponse(id)))
            .orElse(ResponseEntity.notFound().build());
}
```

**Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/LeaseController.java \
       backend/src/main/java/com/datagami/rentaxis/core/service/SettlementService.java
git commit -m "feat: add draft/finalize settlement endpoints with attachment-enriched responses"
```

---

## Task 9: Backend — Configure multipart max file size

**Files:**
- Modify: `backend/src/main/resources/application.properties` (or `application.yml`)

**Step 1: Add multipart config**

Check which config format is used, then add:

```properties
spring.servlet.multipart.max-file-size=250MB
spring.servlet.multipart.max-request-size=260MB
```

**Step 2: Commit**

```bash
git add backend/src/main/resources/application.properties
git commit -m "feat: increase multipart upload limit to 250MB for deduction attachments"
```

---

## Task 10: Web — Create settlement page with draft flow

**Files:**
- Create: `web/src/app/[locale]/dashboard/leases/[id]/settlement/page.tsx`
- Modify: `web/src/app/[locale]/dashboard/leases/[id]/page.tsx` (replace modal with navigation link)

**Step 1: Create the settlement page**

This is a full page at `/dashboard/leases/[id]/settlement`. Key sections:

1. **Header** — Lease info + status badge (DRAFT / FINALIZED)
2. **Auto-calculated deductions** — loaded from preview, editable amounts
3. **Manual deductions** — add/remove cards, each with category/amount/description + attachment area
4. **Summary** — total deductions, refund amount
5. **Notes** — textarea
6. **Actions** — Save Draft, Finalize & Terminate

The page should:
- On mount: fetch `GET /api/proxy/v1/leases/{leaseId}/settlement` to check for existing settlement
- If no settlement exists: fetch `GET /api/proxy/v1/leases/{leaseId}/settlement/preview` for auto-deduction data
- If DRAFT settlement exists: load its deductions and attachments for editing
- If FINALIZED: show read-only view with option to add attachments

**Attachment upload per deduction:**
- Each manual deduction card has an attachment section
- Shows existing attachments as a list with name, size, delete button
- "Add Files" button triggers a hidden file input (accept: image/*, video/*, .pdf)
- Upload calls `POST /api/upload?path=/api/v1/settlements/deductions/{deductionId}/attachments` with FormData
- After upload, refreshes the attachment list for that deduction
- Shows "X/10 attachments" counter
- Image thumbnails shown via `fileUrl`; video/PDF shows icon + name

**Draft save:**
- Calls `POST /api/proxy/v1/leases/{leaseId}/settlement/draft` with `SaveSettlementDTO` body
- Response returns updated settlement with deduction IDs (needed for attachment uploads)
- After first save, deductions get server-assigned IDs — enable attachment uploads

**Finalize:**
- Confirmation dialog
- Calls `POST /api/proxy/v1/leases/{leaseId}/settlement/finalize`
- On success, navigates back to lease detail page and refreshes

**Key implementation notes:**
- The settlement must be saved as draft BEFORE attachments can be uploaded (deductions need server IDs)
- Show a hint: "Save draft first to enable file attachments"
- Use the existing upload route at `web/src/app/api/upload/route.ts` — it already handles auth headers and multipart forwarding
- Follow existing Tailwind class patterns from the lease detail page
- Use lucide-react icons: `Paperclip`, `Upload`, `Trash2`, `Image`, `Video`, `FileText`

**Step 2: Update lease detail page**

In `web/src/app/[locale]/dashboard/leases/[id]/page.tsx`:
- Remove the settlement modal code (the `showSettlementModal` state and modal JSX)
- Replace the "Terminate & Settle" button with a link/button that navigates to `/dashboard/leases/{id}/settlement`
- Keep the existing settlement summary display for TERMINATED/CLOSED leases, but add attachment display per deduction
- The settlement summary should now show attachments for each deduction (thumbnails/links)

**Step 3: Commit**

```bash
git add web/src/app/[locale]/dashboard/leases/[id]/settlement/page.tsx \
       web/src/app/[locale]/dashboard/leases/[id]/page.tsx
git commit -m "feat: add settlement page with draft flow and deduction attachments (web)"
```

---

## Task 11: Web — Update settlement summary display with attachments

**Files:**
- Modify: `web/src/app/[locale]/dashboard/leases/[id]/page.tsx`

**Step 1: Update the settlement summary section**

In the existing settlement summary (shown for TERMINATED/CLOSED leases), update the deduction display to include attachments:

- Under each deduction row, if `deduction.attachments?.length > 0`, show a collapsible attachment list
- Each attachment: small thumbnail (for images), icon (for video/PDF), name, size, download button
- For images: clicking opens a lightbox/modal preview
- Update the `fetchSettlement` function to use the new `SettlementResponseDTO` shape (which now includes `deductions[].attachments[]`)
- Update the `Settlement` TypeScript type to match:

```typescript
type Settlement = {
    id: string;
    leaseId: string;
    depositAmount: number;
    totalDeductions: number;
    refundAmount: number;
    notes: string;
    status: string; // NEW
    settledBy: string;
    settledByName?: string;
    settledAt: string;
    createdAt: string; // NEW
    deductions: {
        id: string; // NEW
        category: string;
        description: string;
        amount: number;
        autoCalculated: boolean; // NEW
        attachments: { // NEW
            id: string;
            name: string;
            fileUrl: string;
            fileType: string;
            fileSize: number;
            uploadedAt: string;
        }[];
    }[];
};
```

**Step 2: Add "Add Attachments" capability on finalized settlement**

For FINALIZED settlements, show an "Add Evidence" button on each deduction that allows uploading additional attachments (but no delete).

**Step 3: Commit**

```bash
git add web/src/app/[locale]/dashboard/leases/[id]/page.tsx
git commit -m "feat: display deduction attachments in settlement summary and allow post-finalization uploads"
```

---

## Task 12: Web — Update Next.js upload route for large files

**Files:**
- Modify: `web/src/app/api/upload/route.ts`

**Step 1: Increase body size limit**

Add to the route file:
```typescript
export const maxDuration = 300; // 5 minutes for large video uploads
```

Also check if Next.js config needs `experimental.serverActions.bodySizeLimit` or similar for the proxy route. The existing `/api/proxy/` rewrite may need adjustment for large multipart uploads — if so, consider streaming the upload directly through the proxy rewrite rather than the upload route.

**Step 2: Commit**

```bash
git add web/src/app/api/upload/route.ts
git commit -m "feat: increase upload timeout for large deduction attachment files"
```

---

## Task 13: Mobile — Update `SettlementService` with draft/finalize/attachment methods

**Files:**
- Modify: `mobile/packages/rentaxis_core/lib/api/services/settlement_service.dart`

**Step 1: Add new methods**

```dart
Future<Map<String, dynamic>> saveDraft(
    String leaseId, Map<String, dynamic> data) async {
  final response = await _dio.post(
    '/v1/leases/$leaseId/settlement/draft',
    data: data,
  );
  return response.data;
}

Future<Map<String, dynamic>> finalizeSettlement(String leaseId) async {
  final response = await _dio.post('/v1/leases/$leaseId/settlement/finalize');
  return response.data;
}

Future<Map<String, dynamic>> uploadDeductionAttachment(
    String deductionId, String filePath, String name) async {
  final formData = FormData.fromMap({
    'file': await MultipartFile.fromFile(filePath),
    'name': name,
  });
  final response = await _dio.post(
    '/v1/settlements/deductions/$deductionId/attachments',
    data: formData,
  );
  return response.data;
}

Future<List<dynamic>> getDeductionAttachments(String deductionId) async {
  final response = await _dio.get(
    '/v1/settlements/deductions/$deductionId/attachments',
  );
  return response.data;
}

Future<List<int>> downloadDeductionAttachment(String attachmentId) async {
  final response = await _dio.get(
    '/v1/settlements/attachments/$attachmentId/download',
    options: Options(responseType: ResponseType.bytes),
  );
  return response.data;
}

Future<void> deleteDeductionAttachment(String attachmentId) async {
  await _dio.delete('/v1/settlements/attachments/$attachmentId');
}
```

**Step 2: Commit**

```bash
git add mobile/packages/rentaxis_core/lib/api/services/settlement_service.dart
git commit -m "feat: add draft/finalize/attachment methods to mobile SettlementService"
```

---

## Task 14: Mobile — Rewrite `LeaseSettlementScreen` with full settlement management

**Files:**
- Modify: `mobile/apps/manager/lib/screens/lease_settlement_screen.dart`

**Step 1: Rewrite the screen**

Replace the current read-only screen with a full settlement editor. The screen should handle three modes:

**Mode 1 — No settlement exists (lease is ACTIVE):**
- Fetch settlement preview
- Show preview summary card (deposit, auto-deductions)
- "Start Settlement Draft" button → creates draft via `saveDraft()` then switches to Mode 2

**Mode 2 — DRAFT settlement:**
- Editable settlement form:
  - Auto-calculated deductions section (editable amounts)
  - Manual deductions section with add/remove
  - Each deduction is a Card with:
    - Category dropdown (DropdownButtonFormField)
    - Amount text field (TextFormField with number keyboard)
    - Description text field
    - Attachment section (see below)
  - Notes text field
  - Summary: total deductions, refund amount
- Action buttons: "Save Draft" and "Finalize & Terminate"
- Save Draft: calls `saveDraft()`, shows SnackBar on success
- Finalize: shows confirmation dialog, calls `finalizeSettlement()`, navigates back

**Mode 3 — FINALIZED settlement:**
- Read-only display of all settlement data
- Each deduction shows its attachments
- Can still add attachments to deductions (tap to add)
- Cannot delete attachments or edit amounts

**Attachment UI per deduction:**
- Grid of thumbnails (2 columns) below each deduction card
- Counter: "3/10 attachments"
- "Add" button (camera icon) opens bottom sheet with Camera / Gallery / File options
- Uses `image_picker` for camera/gallery, `file_picker` for documents/videos
- Upload calls `uploadDeductionAttachment()` on the `SettlementService`
- Delete: long-press → confirm dialog (DRAFT only)
- Tap: opens full-screen preview (use `Image.network` for images, `video_player` for videos, `open_filex` for PDFs)

**Key patterns to follow:**
- `ConsumerStatefulWidget` with `ConsumerState`
- Provider for `SettlementService` via `apiClientProvider`
- `AppColors` for theming, `Formatters.currency()` for amounts
- `LoadingOverlay` for loading states
- `StatusBadge` for DRAFT/FINALIZED badges
- Gradient summary card pattern from current screen

**Step 2: Commit**

```bash
git add mobile/apps/manager/lib/screens/lease_settlement_screen.dart
git commit -m "feat: rewrite LeaseSettlementScreen with draft flow and deduction attachments"
```

---

## Task 15: Mobile — Update `LeaseDetailScreen` to navigate to settlement

**Files:**
- Modify: `mobile/apps/manager/lib/screens/lease_detail_screen.dart`

**Step 1: Update termination flow**

- Remove the existing simple terminate dialog (`_terminateLease` method with just notes)
- Replace the "Terminate Lease" popup menu item to navigate to the settlement screen:
  ```dart
  context.push('/leases/${widget.leaseId}/settlement');
  ```
- The settlement screen now handles the full termination flow (draft → finalize → terminate)
- Keep the existing "Settlement" navigation link in the detail screen but ensure it also works for ACTIVE leases (not just terminated)

**Step 2: Update status check for settlement link visibility**

The settlement link should be visible for `ACTIVE`, `NOTICE_GIVEN`, `TERMINATED`, and `CLOSED` statuses.

**Step 3: Commit**

```bash
git add mobile/apps/manager/lib/screens/lease_detail_screen.dart
git commit -m "feat: update LeaseDetailScreen to use settlement screen for termination"
```

---

## Task 16: Mobile — Add `video_player` dependency if needed

**Files:**
- Modify: `mobile/apps/manager/pubspec.yaml`

**Step 1: Check and add dependencies**

If not already present, add:
```yaml
dependencies:
  video_player: ^2.9.2
```

The app already has `image_picker` and `file_picker`.

**Step 2: Run pub get**

```bash
cd mobile/apps/manager && flutter pub get
```

**Step 3: Commit**

```bash
git add mobile/apps/manager/pubspec.yaml mobile/apps/manager/pubspec.lock
git commit -m "chore: add video_player dependency for deduction attachment previews"
```

---

## Task 17: Integration testing — verify full flow

**Step 1: Start backend**

```bash
cd backend && ./gradlew bootRun
```

**Step 2: Test draft flow via curl**

```bash
# Get settlement preview
curl -s http://localhost:8080/api/v1/leases/{LEASE_ID}/settlement/preview \
  -H "X-User-Id: {USER_ID}" -H "X-Tenant-Id: {TENANT_ID}" -H "X-User-Role: TENANT_ADMIN"

# Create draft
curl -s -X POST http://localhost:8080/api/v1/leases/{LEASE_ID}/settlement/draft \
  -H "Content-Type: application/json" \
  -H "X-User-Id: {USER_ID}" -H "X-Tenant-Id: {TENANT_ID}" -H "X-User-Role: TENANT_ADMIN" \
  -d '{"notes":"Test draft","deductions":[{"category":"PROPERTY_DAMAGE","description":"Broken window","amount":500,"autoCalculated":false}]}'

# Get settlement (verify draft with deduction IDs)
curl -s http://localhost:8080/api/v1/leases/{LEASE_ID}/settlement \
  -H "X-User-Id: {USER_ID}" -H "X-Tenant-Id: {TENANT_ID}" -H "X-User-Role: TENANT_ADMIN"

# Upload attachment to deduction
curl -s -X POST http://localhost:8080/api/v1/settlements/deductions/{DEDUCTION_ID}/attachments \
  -H "X-User-Id: {USER_ID}" -H "X-Tenant-Id: {TENANT_ID}" -H "X-User-Role: TENANT_ADMIN" \
  -F "name=damage_photo" -F "file=@/path/to/test-image.jpg"

# Finalize settlement
curl -s -X POST http://localhost:8080/api/v1/leases/{LEASE_ID}/settlement/finalize \
  -H "X-User-Id: {USER_ID}" -H "X-Tenant-Id: {TENANT_ID}" -H "X-User-Role: TENANT_ADMIN"

# Verify attachment delete blocked after finalization
curl -s -X DELETE http://localhost:8080/api/v1/settlements/attachments/{ATTACHMENT_ID} \
  -H "X-User-Id: {USER_ID}" -H "X-Tenant-Id: {TENANT_ID}" -H "X-User-Role: TENANT_ADMIN"
# Expected: 400 or 409 error
```

**Step 3: Test web UI**

```bash
cd web && npm run dev
```
- Navigate to an ACTIVE lease
- Click "Settlement" to go to settlement page
- Add manual deductions, save as draft
- Upload attachments to deductions
- Finalize and verify lease terminates
- Verify finalized settlement shows attachments and allows adding more

**Step 4: Test mobile**

```bash
cd mobile/apps/manager && flutter run
```
- Navigate to an ACTIVE lease detail
- Tap "Terminate Lease" → goes to settlement screen
- Create draft with manual deductions
- Upload photos from camera/gallery
- Save draft, come back, verify persistence
- Finalize and verify

**Step 5: Commit any fixes from testing**

---

## Summary

| Task | Layer | Description |
|------|-------|-------------|
| 1 | DB | Liquibase migration: `settlement_deduction_attachments` + `status` column |
| 2 | Backend | `SettlementStatus` enum + entity update |
| 3 | Backend | `SettlementDeductionAttachment` entity + repository |
| 4 | Backend | DTOs: `DeductionAttachmentDTO`, `SaveSettlementDTO`, updated `SettlementResponseDTO` |
| 5 | Backend | `DeductionAttachmentService` (upload/download/delete with Azure+local) |
| 6 | Backend | `DeductionAttachmentController` (REST endpoints) |
| 7 | Backend | `SettlementService` — `saveDraft()` + `finalizeSettlement()` |
| 8 | Backend | `LeaseController` — new endpoints + response enrichment |
| 9 | Backend | Multipart upload size config (250MB) |
| 10 | Web | Settlement page with draft flow + deduction attachments |
| 11 | Web | Updated settlement summary with attachment display |
| 12 | Web | Upload route timeout increase |
| 13 | Mobile | `SettlementService` — new API methods |
| 14 | Mobile | `LeaseSettlementScreen` rewrite with full editing |
| 15 | Mobile | `LeaseDetailScreen` — navigate to settlement for termination |
| 16 | Mobile | `video_player` dependency |
| 17 | All | Integration testing |
