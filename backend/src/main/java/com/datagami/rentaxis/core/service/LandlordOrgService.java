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

    public LandlordOrgService(LandlordOrgRepository repository,
                               UserRepository userRepository,
                               NotificationService notificationService,
                               JdbcTemplate jdbcTemplate,
                               ContractGenerationService contractGenerationService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.jdbcTemplate = jdbcTemplate;
        this.contractGenerationService = contractGenerationService;
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

        scheduleContractCleanupAfterCommit(tenantId, contractDocumentUrls);
        log.info("deleteTenant({}): database purge completed; contract cleanup scheduled after commit", tenantId);
    }

    private void scheduleContractCleanupAfterCommit(UUID tenantId, List<String> documentUrls) {
        List<String> capturedUrls = List.copyOf(documentUrls);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            contractGenerationService.cleanupTenantDocuments(tenantId, capturedUrls);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                contractGenerationService.cleanupTenantDocuments(tenantId, capturedUrls);
            }
        });
    }
}
