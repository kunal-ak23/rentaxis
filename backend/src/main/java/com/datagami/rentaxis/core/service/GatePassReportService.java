package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.GatePassDtos.GatePassReportRow;
import com.datagami.rentaxis.core.security.PropertyScope;
import com.datagami.rentaxis.core.util.Search;
import com.datagami.rentaxis.domain.entity.GatePass;
import com.datagami.rentaxis.domain.entity.GatePassScan;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.repository.GatePassScanRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The gate-pass report's reads: the paged report (scale #15) and the batched display
 * lookups {@code GatePassController} also uses for the full {@code /report}.
 *
 * <p>Role checks and the "may this manager name this property" check stay in the
 * controller, as for every gate-pass endpoint. What lives here is the scoping that has
 * to be in SQL for a page to be right: the property filter and a property manager's
 * buildings, both applied before {@code LIMIT}/{@code OFFSET} and in the count.</p>
 */
@Service
public class GatePassReportService {

    private final GatePassScanRepository scanRepository;
    private final UnitRepository unitRepository;
    private final UserRepository userRepository;
    private final PropertyScope propertyScope;

    public GatePassReportService(GatePassScanRepository scanRepository, UnitRepository unitRepository,
                                 UserRepository userRepository, PropertyScope propertyScope) {
        this.scanRepository = scanRepository;
        this.unitRepository = unitRepository;
        this.userRepository = userRepository;
        this.propertyScope = propertyScope;
    }

    /**
     * One page of {@code /report}'s rows, newest first ({@code scannedAt desc, id}).
     *
     * <p>A property manager with no {@code propertyId} gets their assigned buildings only
     * ({@link PropertyScope#scopedPropertyIds()}: {@code null} = unrestricted, empty =
     * nothing) — what {@code /report} does row by row with {@code canAccessProperty}.
     * The caller has already refused a {@code propertyId} outside the manager's scope.
     */
    @Transactional(readOnly = true)
    public Page<GatePassReportRow> reportPage(UUID tenantId, Instant from, Instant to, UUID propertyId,
                                              int page, int size) {
        Pageable pageable = Search.page(page, size, Sort.unsorted());
        List<UUID> scoped = propertyScope.scopedPropertyIds();
        Page<Object[]> rows = scanRepository.findReportPage(tenantId, from, to, propertyId, scoped == null,
                Search.scopeIds(scoped), pageable);

        // The page's rows only: at most one page of units and guards, one query each.
        List<GatePass> passes = rows.getContent().stream().map(r -> (GatePass) r[1]).toList();
        List<GatePassScan> scans = rows.getContent().stream().map(r -> (GatePassScan) r[0]).toList();
        Map<UUID, String> unitNumbers = unitNumbers(passes.stream().map(GatePass::getUnitId).toList());
        Map<UUID, String> guardNames = guardNames(tenantId, scans.stream().map(GatePassScan::getScannedByUserId).toList());

        return rows.map(r -> {
            GatePassScan scan = (GatePassScan) r[0];
            GatePass pass = (GatePass) r[1];
            return new GatePassReportRow(scan.getId(), scan.getScannedAt(), scan.getDirection(), scan.getResult(),
                    scan.getRejectionReason(), scan.getScannedByUserId(),
                    guardNames.get(scan.getScannedByUserId()), pass.getId(), pass.getPropertyId(),
                    unitNumbers.get(pass.getUnitId()), pass.getGuestName(), pass.getGuestPhone(),
                    pass.getVehicleNumber(), pass.getPurpose(), pass.getPassType());
        });
    }

    /** Batch unit-number lookup for display; missing units simply have no number. */
    @Transactional(readOnly = true)
    public Map<UUID, String> unitNumbers(List<UUID> unitIds) {
        List<UUID> distinct = unitIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return new HashMap<>(); // not Map.of(): its get(null) throws
        }
        Map<UUID, String> byId = new HashMap<>();
        for (Unit unit : unitRepository.findAllById(distinct)) {
            byId.put(unit.getId(), unit.getUnitNumber());
        }
        return byId;
    }

    /**
     * Batch display-name lookup for the guards who performed the scans.
     *
     * <p>Exists because the report's central question is "who scanned this", and the
     * row carried only a UUID. The role that most needs the answer is the one that
     * cannot get it: {@code PROPERTY_MANAGER} is allowed on this report but not on
     * {@code /api/admin/users} (SUPER_ADMIN/TENANT_ADMIN only), so resolving the id
     * client-side 403s. Resolving it here is the only place a manager can be told.
     *
     * <p>Tenant-scoped in the SQL rather than relying on the {@code tenantFilter}
     * aspect. Note this rejects {@code UserRepository.findDisplayNameById}, which
     * deliberately bypasses the filter with native SQL to attribute cross-tenant
     * SUPER_ADMIN actions — and is single-id besides. A scan is always performed by a
     * guard inside the tenant that owns it, so there is no cross-tenant case to serve.
     *
     * <p>A miss — deleted user, or an id from outside the tenant — yields no entry, so
     * the row's name is null and the client falls back to the id it still carries. It
     * does not skip the row: a scan that happened is part of the audit trail whether
     * or not the account behind it still exists.
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> guardNames(UUID tenantId, List<UUID> userIds) {
        List<UUID> distinct = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return new HashMap<>(); // not Map.of(): its get(null) throws, and a detached guard id is null
        }
        Map<UUID, String> byId = new HashMap<>();
        for (User user : userRepository.findByTenantIdAndIdIn(tenantId, distinct)) {
            byId.put(user.getId(), user.getName());
        }
        return byId;
    }
}
