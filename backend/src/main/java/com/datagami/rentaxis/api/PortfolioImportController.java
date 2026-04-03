package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.api.dto.PortfolioImportResultDTO;
import com.datagami.rentaxis.core.service.PortfolioImportService;
import com.datagami.rentaxis.core.service.PortfolioTemplateService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.ImportJob;
import com.datagami.rentaxis.domain.repository.ImportJobRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/import/portfolio")
@RequiredArgsConstructor
public class PortfolioImportController {

    private final PortfolioImportService importService;
    private final PortfolioTemplateService templateService;
    private final ImportJobRepository importJobRepository;
    private final ObjectMapper objectMapper;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<?> importPortfolio(
            @RequestParam("file") MultipartFile file,
            @RequestHeader("X-User-Id") UUID userId) {
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".xlsx")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only .xlsx files are supported"));
        }

        try {
            byte[] fileBytes = file.getBytes();
            UUID tenantId = TenantContextHolder.getTenantId();

            ImportJob job = new ImportJob();
            job.setStatus("VALIDATING");
            job.setFileName(filename);
            job.setCreatedBy(userId);
            ImportJob savedJob = importJobRepository.save(job);

            importService.processImportAsync(fileBytes, savedJob, tenantId);

            return ResponseEntity.ok(Map.of("jobId", savedJob.getId()));
        } catch (Exception e) {
            log.error("Failed to start portfolio import", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to start import: " + e.getMessage()));
        }
    }

    @GetMapping("/{jobId}/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<PortfolioImportResultDTO> getStatus(@PathVariable UUID jobId) {
        return importJobRepository.findById(jobId)
                .map(job -> ResponseEntity.ok(mapToResult(job)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/template")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<byte[]> downloadTemplate() {
        try {
            byte[] template = templateService.generateTemplate();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=portfolio-import-template.xlsx")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(template);
        } catch (Exception e) {
            log.error("Failed to generate template", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private PortfolioImportResultDTO mapToResult(ImportJob job) {
        PortfolioImportResultDTO dto = new PortfolioImportResultDTO();
        dto.setJobId(job.getId());
        dto.setStatus(job.getStatus());
        dto.setPropertiesCreated(job.getPropertiesCreated());
        dto.setBuildingsCreated(job.getBuildingsCreated());
        dto.setUnitsCreated(job.getUnitsCreated());
        dto.setRentersCreated(job.getRentersCreated());
        dto.setLeasesCreated(job.getLeasesCreated());
        dto.setPaymentSchedulesCreated(job.getSchedulesCreated());

        if (job.getErrors() != null) {
            try {
                dto.setErrors(objectMapper.readValue(job.getErrors(), new TypeReference<List<ImportErrorDTO>>() {}));
            } catch (Exception e) {
                dto.setErrors(List.of(new ImportErrorDTO("General", 0, "", "Could not parse error details")));
            }
        } else {
            dto.setErrors(Collections.emptyList());
        }

        return dto;
    }
}
