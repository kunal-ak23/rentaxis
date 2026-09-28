package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.LeaseDocumentDTO;
import com.datagami.rentaxis.core.service.ContractGenerationService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

/**
 * Issues the executed copy of a contract — the signed contract with the
 * organisation's digital stamp — when the lease is posted (Kunal, 2026-09-28,
 * "Executed copy at posting").
 *
 * <p>Posting ({@code LeasePostingService}, through {@code markActiveOnPosting}) is
 * the one way a lease becomes ACTIVE, so {@link LeasePostedEvent} covers every
 * transition into a signed state; the later ones (NOTICE_GIVEN, RENEWED, EXPIRED,
 * TERMINATED, CLOSED) all start from ACTIVE.</p>
 *
 * <p><b>Never blocks posting.</b> It runs after the posting transaction has
 * committed, off the request thread ({@code documentExecutor}), in a transaction
 * of its own, and any failure — the PDF renderer,
 * storage, a concurrent duplicate — is logged and swallowed; the lease stays
 * posted and {@link #issue} (the manual "Issue executed copy" action) can make the
 * copy later. Idempotent: a second run finds the copy and returns it.</p>
 */
@Service
@Slf4j
public class ExecutedContractCopyService {

    private final ContractGenerationService contracts;
    private final TransactionTemplate newTransaction;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public ExecutedContractCopyService(ContractGenerationService contracts, PlatformTransactionManager txm,
                                       org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.contracts = contracts;
        this.jdbc = jdbc;
        this.newTransaction = new TransactionTemplate(txm);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @org.springframework.scheduling.annotation.Async("documentExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPosted(LeasePostedEvent event) {
        UUID previous = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(event.tenantId());
        try {
            newTransaction.execute(status -> contracts.createExecutedCopy(event.leaseId()));
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable e) {
            // Anything else — including an Error from the PDF library — is logged:
            // the post has committed and the copy can be issued again from the lease.
            log.error("Executed copy not issued for lease {} (posting kept; issue it again from the lease)",
                    event.leaseId(), e);
        } finally {
            if (previous == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.setTenantId(previous);
            }
        }
    }

    /** What one sweep did. {@code none}: the lease turned out to need no copy (e.g. no stamp area). */
    public record SweepRun(int organisations, int issued, int none, int failed) {
    }

    /** Per run, and per organisation within it, so one big landlord cannot take a whole night. */
    public static final int SWEEP_MAX = 200;
    public static final int SWEEP_MAX_PER_ORG = 25;

    /**
     * Posted, signed leases that have a stored signed contract and an organisation
     * stamp but no executed copy — the ones whose after-commit issue was dropped
     * (document executor saturated by a bulk post) or failed. Explicit tenant
     * columns (no Hibernate filter: a cross-organisation read by a system job);
     * random order so a lease that can never get a copy cannot starve the others.
     */
    static final String MISSING_SQL = """
            SELECT tenant_id, id FROM (
              SELECT l.tenant_id, l.id,
                     row_number() OVER (PARTITION BY l.tenant_id ORDER BY random()) AS n
                FROM leases l
                JOIN landlord_org o ON o.id = l.tenant_id
               WHERE l.posted_at IS NOT NULL
                 AND l.status IN ('ACTIVE','NOTICE_GIVEN','RENEWED','EXPIRED','TERMINATED','CLOSED')
                 AND o.stamp_image_url IS NOT NULL AND btrim(o.stamp_image_url) <> ''
                 AND EXISTS (SELECT 1 FROM lease_documents d
                              WHERE d.lease_id = l.id AND d.tenant_id = l.tenant_id AND d.type = 'CONTRACT')
                 AND NOT EXISTS (SELECT 1 FROM lease_documents d
                              WHERE d.lease_id = l.id AND d.type = 'EXECUTED_COPY')
            ) c
             WHERE n <= ?
             ORDER BY random()
             LIMIT ?
            """;

    /**
     * The daily catch-up (run by {@code LeaseExpirationJob}): issues the missing
     * executed copies, one organisation at a time with that organisation's tenant
     * context, each lease in its own transaction. A failure is that lease's own —
     * logged, the sweep carries on (the next night retries it). Idempotent: an
     * existing copy is returned, and changeset 158's unique index refuses a second.
     */
    public SweepRun sweepMissing(int max, int maxPerOrg) {
        java.util.Map<UUID, java.util.List<UUID>> byOrg = new java.util.LinkedHashMap<>();
        jdbc.query(MISSING_SQL, rs -> {
            byOrg.computeIfAbsent(rs.getObject(1, UUID.class), k -> new java.util.ArrayList<>())
                    .add(rs.getObject(2, UUID.class));
        }, maxPerOrg, max);
        int issued = 0, none = 0, failed = 0;
        UUID previous = TenantContextHolder.getTenantId();
        try {
            for (var org : byOrg.entrySet()) {
                TenantContextHolder.setTenantId(org.getKey());
                for (UUID leaseId : org.getValue()) {
                    try {
                        Optional<LeaseDocumentDTO> copy =
                                newTransaction.execute(status -> contracts.createExecutedCopy(leaseId));
                        if (copy != null && copy.isPresent()) issued++; else none++;
                    } catch (VirtualMachineError fatal) {
                        throw fatal;
                    } catch (Throwable e) {
                        failed++;
                        log.error("Executed copy sweep: lease {} of organisation {} failed; the sweep carries on",
                                leaseId, org.getKey(), e);
                    }
                }
            }
        } finally {
            if (previous == null) TenantContextHolder.clear(); else TenantContextHolder.setTenantId(previous);
        }
        if (issued + none + failed > 0) {
            log.info("Executed copy sweep: organisations={} issued={} none={} failed={}",
                    byOrg.size(), issued, none, failed);
        }
        return new SweepRun(byOrg.size(), issued, none, failed);
    }

    /** The manual action: issue the copy now if it is due (returns the existing one if already issued). */
    public Optional<LeaseDocumentDTO> issue(UUID leaseId) {
        try {
            return contracts.createExecutedCopy(leaseId);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Issued by a concurrent run between our check and our insert.
            return contracts.createExecutedCopy(leaseId);
        }
    }
}
