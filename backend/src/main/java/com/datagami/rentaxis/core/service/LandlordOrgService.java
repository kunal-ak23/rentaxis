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
        purgeLedger(tenantId, tenantedTables);

        jdbcTemplate.execute((java.sql.Connection conn) -> {
            List<String> remaining = new java.util.ArrayList<>(tenantedTables);
            boolean cyclesBroken = false;
            for (int pass = 1; pass <= MAX_PURGE_PASSES && !remaining.isEmpty(); pass++) {
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
                    // No forward progress. Once — and only once — because the
                    // tables left are in a foreign-key cycle rather than merely in
                    // the wrong order (issue #326, second round). Break it and let
                    // the next pass try again; a second stall is a real one.
                    if (!cyclesBroken) {
                        cyclesBroken = true;
                        breakCycles(conn, stillBlocked, tenantedTables, tenantId);
                        remaining = stillBlocked;
                        continue;
                    }
                    throw new IllegalStateException(
                            "deleteTenant stalled after pass " + pass + " with tables: " + stillBlocked);
                }
                remaining = stillBlocked;
            }
            if (!remaining.isEmpty()) {
                throw new IllegalStateException(
                        "deleteTenant could not clear tables after " + MAX_PURGE_PASSES
                                + " passes: " + remaining);
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
     * How many times the purge re-tries the tables a foreign key blocked. One pass
     * per level of the dependency chain, plus room for the passes after a cycle has
     * been broken; a run that needs more than this is not making progress.
     */
    private static final int MAX_PURGE_PASSES = 12;

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
     *   <li><b>The append-only triggers</b> of changeset 81 refuse every DELETE on
     *       {@code journal_entries} and {@code journal_lines} — "reverse the entry
     *       instead" — which is the right answer for every caller except this one.
     *       A tenant being erased has no books left to reverse into. Changeset 89
     *       gives those trigger functions one exemption: a DELETE passes when the
     *       transaction has named the row's <em>own</em> tenant in
     *       {@code rentaxis.purging_tenant}, which is what this sets below.</li>
     * </ol>
     *
     * <p><b>Why a session variable rather than switching the triggers off.</b>
     * {@code ALTER TABLE … DISABLE TRIGGER} — the first shape of this fix — takes an
     * ACCESS EXCLUSIVE lock that Postgres holds until the transaction <em>commits</em>,
     * not until the matching ENABLE. Since ACCESS EXCLUSIVE conflicts with ACCESS
     * SHARE, every tenant's ledger reads and writes (dashboards, statements, trial
     * balance, postings, the payment webhooks' {@code clearOnline}) would queue
     * behind a super-admin's delete for the whole of it, after first waiting for
     * every in-flight ledger query to finish. It also requires table ownership. The
     * exemption takes no table lock, needs no DDL and no ownership, and is
     * <em>narrower</em>: it admits one tenant's rows for one transaction, where
     * disabling the triggers admits every row of every tenant for the window.</p>
     *
     * <p><b>Set once, never cleared.</b> {@code set_config(…, is_local => true)} is
     * the function form of {@code SET LOCAL}: it reverts at commit or rollback on
     * its own, and it must still be in scope <em>at</em> commit, because the balance
     * check is a deferred constraint trigger whose events fire then — an entry whose
     * lines this removed would otherwise fail it as "not balanced".</p>
     *
     * <p>Tenant-scoped like every other statement here: both DELETEs carry
     * {@code WHERE tenant_id = ?}, and the exemption itself names one tenant, so
     * another tenant's journal is refused in the same words as always throughout.</p>
     */
    private void purgeLedger(UUID tenantId, List<String> tenantedTables) {
        for (ForeignKey reference : inboundJournalReferences(tenantedTables)) {
            int cleared = jdbcTemplate.update(
                    "UPDATE " + quote(reference.table()) + " SET " + quote(reference.column())
                            + " = NULL WHERE tenant_id = ? AND " + quote(reference.column()) + " IS NOT NULL",
                    tenantId);
            if (cleared > 0) {
                log.debug("deleteTenant({}): cleared {} {}.{} journal pointers",
                        tenantId, cleared, reference.table(), reference.column());
            }
        }

        // Bound: SET takes no bind parameters, set_config does, so the tenant id
        // never reaches the statement as text this method assembled.
        jdbcTemplate.queryForObject("SELECT set_config(?, ?, true)", String.class,
                PURGE_SETTING, tenantId.toString());

        for (String table : LEDGER_TABLES) {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM " + quote(table) + " WHERE tenant_id = ?", tenantId);
            log.debug("deleteTenant({}): deleted {} rows from {}", tenantId, deleted, table);
        }
    }

    /**
     * The transaction-local setting changeset 89's trigger functions consult. Its
     * value is the id of the one tenant whose journal may be deleted.
     */
    private static final String PURGE_SETTING = "rentaxis.purging_tenant";

    /** One single-column foreign key in the public schema. */
    private record ForeignKey(String table, String column, String referencedTable, boolean nullable) {
    }

    /**
     * Every foreign-key column in the schema, with what it points at and whether it
     * can be NULLed.
     *
     * <p><b>{@code pg_catalog}, not {@code information_schema}.</b> The SQL-standard
     * views only show constraints on tables the current role owns or has privileges
     * on, so under a least-privilege application role they answer "no foreign keys"
     * and this purge would silently lose its ordering knowledge and fail on the
     * first FK instead. The catalogue has no such filter.</p>
     *
     * <p>Composite keys come back as one row per column. NULLing any one column of a
     * composite foreign key satisfies it (MATCH SIMPLE), so treating them
     * column-wise is correct for the one thing this is used for.</p>
     */
    private List<ForeignKey> foreignKeys() {
        return jdbcTemplate.query(
                "SELECT src.relname, att.attname, tgt.relname, NOT att.attnotnull " +
                        "FROM pg_constraint con " +
                        "JOIN pg_class src ON src.oid = con.conrelid " +
                        "JOIN pg_class tgt ON tgt.oid = con.confrelid " +
                        "JOIN pg_namespace ns ON ns.oid = src.relnamespace " +
                        "JOIN LATERAL unnest(con.conkey) AS k(attnum) ON true " +
                        "JOIN pg_attribute att ON att.attrelid = src.oid AND att.attnum = k.attnum " +
                        "WHERE con.contype = 'f' AND ns.nspname = 'public'",
                (rs, rowNum) -> new ForeignKey(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getBoolean(4)));
    }

    /**
     * Every {@code tenant_id}-carrying column that references {@code journal_entries},
     * excluding the journal's own tables — {@code journal_lines} is deleted outright
     * below, and {@code journal_entries}' self-references (a reversal and its
     * original) go in the one statement that deletes both.
     */
    private List<ForeignKey> inboundJournalReferences(List<String> tenantedTables) {
        return foreignKeys().stream()
                .filter(fk -> "journal_entries".equals(fk.referencedTable()))
                .filter(fk -> !LEDGER_TABLES.contains(fk.table()))
                .filter(fk -> tenantedTables.contains(fk.table()))
                .toList();
    }

    /**
     * Break the foreign-key cycle the pass loop is stuck in, by NULLing this
     * tenant's pointers between the tables that are left (issue #326, second round).
     *
     * <p><b>Why the loop can stall at all.</b> Retrying blocked tables resolves an
     * <em>ordering</em> problem; it cannot resolve a <em>cycle</em>.
     * {@code cheques.penalty_assessment_id} points at {@code penalty_assessments}
     * while {@code penalty_assessments.cheque_id} and
     * {@code collection_cheque_id} point back at {@code cheques}, so neither can
     * ever go first — and {@code leases}, {@code renters}, {@code units},
     * {@code accounts} and {@code properties} queue behind {@code cheques}, with
     * {@code users} behind {@code renters}. That is the whole of the production
     * failure: "stalled after pass 4 with tables: [users, properties, units,
     * renters, accounts, leases, cheques, penalty_assessments]". A tenant with no
     * fine never met it, which is why the first round's lease-only test passed.
     *
     * <p><b>Only when stuck, only nullable columns, only this tenant's rows, only
     * between the tables that are left.</b> A tenant whose tables the loop can
     * order never reaches here; nothing that survives the transaction is
     * modified, because everything touched is deleted moments later — and if the
     * purge fails afterwards the whole transaction rolls back, pointers included.
     * A cycle made of NOT NULL columns cannot be broken this way and stalls with
     * the message it always did, naming the tables so the next person can see it.</p>
     *
     * <p>Discovered rather than listed: a future feature that adds a cycle is
     * handled the day it is added, which is the same reason the journal pointers
     * are discovered above.</p>
     */
    private void breakCycles(java.sql.Connection conn, List<String> blocked,
                             List<String> tenantedTables, UUID tenantId) throws java.sql.SQLException {
        List<ForeignKey> pointers = foreignKeys().stream()
                .filter(ForeignKey::nullable)
                .filter(fk -> blocked.contains(fk.table()))
                .filter(fk -> blocked.contains(fk.referencedTable()))
                .filter(fk -> tenantedTables.contains(fk.table()))
                .toList();
        if (pointers.isEmpty()) {
            log.warn("deleteTenant({}): no nullable pointers to break between {}", tenantId, blocked);
            return;
        }
        log.info("deleteTenant({}): breaking {} foreign-key pointers between the blocked tables {}",
                tenantId, pointers.size(), blocked);
        for (ForeignKey pointer : pointers) {
            try (var ps = conn.prepareStatement(
                    "UPDATE " + quote(pointer.table()) + " SET " + quote(pointer.column())
                            + " = NULL WHERE tenant_id = ? AND " + quote(pointer.column()) + " IS NOT NULL")) {
                ps.setObject(1, tenantId);
                int cleared = ps.executeUpdate();
                log.debug("  cleared {} {}.{} -> {} pointers",
                        cleared, pointer.table(), pointer.column(), pointer.referencedTable());
            }
        }
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
