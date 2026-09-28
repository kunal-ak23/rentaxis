package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.core.service.ContractGenerationService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Executed copy at posting (Kunal, 2026-09-28): the copy is issued after the
 * posting transaction commits — never inside it, never on a rollback — for the
 * lease's own organisation, and a failure to issue it does not undo or fail the
 * post. At most one copy per lease is enforced by the database too.
 */
@SpringBootTest
class ExecutedContractCopyIT extends AbstractPostgresIT {

    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager txm;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExecutedContractCopyService executedCopies;
    @MockitoBean ContractGenerationService contracts;
    // The event's other listeners would look the (synthetic) lease up; not under test here.
    @MockitoBean com.datagami.rentaxis.core.service.recognition.RecognitionEventListener recognition;
    @MockitoBean com.datagami.rentaxis.core.service.vat.VatTaxPointEventListener vatTaxPoints;

    @AfterEach
    void tearDown() {
        reset(contracts);
        TenantContextHolder.clear();
    }

    private void postInTransaction(UUID tenantId, UUID leaseId, boolean rollback) {
        TransactionTemplate tx = new TransactionTemplate(txm);
        tx.executeWithoutResult(status -> {
            events.publishEvent(new LeasePostedEvent(tenantId, leaseId, LocalDate.of(2026, 4, 24)));
            // Nothing is issued while the posting transaction is still open.
            verify(contracts, never()).createExecutedCopy(any());
            if (rollback) status.setRollbackOnly();
        });
    }

    @Test
    void issuedAfterCommitInTheLeasesOwnOrganisation() {
        UUID tenant = UUID.randomUUID();
        UUID lease = UUID.randomUUID();
        AtomicReference<UUID> seenTenant = new AtomicReference<>();
        doAnswer(inv -> { seenTenant.set(TenantContextHolder.getTenantId()); return Optional.empty(); })
                .when(contracts).createExecutedCopy(lease);

        AtomicReference<String> thread = new AtomicReference<>();
        doAnswer(inv -> { seenTenant.set(TenantContextHolder.getTenantId()); thread.set(Thread.currentThread().getName());
            return Optional.empty(); }).when(contracts).createExecutedCopy(lease);

        postInTransaction(tenant, lease, false);

        verify(contracts, timeout(5000)).createExecutedCopy(lease);
        assertThat(seenTenant.get()).isEqualTo(tenant);
        // Off the posting request's thread (R3 minor 1).
        assertThat(thread.get()).startsWith("document-").isNotEqualTo(Thread.currentThread().getName());
        assertThat(TenantContextHolder.getTenantId()).isNull(); // the caller's context untouched
    }

    @Test
    void notIssuedWhenThePostRollsBack() {
        postInTransaction(UUID.randomUUID(), UUID.randomUUID(), true);
        verify(contracts, after(1500).never()).createExecutedCopy(any());
    }

    @Test
    void aFailureToIssueDoesNotFailThePost() {
        UUID lease = UUID.randomUUID();
        // An Error, not just an exception (R3 minor 1): e.g. the PDF library overflowing.
        when(contracts.createExecutedCopy(lease)).thenThrow(new StackOverflowError("renderer down"));
        // Completes without an exception reaching the committer.
        postInTransaction(UUID.randomUUID(), lease, false);
        verify(contracts, timeout(5000)).createExecutedCopy(lease);
        // The listener itself swallows it (logged; the copy can be issued by hand).
        org.assertj.core.api.Assertions.assertThatCode(() -> executedCopies.onPosted(
                new LeasePostedEvent(UUID.randomUUID(), lease, LocalDate.of(2026, 4, 24)))).doesNotThrowAnyException();
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    void theDatabaseHoldsAtMostOneExecutedCopyPerLease() {
        String def = jdbc.queryForObject(
                "select indexdef from pg_indexes where indexname = 'uq_lease_documents_executed_copy'", String.class);
        assertThat(def).startsWith("CREATE UNIQUE INDEX").contains("(lease_id)").contains("'EXECUTED_COPY'");
    }
}
