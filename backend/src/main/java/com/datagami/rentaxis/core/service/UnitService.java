package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.util.Loaded;
import com.datagami.rentaxis.api.dto.UnitRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class UnitService {

    private final UnitRepository repository;
    private final TenantReferences refs;
    private final com.datagami.rentaxis.core.security.PropertyScope propertyScope;
    private final com.datagami.rentaxis.domain.repository.LeaseRepository leaseRepository;

    public UnitService(UnitRepository repository, TenantReferences refs,
                       com.datagami.rentaxis.core.security.PropertyScope propertyScope,
                       com.datagami.rentaxis.domain.repository.LeaseRepository leaseRepository) {
        this.propertyScope = propertyScope;
        this.leaseRepository = leaseRepository;
        this.repository = repository;
        this.refs = refs;
    }

    /**
     * Creates a unit from a request body. The property and building arrive as ids
     * and are resolved here, inside the transaction and the caller's tenant, before
     * anything is saved: another tenant's (or a missing) property or building is a
     * 404, and a building that is not on the property is a 400.
     */
    @Transactional
    public Unit createUnit(UnitRequest r) {
        // Break-it R3 ops3 F2: the field rules the CSV upload shares (UnitRules).
        List<String> problems = UnitRules.fieldProblems(r.unitNumber(), r.sizeSqft(), r.expectedRent(), r.actualRent());
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems.get(0));
        }
        if (r.property() == null) {
            throw new BusinessRuleViolationException("property.id is required");
        }
        Property property = refs.propertyOrNull(r.property());
        Building building = r.building() == null ? null : refs.building(r.building().id(), property);

        // Break-it R3 ops3 F1: one unit number once per building (or per property
        // for a unit in no building), compared normalised.
        String number = UnitRules.display(r.unitNumber());
        if (takenNumbers(property, building).contains(UnitRules.normalise(number))) {
            throw numberTaken(number, property, building, null);
        }

        Unit u = new Unit();
        u.setProperty(property);
        u.setBuilding(building);
        u.setUnitNumber(number);
        if (r.type() != null) u.setType(r.type());
        u.setSizeSqft(r.sizeSqft());
        if (r.status() != null) u.setStatus(r.status());
        if (r.expectedRent() != null) u.setExpectedRent(r.expectedRent());
        if (r.actualRent() != null) u.setActualRent(r.actualRent());
        u.setCurrentTenantName(r.currentTenantName());
        Unit saved = repository.save(u);
        flushTranslatingNumberClash(number, property, building);
        return Loaded.with(saved, Unit::getProperty, Unit::getBuilding);
    }

    /** The normalised unit numbers already taken in the scope a new unit in {@code building} (or none) joins. */
    private java.util.Set<String> takenNumbers(Property property, Building building) {
        UUID tenantId = property.getTenantId();
        List<String> numbers = building != null
                ? repository.unitNumbersInBuilding(tenantId, building.getId())
                : repository.unitNumbersWithoutBuilding(tenantId, property.getId());
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (String n : numbers) keys.add(UnitRules.normalise(n));
        return keys;
    }

    /** "Unit 101 already exists in Tower A" — a 400 naming the clash, with a translatable code. */
    static BusinessRuleViolationException numberTaken(String number, Property property, Building building, Integer row) {
        String place = building != null ? building.getNameEn() : property.getNameEn();
        String message = (row != null ? "Row " + row + ": " : "")
                + "Unit " + number + " already exists in " + place;
        java.util.Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("unitNumber", number);
        args.put("place", place);
        return new BusinessRuleViolationException(message, UNIT_NUMBER_TAKEN, args);
    }

    public static final String UNIT_NUMBER_TAKEN = "unit.numberTaken";

    /**
     * Flushes now so that a second tab which passed the check at the same moment
     * meets changeset 157's unique index here, and gets the same readable refusal
     * rather than a constraint name.
     */
    private void flushTranslatingNumberClash(String number, Property property, Building building) {
        try {
            repository.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            if (isNumberIndexViolation(e)) {
                throw numberTaken(number, property, building, null);
            }
            throw e;
        }
    }

    static boolean isNumberIndexViolation(Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth++ < 16; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("uq_units_number_")) return true;
        }
        return false;
    }

    /**
     * Saves a unit built in code. Internal and test use only: its property and
     * building must already be managed rows of the caller's tenant. Request bodies
     * go through {@link #createUnit(UnitRequest)}.
     */
    @Transactional
    public Unit createUnit(Unit unit) {
        return repository.save(unit);
    }

    @Transactional(readOnly = true)
    public List<Unit> getUnitsByProperty(UUID propertyId) {
        // A property manager lists units of their own buildings only (audit D-F7):
        // another building's list, with its current tenants' names, is empty.
        if (!propertyScope.canAccessProperty(propertyId)) {
            return List.of();
        }
        return withOccupancy(repository.findByPropertyId(propertyId));
    }

    @Transactional(readOnly = true)
    public List<Unit> getAllUnits() {
        return withOccupancy(propertyScope.filter(repository.findAll(),
                u -> u.getProperty() != null ? u.getProperty().getId() : null));
    }

    private static final org.springframework.data.domain.Sort UNIT_ORDER = org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Order.asc("property.nameEn"),
            org.springframework.data.domain.Sort.Order.asc("unitNumber"),
            org.springframework.data.domain.Sort.Order.asc("id"));

    /**
     * {@code GET /units/paged} (scale P1-3): the same rows as {@code GET /units} — occupancy
     * filled, property and building on each — filtered, searched and paged in the database.
     * A property manager sees their buildings only; naming another building is an empty page.
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Unit> searchPaged(String q, UUID propertyId,
            com.datagami.rentaxis.domain.entity.enums.UnitStatus status, String floor, int page, int size) {
        return searchPaged(q, propertyId, null, status, floor, page, size);
    }

    /** S16-02: {@code buildingId} narrows to one tower (Building) of the property. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Unit> searchPaged(String q, UUID propertyId, UUID buildingId,
            com.datagami.rentaxis.domain.entity.enums.UnitStatus status, String floor, int page, int size) {
        org.springframework.data.domain.Pageable pageable = com.datagami.rentaxis.core.util.Search.page(page, size, UNIT_ORDER);
        if (propertyId != null && !propertyScope.canAccessProperty(propertyId)) {
            return org.springframework.data.domain.Page.empty(pageable);
        }
        List<UUID> scoped = propertyScope.scopedPropertyIds();
        org.springframework.data.domain.Page<Unit> rows = repository.searchPaged(
                com.datagami.rentaxis.core.util.Search.requireTenant(), propertyId, buildingId, status,
                floor == null || floor.isBlank() ? null : floor.trim() + "%",
                com.datagami.rentaxis.core.util.Search.like(q), scoped == null,
                com.datagami.rentaxis.core.util.Search.scopeIds(scoped), pageable);
        withOccupancy(rows.getContent());
        return rows;
    }

    /** {@code GET /units/search} (scale P1-6): the first {@code limit} matches for a picker. */
    @Transactional(readOnly = true)
    public List<com.datagami.rentaxis.api.dto.lookup.UnitOptionDTO> search(String q, UUID propertyId,
            com.datagami.rentaxis.domain.entity.enums.UnitStatus status, int limit) {
        if (propertyId != null && !propertyScope.canAccessProperty(propertyId)) {
            return List.of();
        }
        List<UUID> scoped = propertyScope.scopedPropertyIds();
        return repository.searchPaged(com.datagami.rentaxis.core.util.Search.requireTenant(), propertyId,
                        null, status, null, com.datagami.rentaxis.core.util.Search.like(q), scoped == null,
                        com.datagami.rentaxis.core.util.Search.scopeIds(scoped),
                        org.springframework.data.domain.PageRequest.of(0, com.datagami.rentaxis.core.util.Search.limit(limit), UNIT_ORDER))
                .getContent().stream().map(UnitService::option).toList();
    }

    /** {@code GET /units/names} (scale P1-6): the named units the caller may see, at most 200 ids. */
    @Transactional(readOnly = true)
    public List<com.datagami.rentaxis.api.dto.lookup.UnitOptionDTO> names(List<UUID> ids) {
        List<UUID> wanted = com.datagami.rentaxis.core.util.Search.names(ids);
        if (wanted.isEmpty()) return List.of();
        List<UUID> scoped = propertyScope.scopedPropertyIds();
        return repository.findNamed(com.datagami.rentaxis.core.util.Search.requireTenant(), wanted,
                        scoped == null, com.datagami.rentaxis.core.util.Search.scopeIds(scoped))
                .stream().map(UnitService::option).toList();
    }

    private static com.datagami.rentaxis.api.dto.lookup.UnitOptionDTO option(Unit u) {
        Property p = u.getProperty();
        Building b = u.getBuilding();
        return new com.datagami.rentaxis.api.dto.lookup.UnitOptionDTO(u.getId(), u.getUnitNumber(),
                p == null ? null : p.getId(), p == null ? null : p.getNameEn(),
                p == null || p.getType() == null ? null : p.getType().name(),
                b == null ? null : b.getId(), b == null ? null : b.getNameEn(),
                u.getStatus() == null ? null : u.getStatus().name());
    }

    /**
     * F14-01: fills each unit's date-based occupancy from its posted leases, in one
     * query. A lease posted today for next month reserves the unit; it occupies it
     * from its start date.
     */
    List<Unit> withOccupancy(List<Unit> units) {
        if (units.isEmpty()) return units;
        java.time.LocalDate today = java.time.LocalDate.now();
        java.util.Map<UUID, List<com.datagami.rentaxis.domain.entity.Lease>> byUnit = new java.util.HashMap<>();
        leaseRepository.currentOrUpcomingOnUnits(units.stream().map(Unit::getId).toList(), today)
                .forEach(l -> byUnit.computeIfAbsent(l.getUnit().getId(), k -> new java.util.ArrayList<>()).add(l));
        for (Unit u : units) {
            List<com.datagami.rentaxis.domain.entity.Lease> leases = byUnit.getOrDefault(u.getId(), List.of());
            boolean covered = leases.stream().anyMatch(l -> l.getStartDate() == null || !l.getStartDate().isAfter(today));
            com.datagami.rentaxis.domain.entity.Lease next = leases.stream()
                    .filter(l -> l.getStartDate() != null && l.getStartDate().isAfter(today)
                            && l.getStatus() != com.datagami.rentaxis.domain.entity.enums.LeaseStatus.RENEWED)
                    .min(java.util.Comparator.comparing(com.datagami.rentaxis.domain.entity.Lease::getStartDate))
                    .orElse(null);
            u.setOccupancy(occupancyOf(u.getStatus(), covered, next != null));
            u.setNextLeaseStart(next != null ? next.getStartDate() : null);
            u.setNextTenantName(next != null && next.getRenter() != null ? next.getRenter().getNameEn() : null);
        }
        // Serialised with their property and building after this transaction ends (OSIV off).
        return Loaded.all(units, Unit::getProperty, Unit::getBuilding);
    }

    static String occupancyOf(com.datagami.rentaxis.domain.entity.enums.UnitStatus status, boolean covered,
                              boolean upcoming) {
        if (covered) return "OCCUPIED";
        if (upcoming) return "RESERVED";
        return status == com.datagami.rentaxis.domain.entity.enums.UnitStatus.MAINTENANCE ? "MAINTENANCE" : "VACANT";
    }

    /**
     * The CSV import. {@code propertyId} and {@code buildingId} are request
     * parameters, so they are resolved in the caller's tenant exactly as
     * {@link #createUnit(UnitRequest)} resolves its body: they used to become
     * id-only stubs that Hibernate wrote as foreign keys unchecked. Every row
     * takes the resolved property and building; nothing a row carries overrides
     * them.
     */
    @Transactional
    public List<Unit> bulkCreateUnits(UUID propertyId, UUID buildingId, List<Unit> units) {
        return bulkCreateUnits(propertyId, buildingId, units, null);
    }

    /**
     * Break-it R3 ops3 F1/F2: every row meets the rules Add Unit applies — the field
     * rules ({@link UnitRules#fieldProblems}) and one unit number once per building
     * (or per property), against the units already there and the file's other rows.
     * Any refused row refuses the file: nothing is saved, and
     * {@link UnitRowsRejectedException} lists every row's reason.
     *
     * @param rowNumbers the file row of each unit, for the messages; null numbers them from row 2
     */
    @Transactional
    public List<Unit> bulkCreateUnits(UUID propertyId, UUID buildingId, List<Unit> units, List<Integer> rowNumbers) {
        Property property = refs.property(propertyId);
        Building building = buildingId == null ? null : refs.building(buildingId, property);
        java.util.Set<String> taken = takenNumbers(property, building);
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        List<String> errors = new java.util.ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            Unit unit = units.get(i);
            int row = rowNumbers != null ? rowNumbers.get(i) : i + 2;
            for (String problem : UnitRules.fieldProblems(unit.getUnitNumber(), unit.getSizeSqft(),
                    unit.getExpectedRent(), unit.getActualRent())) {
                errors.add("Row " + row + ": " + problem);
            }
            String number = UnitRules.display(unit.getUnitNumber());
            if (number == null || number.isEmpty()) continue;
            String key = UnitRules.normalise(number);
            if (taken.contains(key)) {
                errors.add(numberTaken(number, property, building, row).getMessage());
            } else if (seen.containsKey(key)) {
                errors.add("Row " + row + ": Unit " + number + " is listed twice in this file (also row "
                        + seen.get(key) + ")");
            } else {
                seen.put(key, row);
            }
            unit.setUnitNumber(number);
            unit.setProperty(property);
            unit.setBuilding(building);
        }
        if (!errors.isEmpty()) {
            throw new UnitRowsRejectedException(errors);
        }
        List<Unit> saved = repository.saveAll(units);
        try {
            repository.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            if (isNumberIndexViolation(e)) {
                // Another upload or Add Unit took one of these numbers meanwhile.
                throw new UnitRowsRejectedException(List.of(
                        "A unit number in this file was just added elsewhere; nothing was saved. Upload again to see which."));
            }
            throw e;
        }
        return saved;
    }
}
