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
 * committed, in a transaction of its own, and any failure — the PDF renderer,
 * storage, a concurrent duplicate — is logged and swallowed; the lease stays
 * posted and {@link #issue} (the manual "Issue executed copy" action) can make the
 * copy later. Idempotent: a second run finds the copy and returns it.</p>
 */
@Service
@Slf4j
public class ExecutedContractCopyService {

    private final ContractGenerationService contracts;
    private final TransactionTemplate newTransaction;

    public ExecutedContractCopyService(ContractGenerationService contracts, PlatformTransactionManager txm) {
        this.contracts = contracts;
        this.newTransaction = new TransactionTemplate(txm);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPosted(LeasePostedEvent event) {
        UUID previous = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(event.tenantId());
        try {
            newTransaction.execute(status -> contracts.createExecutedCopy(event.leaseId()));
        } catch (RuntimeException e) {
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
