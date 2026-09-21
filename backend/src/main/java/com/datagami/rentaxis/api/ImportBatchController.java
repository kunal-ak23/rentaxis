package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.api.dto.cutover.ImportBatchDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.cutover.ContractImportPostJobService;
import com.datagami.rentaxis.core.service.cutover.ContractImportPostService;
import com.datagami.rentaxis.core.service.cutover.ImportBatchDiscardService;
import com.datagami.rentaxis.core.service.cutover.ImportBatchService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.ImportJob;
import com.datagami.rentaxis.domain.repository.ImportJobRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The cut-over batches screen (spec §10.3, §11): what has been imported, the button
 * that puts it on the books, the one that takes it back off, and the one that
 * throws it away.
 *
 * <p><b>Role gate.</b> One class-level {@code @PreAuthorize}: SUPER_ADMIN,
 * TENANT_ADMIN and ACCOUNTANT. Posting a cut-over writes a portfolio's worth of
 * journals and reversing one unposts every contract it created, so it sits with the
 * rest of finance, not with property management.</p>
 *
 * <p><b>The tenant guard.</b> {@code ApiSecurityFilter} authorises a SUPER_ADMIN
 * unconditionally but only populates {@code TenantContextHolder} when the caller
 * has picked an organisation, so a platform admin with none selected would reach a
 * service that assumes an ambient tenant and fail as an opaque 500 — or, worse for
 * a list endpoint, read every organisation's batches, because the tenant filter is
 * left off when the context is empty. Same guard and same wording as
 * {@code RecognitionController} and {@code VoucherController}.</p>
 *
 * <p>Thin by design: every rule lives in {@link ImportBatchService},
 * {@link ContractImportPostService} and {@link ImportBatchDiscardService}.</p>
 */
@RestController
@RequestMapping("/api/v1/finance/import-batches")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')")
public class ImportBatchController {

    /** Same wording as {@code RecognitionController.NO_TENANT} — one sentence, said consistently. */
    static final String NO_TENANT = "Select an organisation first";

    private final ImportBatchService batches;
    private final ContractImportPostJobService postJobs;
    private final ImportBatchDiscardService discards;
    private final ImportJobRepository importJobs;
    private final ObjectMapper objectMapper;

    public record ReverseBatchDTO(@NotNull LocalDate date, String reason) {}

    /** What starting a bulk post answers with. A record, so the shape is the contract. */
    public record PostStartedDTO(UUID jobId, UUID batchId) {}

    /**
     * A bulk post in flight, or finished.
     *
     * <p>{@code processed}/{@code total} are the progress signal the batches page
     * polls; {@code result} is null until the run finishes and then carries the
     * per-contract outcomes. {@code errors} is only ever about the batch as a whole
     * — a books start date that is not set, someone else posting it — because a
     * contract that fails is inside {@code result}, not here.</p>
     */
    public record PostJobDTO(UUID jobId, UUID batchId, String status, Integer processed, Integer total,
                             ContractImportPostService.BulkPostResult result, List<ImportErrorDTO> errors) {}

    @GetMapping
    public ResponseEntity<List<ImportBatchDTO>> list() {
        requireTenantSelected();
        return ResponseEntity.ok(batches.list().stream().map(ImportBatchDTO::of).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ImportBatchDTO> get(@PathVariable UUID id) {
        requireTenantSelected();
        return ResponseEntity.ok(ImportBatchDTO.of(batches.get(id)));
    }

    /**
     * Put the batch on the books.
     *
     * <p><b>Asynchronous, like the upload that produced it.</b> A six-hundred
     * contract portfolio posts a few thousand journals; that is not a request anybody
     * should hold a connection open for, and the alternative — a synchronous call
     * that times out at the proxy while the transaction runs on — is the worst of
     * both. The job id comes back at once and the caller polls
     * {@code GET …/post/{jobId}}.</p>
     *
     * <p>The batch is resolved here, on the request thread, so another organisation's
     * id is a 404 rather than a job that fails a second later. The caller's
     * authentication travels with the job: see {@link ContractImportPostJobService}.</p>
     */
    @PostMapping("/{id}/post")
    public ResponseEntity<PostStartedDTO> post(@PathVariable UUID id) {
        requireTenantSelected();
        batches.get(id);   // 404 for another organisation's id, before a job row exists
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        ImportJob job = postJobs.start(id, currentUserId(auth));
        postJobs.runAsync(id, job.getId(), TenantContextHolder.getTenantId(), auth);
        return ResponseEntity.ok(new PostStartedDTO(job.getId(), id));
    }

    /** How the bulk post is getting on, and what it did. */
    @GetMapping("/{id}/post/{jobId}")
    public ResponseEntity<PostJobDTO> postStatus(@PathVariable UUID id, @PathVariable UUID jobId) {
        requireTenantSelected();
        ImportJob job = importJobs.findById(jobId)
                .filter(j -> TenantContextHolder.getTenantId().equals(j.getTenantId()))
                .orElseThrow(() -> new NotFoundException("Import job not found"));
        // The job has to be this batch's. Without it, any job id would answer on any
        // batch's URL, which is a confusing way to read somebody else's upload.
        ContractImportPostService.BulkPostResult result = postJobs.resultOf(job);
        UUID batchOfJob = job.getImportBatchId();
        if (batchOfJob != null && !batchOfJob.equals(id)
                && (result == null || !id.equals(result.repostOf()))) {
            throw new NotFoundException("Import job not found");
        }
        return ResponseEntity.ok(new PostJobDTO(job.getId(), batchOfJob, job.getStatus(),
                job.getProcessed(), job.getTotal(), result, errorsOf(job)));
    }

    @PostMapping("/{id}/reverse")
    public ResponseEntity<ImportBatchDTO> reverse(@PathVariable UUID id,
                                                  @Valid @RequestBody ReverseBatchDTO body) {
        requireTenantSelected();
        return ResponseEntity.ok(ImportBatchDTO.of(batches.reverse(id, body.date(), body.reason())));
    }

    /**
     * Throw the batch away: its draft contracts, and the properties, buildings,
     * units and renters it created, when nothing else refers to them.
     *
     * <p>Synchronous. Unlike the post it writes no journals and touches only the
     * rows one workbook made, and the accountant pressing it is waiting to re-upload
     * the corrected file.</p>
     */
    @PostMapping("/{id}/discard")
    public ResponseEntity<ImportBatchDiscardService.DiscardResult> discard(@PathVariable UUID id) {
        requireTenantSelected();
        return ResponseEntity.ok(discards.discard(id));
    }

    /** A batch belongs to an organisation; see the class Javadoc. */
    private void requireTenantSelected() {
        if (TenantContextHolder.getTenantId() == null) {
            throw new BusinessRuleViolationException(NO_TENANT);
        }
    }

    /** Same shape as {@code PostingService.currentUserId}: null for a system-run job. */
    private static UUID currentUserId(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The job's batch-level complaints. Always the legacy array shape here — a
     * bulk-post job writes its per-contract outcomes to {@code result}, never to
     * {@code errors} — so this does not need the portfolio controller's
     * array-or-object discriminator.
     */
    private List<ImportErrorDTO> errorsOf(ImportJob job) {
        if (job.getErrors() == null || job.getErrors().isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(job.getErrors(), new TypeReference<List<ImportErrorDTO>>() {});
        } catch (Exception e) {
            log.warn("Bulk-post job {} has unreadable errors: {}", job.getId(), e.toString());
            return List.of(ImportErrorDTO.file("Batch", "Post", "Could not read the failure details"));
        }
    }
}
