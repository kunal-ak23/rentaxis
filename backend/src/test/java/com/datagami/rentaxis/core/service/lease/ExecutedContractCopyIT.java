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
            verify(contracts, never()).createExecutedCopy(leaseId);
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
        UUID lease = UUID.randomUUID();
        postInTransaction(UUID.randomUUID(), lease, true);
        verify(contracts, after(1500).never()).createExecutedCopy(lease);
    }

    @Test
    void aFailureToIssueDoesNotFailThePost() {
        UUID lease = UUID.randomUUID();
        // An Error, not just an exception (R3 minor 1): e.g. the PDF library overflowing.
        when(contracts.createExecutedCopy(lease)).thenThrow(new StackOverflowError("renderer down"));
        // Completes without an exception reaching the committer.
        postInTransaction(UUID.randomUUID(), lease, false);
        verify(contracts, timeout(5000)).createExecutedCopy(lease);
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    void theDatabaseHoldsAtMostOneExecutedCopyPerLease() {
        String def = jdbc.queryForObject(
                "select indexdef from pg_indexes where indexname = 'uq_lease_documents_executed_copy'", String.class);
        assertThat(def).startsWith("CREATE UNIQUE INDEX").contains("(lease_id)").contains("'EXECUTED_COPY'");
    }

    // ------------------------------------------------------------------
    // The nightly sweep for copies whose after-commit issue was dropped
    // ------------------------------------------------------------------

    private UUID org(boolean stamp) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into landlord_org (id, name, slug, status, stamp_image_url) values (?, ?, ?, 'ACTIVE', ?)",
                id, "Sweep " + id, "sweep-" + id, stamp ? "https://acct.blob.core.windows.net/tenant-" + id + "/branding/s.png" : null);
        return id;
    }

    private UUID lease(UUID tenantId, String status, boolean posted, String... docTypes) {
        UUID property = UUID.randomUUID(), unit = UUID.randomUUID(), renter = UUID.randomUUID(), lease = UUID.randomUUID();
        jdbc.update("INSERT INTO properties (id, tenant_id, name_en, emirate) VALUES (?,?,?,?)",
                property, tenantId, "P-" + property, "DUBAI");
        jdbc.update("INSERT INTO units (id, tenant_id, property_id, unit_number) VALUES (?,?,?,?)",
                unit, tenantId, property, "U-" + unit);
        jdbc.update("INSERT INTO renters (id, tenant_id, name_en) VALUES (?,?,?)", renter, tenantId, "R-" + renter);
        jdbc.update("INSERT INTO leases (id, tenant_id, unit_id, renter_id, start_date, end_date, status,"
                        + " rent_amount, deposit_amount, posted_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                lease, tenantId, unit, renter, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), status,
                new java.math.BigDecimal("1200.00"), java.math.BigDecimal.ZERO,
                posted ? java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T08:00:00Z")) : null);
        for (String type : docTypes) {
            jdbc.update("insert into lease_documents (id, tenant_id, lease_id, document_url, type) values (?,?,?,?,?)",
                    UUID.randomUUID(), tenantId, lease, type.toLowerCase() + ".pdf", type);
        }
        return lease;
    }

    @Test
    void theNightlySweepIssuesOnlyMissingCopiesOrganisationByOrganisation() {
        UUID a = org(true), b = org(true), c = org(false);
        UUID a1 = lease(a, "ACTIVE", true, "CONTRACT");                        // missing: issued
        UUID a2 = lease(a, "ACTIVE", true, "CONTRACT", "EXECUTED_COPY");       // already has one
        UUID a3 = lease(a, "PENDING_SIGNATURE", false, "CONTRACT");            // not signed
        UUID a4 = lease(a, "ACTIVE", true);                                    // no stored contract
        UUID b1 = lease(b, "TERMINATED", true, "CONTRACT");                    // missing, but fails
        UUID c1 = lease(c, "ACTIVE", true, "CONTRACT");                        // org has no stamp
        java.util.Map<UUID, UUID> seenTenant = new java.util.concurrent.ConcurrentHashMap<>();
        doAnswer(inv -> {
            UUID id = inv.getArgument(0);
            seenTenant.put(id, TenantContextHolder.getTenantId());
            if (id.equals(b1)) throw new IllegalStateException("renderer down");
            if (id.equals(a1)) {
                var dto = new com.datagami.rentaxis.api.dto.LeaseDocumentDTO();
                dto.setId(UUID.randomUUID());
                return Optional.of(dto);
            }
            return Optional.empty();
        }).when(contracts).createExecutedCopy(any());

        var run = executedCopies.sweepMissing(ExecutedContractCopyService.SWEEP_MAX, ExecutedContractCopyService.SWEEP_MAX_PER_ORG);

        verify(contracts).createExecutedCopy(a1);
        verify(contracts).createExecutedCopy(b1);
        for (UUID notDue : new UUID[]{a2, a3, a4, c1}) verify(contracts, never()).createExecutedCopy(notDue);
        // Each in its own organisation's context; B's failure did not stop A.
        assertThat(seenTenant).containsEntry(a1, a).containsEntry(b1, b);
        assertThat(run.issued()).isGreaterThanOrEqualTo(1);
        assertThat(run.failed()).isGreaterThanOrEqualTo(1);
        assertThat(TenantContextHolder.getTenantId()).isNull();

        // Bounded per organisation: with a cap of one, A contributes one lease a night.
        UUID a5 = lease(a, "ACTIVE", true, "CONTRACT");
        reset(contracts);
        when(contracts.createExecutedCopy(any())).thenReturn(Optional.empty());
        executedCopies.sweepMissing(ExecutedContractCopyService.SWEEP_MAX, 1);
        long fromA = org.mockito.Mockito.mockingDetails(contracts).getInvocations().stream()
                .filter(i -> i.getArguments()[0].equals(a1) || i.getArguments()[0].equals(a5)).count();
        assertThat(fromA).isEqualTo(1);
    }
}
