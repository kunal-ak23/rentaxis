package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public LandlordOrgService(LandlordOrgRepository repository,
                               UserRepository userRepository,
                               NotificationService notificationService,
                               JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.jdbcTemplate = jdbcTemplate;
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

        List<String> tenantedTables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.columns " +
                        "WHERE column_name = 'tenant_id' AND table_schema = 'public'",
                String.class);

        log.info("deleteTenant({}): purging {} tenanted tables", tenantId, tenantedTables.size());

        jdbcTemplate.execute((java.sql.Connection conn) -> {
            List<String> remaining = new java.util.ArrayList<>(tenantedTables);
            for (int pass = 1; pass <= 6 && !remaining.isEmpty(); pass++) {
                List<String> stillBlocked = new java.util.ArrayList<>();
                for (String table : remaining) {
                    java.sql.Savepoint sp = conn.setSavepoint("del_" + table.replaceAll("\\W", "_"));
                    try (var ps = conn.prepareStatement(
                            "DELETE FROM " + table + " WHERE tenant_id = ?")) {
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

        log.info("deleteTenant({}): completed", tenantId);
    }
}
