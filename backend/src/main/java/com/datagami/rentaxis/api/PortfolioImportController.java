package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.api.dto.PortfolioImportJobDetailsDTO;
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

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<?> importPortfolio(
            @RequestParam("file") MultipartFile file,
            @RequestHeader("X-User-Id") UUID userId) {
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".xlsx")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only .xlsx files are supported"));
        }

        if (file.getSize() > 5 * 1024 * 1024) {
            return ResponseEntity.badRequest().body(Map.of("error", "File size exceeds 5MB limit"));
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
                .filter(job -> job.getTenantId().equals(TenantContextHolder.getTenantId()))
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

    // ==================================================================
    // Accounting v2 cut-over (spec §10.3)
    // ==================================================================
    //
    // A sub-path of the same controller, deliberately: there is ONE importer, one
    // import_jobs table and one polling contract, and the cut-over is a second
    // sheet dialect rather than a second pipeline. What it does need of its own is
    // a role gate — every cut-over control admits ACCOUNTANT as well, because the
    // person who assembles a cut-over workbook out of a PACT export is the
    // accountant, and a template they must ask an admin to fetch is a template they
    // will rebuild by hand. The v1 endpoints above keep the roles they had.

    /** Same wording as {@code RecognitionController.NO_TENANT} — one sentence, said consistently. */
    static final String NO_TENANT = "Select an organisation first";

    /** Every cut-over control. */
    private static final String CUTOVER_ROLES = "hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')";

    /** The ordinary multipart ceiling; the global handler turns an over-size upload into a 400. */
    private static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

    /** A .xlsx is a zip. {@code PK\03\04} is the local file header every one of them starts with. */
    private static final byte[] ZIP_MAGIC = { 0x50, 0x4B, 0x03, 0x04 };

    @GetMapping("/cutover/template")
    @PreAuthorize(CUTOVER_ROLES)
    public ResponseEntity<?> downloadCutOverTemplate() {
        ResponseEntity<?> noTenant = tenantMissing();
        if (noTenant != null) return noTenant;
        try {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=contract-import-template.xlsx")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(templateService.generateCutOverTemplate());
        } catch (Exception e) {
            log.error("Failed to generate the cut-over template", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Upload a cut-over workbook. Answers {@code {"jobId": …}} at once and does the
     * work on the import executor, exactly as the v1 upload does — a six-hundred
     * contract workbook is not a request anybody should hold a connection open for.
     *
     * <p>The file is checked by its <em>signature</em>, not by its name or its
     * declared content type: a renamed executable with an .xlsx extension would
     * otherwise reach the parser, and the browser's Content-Type is whatever the
     * client felt like sending.</p>
     */
    @PostMapping(path = "/cutover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(CUTOVER_ROLES)
    public ResponseEntity<?> importCutOver(@RequestParam("file") MultipartFile file,
                                           @RequestHeader("X-User-Id") UUID userId) {
        ResponseEntity<?> noTenant = tenantMissing();
        if (noTenant != null) return noTenant;

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Choose a cut-over workbook to upload"));
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("error", "File size exceeds the 10MB limit"));
        }

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (Exception e) {
            log.error("Failed to read the uploaded cut-over workbook", e);
            return ResponseEntity.badRequest().body(Map.of("error", "The uploaded file could not be read"));
        }
        if (!looksLikeXlsx(fileBytes)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Only .xlsx workbooks are supported; this file is not one"));
        }

        UUID tenantId = TenantContextHolder.getTenantId();
        ImportJob job = new ImportJob();
        job.setStatus("VALIDATING");
        String filename = file.getOriginalFilename();
        job.setFileName(filename == null || filename.isBlank() ? "cutover.xlsx" : filename);
        job.setCreatedBy(userId);
        ImportJob savedJob = importJobRepository.save(job);

        importService.processImportAsync(fileBytes, savedJob, tenantId);
        return ResponseEntity.ok(Map.of("jobId", savedJob.getId()));
    }

    /**
     * The poll. Its own path rather than the v1 one so the cut-over's wider role gate
     * does not widen the v1 import's; the body is the same DTO, with
     * {@code importBatchId} filled in once the persist phase has run.
     */
    @GetMapping("/cutover/{jobId}/status")
    @PreAuthorize(CUTOVER_ROLES)
    public ResponseEntity<?> getCutOverStatus(@PathVariable UUID jobId) {
        ResponseEntity<?> noTenant = tenantMissing();
        if (noTenant != null) return noTenant;
        return importJobRepository.findById(jobId)
                .filter(job -> job.getTenantId().equals(TenantContextHolder.getTenantId()))
                .<ResponseEntity<?>>map(job -> ResponseEntity.ok(mapToResult(job)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    static boolean looksLikeXlsx(byte[] bytes) {
        if (bytes == null || bytes.length < ZIP_MAGIC.length) return false;
        for (int i = 0; i < ZIP_MAGIC.length; i++) {
            if (bytes[i] != ZIP_MAGIC[i]) return false;
        }
        return true;
    }

    /**
     * {@code ApiSecurityFilter} authorises a SUPER_ADMIN unconditionally but only
     * populates {@code TenantContextHolder} once they have picked an organisation.
     * Without this guard a platform admin with none selected reaches a service that
     * assumes an ambient tenant — and the Hibernate tenant filter is left off when
     * the context is empty, so an import would write its rows nowhere in particular.
     *
     * @return the 400 to return, or null when an organisation is selected.
     */
    private ResponseEntity<?> tenantMissing() {
        return TenantContextHolder.getTenantId() == null
                ? ResponseEntity.badRequest().body(Map.of("error", NO_TENANT))
                : null;
    }

    /** Package-private for unit tests. */
    PortfolioImportResultDTO mapToResult(ImportJob job) {
        PortfolioImportResultDTO dto = new PortfolioImportResultDTO();
        dto.setJobId(job.getId());
        dto.setStatus(job.getStatus());
        dto.setPropertiesCreated(job.getPropertiesCreated());
        dto.setBuildingsCreated(job.getBuildingsCreated());
        dto.setUnitsCreated(job.getUnitsCreated());
        dto.setRentersCreated(job.getRentersCreated());
        dto.setLeasesCreated(job.getLeasesCreated());
        // The job's schedules_created column now counts the cheque rows the import
        // built; the wire name follows what it holds.
        dto.setChequesCreated(job.getSchedulesCreated());

        // Cut-over only; null on every v1 job, and on a cut-over job that never
        // reached the persist phase.
        dto.setImportBatchId(job.getImportBatchId());

        // The errors column carries either:
        //   - the legacy array form (List<ImportErrorDTO>) for jobs older than the
        //     bulk-import counters extension and validation-failed jobs, or
        //   - the new wrapper form (PortfolioImportJobDetailsDTO) carrying counters
        //     and warnings alongside any errors. Detect by the first non-whitespace
        //     character so existing rows keep parsing.
        String raw = job.getErrors();
        if (raw == null) {
            dto.setErrors(Collections.emptyList());
        } else {
            String trimmed = raw.stripLeading();
            if (trimmed.startsWith("{")) {
                try {
                    PortfolioImportJobDetailsDTO details = objectMapper.readValue(raw, PortfolioImportJobDetailsDTO.class);
                    dto.setErrors(details.getErrors() == null ? Collections.emptyList() : details.getErrors());
                    dto.setWarnings(details.getWarnings() == null ? Collections.emptyList() : details.getWarnings());
                    if (details.getChequesFromSheet() != null) dto.setChequesFromSheet(details.getChequesFromSheet());
                    if (details.getBookingDepositsCreated() != null) dto.setBookingDepositsCreated(details.getBookingDepositsCreated());
                    if (details.getContractsCreated() != null) dto.setContractsCreated(details.getContractsCreated());
                    if (details.getMappingsCreated() != null) dto.setMappingsCreated(details.getMappingsCreated());
                } catch (Exception e) {
                    log.warn("Failed to parse import job {} errors as wrapper object: {}", job.getId(), e.toString());
                    dto.setErrors(List.of(new ImportErrorDTO("General", 0, "", "Could not parse error details")));
                }
            } else {
                try {
                    dto.setErrors(objectMapper.readValue(raw, new TypeReference<List<ImportErrorDTO>>() {}));
                } catch (Exception e) {
                    log.warn("Failed to parse import job {} errors as legacy array: {}", job.getId(), e.toString());
                    dto.setErrors(List.of(new ImportErrorDTO("General", 0, "", "Could not parse error details")));
                }
            }
        }

        return dto;
    }
}
