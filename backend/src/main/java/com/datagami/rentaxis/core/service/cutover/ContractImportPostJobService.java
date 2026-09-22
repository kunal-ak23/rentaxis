package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.core.service.cutover.ContractImportPostService.BulkPostResult;
import com.datagami.rentaxis.core.service.cutover.ContractImportPostService.Progress;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.ImportJob;
import com.datagami.rentaxis.domain.repository.ImportJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The bulk post as a job, because a six-hundred contract cut-over cannot finish
 * inside one HTTP request.
 *
 * <p>Same {@code import_jobs} row, same {@code importExecutor}, same polling shape
 * as the upload that produced the batch — there is one long-running mechanism in
 * this codebase and this is it. What it adds is a progress signal, because the
 * batches screen has to be able to say "212 of 600" rather than spin.</p>
 *
 * <h2>The two things an executor thread does not inherit</h2>
 *
 * <p><b>The tenant.</b> {@code AsyncConfig}'s decorator deliberately clears
 * {@code TenantContextHolder} at the start of every task, and {@code TenantAspect}
 * only enables Hibernate's tenant filter inside a transaction — so this thread has
 * to set the context itself and clear it in a {@code finally}, and every repository
 * touch has to be inside a transaction. That exact class of bug leaked data on this
 * stack before.</p>
 *
 * <p><b>The user.</b> There is no {@code SecurityContext} on an executor thread
 * either, and three things read it: {@code LeaseAccessPolicy}, which fails closed
 * and would answer "Lease not found" for every contract; {@code PostingService},
 * which stamps {@code posted_by} on every entry; and {@code ImportBatchService},
 * which records who posted the batch. So the caller's own {@code Authentication} is
 * captured on the request thread and re-installed here: the job runs as the person
 * who started it, with exactly the authorities they had, and not as a fabricated
 * super-user.</p>
 */
@Service
public class ContractImportPostJobService {

    private static final Logger log = LoggerFactory.getLogger(ContractImportPostJobService.class);

    /** The job row's status while the run is on the executor. */
    public static final String POSTING = "POSTING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    private final ContractImportPostService postService;
    private final ImportJobRepository jobs;
    private final ObjectMapper objectMapper;

    /** Short transactions of their own, so a progress row is visible while the run holds its batch lock. */
    private final TransactionTemplate ownTx;

    public ContractImportPostJobService(ContractImportPostService postService, ImportJobRepository jobs,
                                        ObjectMapper objectMapper,
                                        PlatformTransactionManager transactionManager) {
        this.postService = postService;
        this.jobs = jobs;
        this.objectMapper = objectMapper;
        this.ownTx = new TransactionTemplate(transactionManager);
        this.ownTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Create the job row the caller polls. On the request thread, in the caller's
     * transaction, so the id handed back is one the next request can find.
     */
    public ImportJob start(UUID batchId, UUID userId) {
        ImportJob job = new ImportJob();
        job.setStatus(POSTING);
        job.setFileName("bulk-post");
        job.setImportBatchId(batchId);
        job.setCreatedBy(userId);
        job.setProcessed(0);
        return jobs.save(job);
    }

    /**
     * Run it.
     *
     * @param auth the caller's authentication, captured on the request thread. Null
     *             is legal — a system-run post — and simply means no user is
     *             recorded against the journals.
     */
    @Async("importExecutor")
    public void runAsync(UUID batchId, UUID jobId, UUID tenantId, Authentication auth) {
        TenantContextHolder.setTenantId(tenantId);
        if (auth != null) {
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        try {
            Consumer<Progress> progress = p -> ownTx.executeWithoutResult(s -> jobs.findById(jobId)
                    .ifPresent(job -> {
                        job.setProcessed(p.processed());
                        job.setTotal(p.total());
                        jobs.save(job);
                    }));

            BulkPostResult result = postService.post(batchId, progress);

            ownTx.executeWithoutResult(s -> jobs.findById(jobId).ifPresent(job -> {
                job.setStatus(COMPLETED);
                job.setCompletedAt(Instant.now());
                // The batch the journals actually landed in — the one asked for, or the
                // successor batch a re-post of a REVERSED one created.
                job.setImportBatchId(result.batchId());
                job.setLeasesCreated(result.leasesPosted());
                job.setProcessed(result.leases().size());
                job.setTotal(result.leases().size());
                job.setResult(toJson(result));
                jobs.save(job);
            }));
            log.info("Bulk-post job {} completed for batch {}: {} posted, {} failed",
                    jobId, result.batchId(), result.leasesPosted(), result.leasesFailed());
        } catch (RuntimeException e) {
            // A refusal about the batch as a whole — no books start date, someone else
            // is posting it, the batch is DISCARDED. Per-contract failures never reach
            // here; they are inside the result.
            log.warn("Bulk-post job {} failed for batch {}: {}", jobId, batchId, e.getMessage());
            ownTx.executeWithoutResult(s -> jobs.findById(jobId).ifPresent(job -> {
                job.setStatus(FAILED);
                job.setCompletedAt(Instant.now());
                job.setErrors(errorJson(e));
                jobs.save(job);
            }));
        } finally {
            TenantContextHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /** The stored result, or null when the job has not produced one. */
    public BulkPostResult resultOf(ImportJob job) {
        if (job.getResult() == null || job.getResult().isBlank()) return null;
        try {
            return objectMapper.readValue(job.getResult(), BulkPostResult.class);
        } catch (Exception e) {
            log.warn("Bulk-post job {} has an unreadable result: {}", job.getId(), e.toString());
            return null;
        }
    }

    private String toJson(BulkPostResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("Bulk-post result for batch {} could not be stored as JSON: {}",
                    result.batchId(), e.toString());
            return null;
        }
    }

    private String errorJson(RuntimeException e) {
        try {
            return objectMapper.writeValueAsString(List.of(ImportErrorDTO.file("Batch", "Post",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
        } catch (Exception jsonEx) {
            return "[{\"sheet\":\"Batch\",\"row\":null,\"field\":\"Post\","
                    + "\"message\":\"The bulk post failed\"}]";
        }
    }
}
