package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class LandlordOrgService {

    private final LandlordOrgRepository repository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final JdbcTemplate jdbcTemplate;
    private final ContractGenerationService contractGenerationService;
    private final TenantArtifactCleanupService tenantArtifactCleanupService;

    public LandlordOrgService(LandlordOrgRepository repository,
                               UserRepository userRepository,
                               NotificationService notificationService,
                               JdbcTemplate jdbcTemplate,
                               ContractGenerationService contractGenerationService,
                               TenantArtifactCleanupService tenantArtifactCleanupService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.jdbcTemplate = jdbcTemplate;
        this.contractGenerationService = contractGenerationService;
        this.tenantArtifactCleanupService = tenantArtifactCleanupService;
    }

    @Transactional
    public LandlordOrg provisionTenant(String name) {
        LandlordOrg org = new LandlordOrg();
        org.setName(name);
        LandlordOrg saved = repository.save(org);

        // Notify all SUPER_ADMINs
        try {
            userRepository.findByRole(UserRole.SUPER_ADMIN).forEach(admin -> {
                notificationService.notify(null, admin.getId(),
                        "TENANT_PROVISIONED", "New Organization Created",
                        "A new organization '" + name + "' has been provisioned.",
                        "TENANT", saved.getId());
            });
        } catch (Exception e) {
            log.warn("Failed to send tenant provisioned notification: {}", e.getMessage());
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public List<LandlordOrg> listAllTenants() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<LandlordOrg> findById(UUID id) {
        return repository.findById(id);
    }

    @Transactional
    public LandlordOrg save(LandlordOrg org) {
        return repository.save(org);
    }

    /**
     * Hard-delete a tenant and every row associated with it.
     *
     * <p>Cascade strategy: discover every table with a {@code tenant_id} column
     * via {@code information_schema} and issue a tenant-scoped DELETE against
     * each, retrying FK-blocked tables in subsequent passes until either
     * everything's gone or we hit the pass limit. Each DELETE runs inside a
     * JDBC savepoint so a FK violation rolls back only that statement, not the
     * whole transaction — Spring's default behavior would otherwise mark the
     * txn rollback-only on the first FK failure.
     *
     * <p>Safety gate: caller must supply {@code confirmName} matching the
     * tenant's current name exactly. The controller-level @PreAuthorize already
     * restricts to SUPER_ADMIN; this is the second layer to prevent the
     * "wrong UUID in a curl" failure mode.
     */
    @Transactional
    public void deleteTenant(UUID tenantId, String confirmName) {
        LandlordOrg org = repository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));

        if (confirmName == null || !confirmName.equals(org.getName())) {
            throw new IllegalArgumentException(
                    "confirmName does not match tenant name. Supplied=" + confirmName
                            + " actual=" + org.getName());
        }

        // Capture the exact contract artifacts while their rows still exist.
        // They are deleted only after the database transaction commits, so a
        // failed purge never leaves live rows pointing at missing documents.
        List<String> contractDocumentUrls = jdbcTemplate.queryForList(
                "SELECT document_url FROM lease_documents WHERE tenant_id = ?",
                String.class,
                tenantId);

        // Persist an exact-object plan in this same transaction while every
        // artifact reference is still available. If any later part of the
        // purge rolls back, the plan rolls back with it and afterCommit never
        // runs, so live rows cannot be left pointing at deleted objects.
        int queuedArtifacts = tenantArtifactCleanupService.captureAndEnqueue(tenantId);

        // IMPORTANT — convention coupling: this discovery query only finds
        // tables whose tenant-scope column is literally named `tenant_id`.
        // If a future feature adds a tenanted table with a different column
        // name (e.g. `owning_tenant_id`), or stores tenant-scoped data
        // outside the DB (blob storage, S3 prefixes, search indexes, etc.),
        // that data will NOT be discovered by this query. Contract documents
        // are handled explicitly below; every other external store must add a
        // similarly scoped cleanup hook.
        List<String> tenantedTables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.columns " +
                        "WHERE column_name = 'tenant_id' AND table_schema = 'public'",
                String.class);

        // Defensive: if discovery returns nothing, refuse to proceed. An empty
        // list is virtually always a schema-introspection misconfiguration
        // (wrong schema name, missing grant) — not "the DB has no tenanted
        // tables." Failing closed here prevents silently deleting only the
        // landlord_org row while leaving orphaned tenant data everywhere.
        if (tenantedTables.isEmpty()) {
            throw new IllegalStateException(
                    "deleteTenant: information_schema returned zero tenanted tables. " +
                            "Refusing to proceed (would orphan data). Check schema=public visibility.");
        }

        log.info("deleteTenant({}): purging {} tenanted tables", tenantId, tenantedTables.size());

        // Preserve cross-tenant users: anyone whose users.tenant_id is this
        // tenant AND who has a user_tenant_memberships row pointing to a
        // DIFFERENT tenant. The cascade would otherwise hard-delete their
        // `users` row and silently revoke their other-tenant access.
        //
        // We REPARENT them to one of their other membership tenants rather
        // than NULL-ing tenant_id, for two reasons:
        //   (a) tenant_id=NULL is reserved for SUPER_ADMIN — a TENANT_USER
        //       with NULL tenant_id would violate the (role, tenant_id)
        //       invariant that downstream code relies on (TenantAspect,
        //       getUserTenantIds, etc.).
        //   (b) Migration 59 added `ux_users_email_super_admin` (UNIQUE on
        //       email WHERE tenant_id IS NULL). If two cross-tenant users
        //       shared an email and we NULL'd both, the second deletion
        //       would fail on the partial-unique conflict.
        //
        // Reparent target: the earliest-joined other-tenant membership where
        // moving this user wouldn't violate `ux_users_tenant_email` (i.e.
        // no other user in that tenant already owns this email). Users with
        // no safe target are left untouched — they'll be hard-deleted by
        // the cascade. That's a documented edge-case loss: two distinct
        // users sharing an email AND sharing exactly one cross-tenant
        // membership, where the first to be cleaned wins the reparent.
        int preserved = jdbcTemplate.update(
                "UPDATE users u SET tenant_id = (" +
                        "  SELECT m.tenant_id FROM user_tenant_memberships m " +
                        "  WHERE m.user_id = u.id AND m.tenant_id <> ? " +
                        "    AND NOT EXISTS (" +
                        "      SELECT 1 FROM users u2 " +
                        "      WHERE u2.tenant_id = m.tenant_id " +
                        "        AND u2.email = u.email AND u2.id <> u.id" +
                        "    ) " +
                        "  ORDER BY m.created_at ASC, m.tenant_id ASC LIMIT 1" +
                        ") " +
                        "WHERE u.tenant_id = ? AND EXISTS (" +
                        "  SELECT 1 FROM user_tenant_memberships m " +
                        "  WHERE m.user_id = u.id AND m.tenant_id <> ? " +
                        "    AND NOT EXISTS (" +
                        "      SELECT 1 FROM users u2 " +
                        "      WHERE u2.tenant_id = m.tenant_id " +
                        "        AND u2.email = u.email AND u2.id <> u.id" +
                        "    )" +
                        ")",
                tenantId, tenantId, tenantId);
        if (preserved > 0) {
            log.info("deleteTenant({}): reparented {} cross-tenant users to their other tenants", tenantId, preserved);
        }

        // The ledger cannot be cleared by the pass loop below — see purgeLedger.
        purgeLedger(tenantId);

        jdbcTemplate.execute((java.sql.Connection conn) -> {
            List<String> remaining = new java.util.ArrayList<>(tenantedTables);
            for (int pass = 1; pass <= 6 && !remaining.isEmpty(); pass++) {
                List<String> stillBlocked = new java.util.ArrayList<>();
                for (String table : remaining) {
                    java.sql.Savepoint sp = conn.setSavepoint("del_" + table.replaceAll("\\W", "_"));
                    // Quote the table identifier — names come from information_schema
                    // (not user input), but mixed-case or reserved words would break
                    // without quoting. Embedded double-quote is rejected as a safety
                    // net against any future schema-introspection surprise.
                    if (table.contains("\"")) {
                        throw new IllegalStateException(
                                "Refusing to delete from table with double-quote in name: " + table);
                    }
                    String quoted = "\"" + table + "\"";
                    try (var ps = conn.prepareStatement(
                            "DELETE FROM " + quoted + " WHERE tenant_id = ?")) {
                        ps.setObject(1, tenantId);
                        int rows = ps.executeUpdate();
                        log.debug("  pass {}: deleted {} rows from {}", pass, rows, table);
                        conn.releaseSavepoint(sp);
                    } catch (java.sql.SQLException e) {
                        // FK violation — roll back to savepoint, retry next pass.
                        conn.rollback(sp);
                        stillBlocked.add(table);
                        log.debug("  pass {}: {} blocked ({})", pass, table, e.getSQLState());
                    }
                }
                if (stillBlocked.size() == remaining.size()) {
                    // No forward progress — bail rather than spin.
                    throw new IllegalStateException(
                            "deleteTenant stalled after pass " + pass + " with tables: " + stillBlocked);
                }
                remaining = stillBlocked;
            }
            if (!remaining.isEmpty()) {
                throw new IllegalStateException(
                        "deleteTenant could not clear tables after 6 passes: " + remaining);
            }
            // Org row last.
            try (var ps = conn.prepareStatement("DELETE FROM landlord_org WHERE id = ?")) {
                ps.setObject(1, tenantId);
                int rows = ps.executeUpdate();
                if (rows != 1) {
                    throw new IllegalStateException(
                            "Expected to delete 1 landlord_org row, deleted " + rows);
                }
            }
            return null;
        });

        scheduleExternalCleanupAfterCommit(tenantId, contractDocumentUrls);
        log.info("deleteTenant({}): database purge completed; contract cleanup and {} exact artifact cleanups scheduled after commit",
                tenantId, queuedArtifacts);
    }

    /** The two ledger tables, in the order their foreign keys allow. */
    private static final List<String> LEDGER_TABLES = List.of("journal_lines", "journal_entries");

    /**
     * Clear this tenant's journal, which the pass loop provably cannot (issue #326).
     *
     * <p>Two separate obstacles, and the loop hits both at once — it retried
     * {@code leases}, {@code journal_entries} and {@code journal_lines} until it
     * ran out of passes and threw {@code deleteTenant stalled}:</p>
     *
     * <ol>
     *   <li><b>A foreign-key cycle.</b> {@code leases.posting_journal_id} and
     *       {@code leases.termination_journal_id} point at {@code journal_entries};
     *       {@code journal_entries.lease_id} points back. Neither table can be
     *       deleted before the other, whatever order the loop tries, so the
     *       pointers are NULLed first — discovered from {@code information_schema}
     *       rather than listed here, so a future table that references a journal
     *       (and carries a {@code tenant_id}) is handled the day it is added.</li>
     *   <li><b>The immutability triggers</b> of changeset 81 refuse every DELETE on
     *       {@code journal_entries} and {@code journal_lines} — "reverse the entry
     *       instead" — which is the right answer for every caller except this one.
     *       A tenant being erased has no books left to reverse into. They are
     *       switched off for the length of the two DELETEs and switched straight
     *       back on; both statements run inside {@code deleteTenant}'s own
     *       transaction, so a failure anywhere later rolls the disable back with
     *       everything else, and {@code ALTER TABLE} takes an ACCESS EXCLUSIVE lock
     *       — no other session can write an unguarded journal through the window,
     *       because no other session can write at all while it is open.</li>
     * </ol>
     *
     * <p>Tenant-scoped like every other statement here: both DELETEs carry
     * {@code WHERE tenant_id = ?}, so another tenant's ledger is never in range
     * even while the triggers are off.</p>
     */
    private void purgeLedger(UUID tenantId) {
        for (JournalReference reference : inboundJournalReferences()) {
            int cleared = jdbcTemplate.update(
                    "UPDATE " + quote(reference.table()) + " SET " + quote(reference.column())
                            + " = NULL WHERE tenant_id = ? AND " + quote(reference.column()) + " IS NOT NULL",
                    tenantId);
            if (cleared > 0) {
                log.debug("deleteTenant({}): cleared {} {}.{} journal pointers",
                        tenantId, cleared, reference.table(), reference.column());
            }
        }

        for (String table : LEDGER_TABLES) {
            jdbcTemplate.execute("ALTER TABLE " + quote(table) + " DISABLE TRIGGER USER");
        }
        try {
            for (String table : LEDGER_TABLES) {
                int deleted = jdbcTemplate.update(
                        "DELETE FROM " + quote(table) + " WHERE tenant_id = ?", tenantId);
                log.debug("deleteTenant({}): deleted {} rows from {}", tenantId, deleted, table);
            }
        } finally {
            for (String table : LEDGER_TABLES) {
                jdbcTemplate.execute("ALTER TABLE " + quote(table) + " ENABLE TRIGGER USER");
            }
        }
    }

    /** A tenanted column somewhere in the schema that points at a journal entry. */
    private record JournalReference(String table, String column) {
    }

    /**
     * Every {@code tenant_id}-carrying column that references {@code journal_entries},
     * excluding the journal's own tables — {@code journal_lines} is deleted outright
     * below, and {@code journal_entries}' self-references (a reversal and its
     * original) go in the one statement that deletes both.
     */
    private List<JournalReference> inboundJournalReferences() {
        return jdbcTemplate.query(
                "SELECT tc.table_name, kcu.column_name " +
                        "FROM information_schema.table_constraints tc " +
                        "JOIN information_schema.key_column_usage kcu " +
                        "  ON kcu.constraint_name = tc.constraint_name " +
                        " AND kcu.constraint_schema = tc.constraint_schema " +
                        "JOIN information_schema.constraint_column_usage ccu " +
                        "  ON ccu.constraint_name = tc.constraint_name " +
                        " AND ccu.constraint_schema = tc.constraint_schema " +
                        "WHERE tc.constraint_type = 'FOREIGN KEY' " +
                        "  AND tc.table_schema = 'public' " +
                        "  AND ccu.table_name = 'journal_entries' " +
                        "  AND tc.table_name NOT IN ('journal_entries', 'journal_lines') " +
                        "  AND EXISTS (SELECT 1 FROM information_schema.columns c " +
                        "              WHERE c.table_schema = 'public' AND c.table_name = tc.table_name " +
                        "                AND c.column_name = 'tenant_id')",
                (rs, rowNum) -> new JournalReference(rs.getString(1), rs.getString(2)));
    }

    /**
     * Identifiers come from {@code information_schema}, never from a request, but
     * they are quoted anyway (mixed case, reserved words) and an embedded quote is
     * refused outright — the same safety net the purge loop applies to table names.
     */
    private static String quote(String identifier) {
        if (identifier == null || identifier.contains("\"")) {
            throw new IllegalStateException("Refusing to use identifier with a double quote: " + identifier);
        }
        return "\"" + identifier + "\"";
    }

    private void scheduleExternalCleanupAfterCommit(UUID tenantId, List<String> documentUrls) {
        List<String> capturedUrls = List.copyOf(documentUrls);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runExternalCleanup(tenantId, capturedUrls);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runExternalCleanup(tenantId, capturedUrls);
            }
        });
    }

    private void runExternalCleanup(UUID tenantId, List<String> contractDocumentUrls) {
        try {
            contractGenerationService.cleanupTenantDocuments(tenantId, contractDocumentUrls);
        } catch (Exception e) {
            // The database commit has already happened. Keep processing the
            // durable non-contract queue and surface the best-effort contract
            // cleanup failure in logs.
            log.error("deleteTenant({}): post-commit contract cleanup failed", tenantId, e);
        }

        try {
            TenantArtifactCleanupService.CleanupReport report =
                    tenantArtifactCleanupService.processPending(tenantId);
            log.info("deleteTenant({}): post-commit artifact cleanup attempted={} deleted={} skipped={} failed={}",
                    tenantId, report.attempted(), report.deleted(),
                    report.skippedReferenced(), report.failed());
        } catch (Exception e) {
            // Rows remain PENDING when an unexpected queue-level error occurs,
            // so processing can be invoked again safely.
            log.error("deleteTenant({}): post-commit artifact queue processing failed", tenantId, e);
        }
    }
}
