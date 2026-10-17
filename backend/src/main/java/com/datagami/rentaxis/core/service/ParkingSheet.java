package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Attached parking (2026-10-17 ruling): the optional Parking sheet of the portfolio
 * workbook, read the same way by both dialects (v1 and the cut-over) and in both
 * phases.
 *
 * <p><b>Columns.</b> PropertyName, BuildingName, BayNumber, Level, Covered,
 * AttachedUnitNumber, located by header. The row is the truth for its bay: a blank
 * BuildingName scopes it to all towers, a blank Level clears the level, a blank
 * Covered means covered, and a blank AttachedUnitNumber leaves (or makes) the bay
 * bookable.</p>
 *
 * <p><b>Where names resolve.</b> A property named on the workbook's Properties
 * sheet is new — both dialects refuse a property that already exists — so its
 * buildings and units come from the Units sheet. Any other name must be a property
 * the organisation already has, with its buildings and units from the database;
 * that is how a Parking-only re-import reaches existing bays. In the persist phase
 * everything the workbook wrote is already in the database, so the same rules run
 * against the database alone ({@link SheetPlaces#NONE}).</p>
 *
 * <p><b>Re-import.</b> A bay the property already has (same BayNumber) is updated
 * in place, never duplicated.</p>
 */
public final class ParkingSheet {

    static final int MAX_LEN = 32;

    private static final Set<String> TRUE_VALUES = Set.of("true", "yes", "1");
    private static final Set<String> FALSE_VALUES = Set.of("false", "no", "0");

    private ParkingSheet() {
    }

    /** One non-empty row, with its 1-based row number. {@code covered} is never null. */
    public record Row(int rowNum, String property, String building, String bay, String level,
                      boolean covered, String unit) {
    }

    /** A row with its names turned into ids (null where the row named none, or in the validation of a new property). */
    public record Resolved(Row row, UUID propertyId, UUID buildingId, UUID unitId) {
    }

    /** A unit as the matching needs it: its building's lower-cased name ("" for none) and its normalised number. */
    public record UnitRef(UUID id, String building, String number) {
    }

    /** An existing bay: its id and the unit it is attached to now. */
    public record SpotRef(UUID id, UUID attachedUnitId) {
    }

    /** What the rules ask the database. Every implementation must be tenant-scoped. */
    public interface Db {
        /** The organisation's existing property of that name (case-insensitive), or empty. */
        Optional<UUID> property(String name);

        /** The property's buildings, lower-cased name to id. */
        Map<String, UUID> buildings(UUID propertyId);

        List<UnitRef> units(UUID propertyId);

        /** The property's bay with exactly this number, or empty. */
        Optional<SpotRef> spot(UUID propertyId, String bayNumber);

        /** Whether the bay has a PENDING or APPROVED booking reaching today or later. */
        boolean openBookingsReaching(UUID spotId);
    }

    /** The properties, buildings and units the workbook itself creates. */
    public record SheetPlaces(Set<String> properties, Map<String, Set<String>> buildings,
                              Map<String, List<UnitRef>> units) {

        public static final SheetPlaces NONE = new SheetPlaces(Set.of(), Map.of(), Map.of());

        /** From the Properties sheet's first column and the Units sheet's first three (both dialects). */
        public static SheetPlaces of(Workbook wb) {
            Set<String> properties = new HashSet<>();
            Map<String, Set<String>> buildings = new HashMap<>();
            Map<String, List<UnitRef>> units = new HashMap<>();
            Sheet props = wb.getSheet("Properties");
            if (props != null) {
                for (int i = 1; i <= props.getLastRowNum(); i++) {
                    org.apache.poi.ss.usermodel.Row r = props.getRow(i);
                    if (r == null || SheetCells.isRowEmpty(r)) continue;
                    String name = SheetCells.getCellString(r, 0).trim();
                    if (!name.isEmpty()) properties.add(lower(name));
                }
            }
            Sheet unitSheet = wb.getSheet("Units");
            if (unitSheet != null) {
                for (int i = 1; i <= unitSheet.getLastRowNum(); i++) {
                    org.apache.poi.ss.usermodel.Row r = unitSheet.getRow(i);
                    if (r == null || SheetCells.isRowEmpty(r)) continue;
                    String property = lower(SheetCells.getCellString(r, 0).trim());
                    String building = lower(SheetCells.getCellString(r, 1).trim());
                    String number = SheetCells.getCellString(r, 2).trim();
                    if (property.isEmpty() || number.isEmpty()) continue;
                    if (!building.isEmpty()) buildings.computeIfAbsent(property, k -> new HashSet<>()).add(building);
                    units.computeIfAbsent(property, k -> new ArrayList<>())
                            .add(new UnitRef(null, building, unitKey(number)));
                }
            }
            return new SheetPlaces(properties, buildings, units);
        }
    }

    /**
     * The sheet's rows, with the checks that need nothing but the row: required cells,
     * lengths and the Covered value. A row with such an error is reported and left out.
     */
    public static List<Row> read(Sheet sheet, List<ImportErrorDTO> errors) {
        SheetCells.HeaderIndex hi = new SheetCells.HeaderIndex(sheet);
        // Every column, not just the required cells: the row is the truth for its bay, so a
        // missing Level or AttachedUnitNumber column would read as blank and clear the
        // level or detach the bay on re-import. A missing column is a file error instead.
        boolean missing = false;
        for (String column : PortfolioSheets.PARKING_HEADERS) {
            if (hi.col(column) < 0) {
                missing = true;
                errors.add(ImportErrorDTO.file(PortfolioSheets.PARKING, column,
                        "Sheet '" + PortfolioSheets.PARKING + "' has no " + column + " column"));
            }
        }
        if (missing) return List.of();

        List<Row> rows = new ArrayList<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            org.apache.poi.ss.usermodel.Row r = sheet.getRow(i);
            if (r == null || SheetCells.isRowEmpty(r)) continue;
            int rowNum = i + 1;
            String property = SheetCells.cell(r, hi, "PropertyName").trim();
            String building = SheetCells.cell(r, hi, "BuildingName").trim();
            String bay = SheetCells.cell(r, hi, "BayNumber").trim();
            String level = SheetCells.cell(r, hi, "Level").trim();
            String coveredRaw = SheetCells.cell(r, hi, "Covered").trim().toLowerCase(Locale.ROOT);
            String unit = SheetCells.cell(r, hi, "AttachedUnitNumber").trim();

            int before = errors.size();
            if (property.isEmpty()) {
                errors.add(error(rowNum, "PropertyName", "PropertyName is required"));
            }
            if (bay.isEmpty()) {
                errors.add(error(rowNum, "BayNumber", "BayNumber is required"));
            } else if (bay.length() > MAX_LEN) {
                errors.add(error(rowNum, "BayNumber", "BayNumber is longer than " + MAX_LEN + " characters"));
            }
            if (level.length() > MAX_LEN) {
                errors.add(error(rowNum, "Level", "Level is longer than " + MAX_LEN + " characters"));
            }
            boolean covered = true;
            if (FALSE_VALUES.contains(coveredRaw)) {
                covered = false;
            } else if (!coveredRaw.isEmpty() && !TRUE_VALUES.contains(coveredRaw)) {
                errors.add(error(rowNum, "Covered", "Covered must be TRUE or FALSE"));
            }
            if (errors.size() == before) {
                rows.add(new Row(rowNum, property, building, bay, level, covered, unit));
            }
        }
        return rows;
    }

    /**
     * Turns the rows' names into ids and reports every row whose property, building or
     * unit is unknown, every bay listed twice for one property, and every existing bay
     * the row would attach while a booking still holds it.
     */
    public static List<Resolved> resolve(List<Row> rows, SheetPlaces sheet, Db db, List<ImportErrorDTO> errors) {
        List<Resolved> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, Optional<UUID>> dbProperties = new LinkedHashMap<>();
        Map<UUID, Map<String, UUID>> dbBuildings = new HashMap<>();
        Map<UUID, List<UnitRef>> dbUnits = new HashMap<>();

        for (Row row : rows) {
            String propertyKey = lower(row.property());
            if (!seen.add(propertyKey + "|" + row.bay())) {
                errors.add(error(row.rowNum(), "BayNumber",
                        "Bay '" + row.bay() + "' is listed twice for property '" + row.property() + "'"));
                continue;
            }

            boolean onSheet = sheet.properties().contains(propertyKey);
            UUID propertyId = null;
            Map<String, UUID> buildings;
            List<UnitRef> units;
            if (onSheet) {
                buildings = new HashMap<>();
                for (String b : sheet.buildings().getOrDefault(propertyKey, Set.of())) buildings.put(b, null);
                units = sheet.units().getOrDefault(propertyKey, List.of());
            } else {
                Optional<UUID> found = dbProperties.computeIfAbsent(propertyKey, k -> db.property(row.property()));
                if (found.isEmpty()) {
                    errors.add(error(row.rowNum(), "PropertyName",
                            "Property '" + row.property() + "' is not on the Properties sheet and does not"
                                    + " exist in this organisation. If you do not use the Parking sheet,"
                                    + " delete its sample rows"));
                    continue;
                }
                propertyId = found.get();
                UUID pid = propertyId;
                buildings = dbBuildings.computeIfAbsent(pid, db::buildings);
                units = dbUnits.computeIfAbsent(pid, db::units);
            }

            UUID buildingId = null;
            String buildingKey = lower(row.building());
            if (!buildingKey.isEmpty()) {
                if (!buildings.containsKey(buildingKey)) {
                    errors.add(error(row.rowNum(), "BuildingName",
                            "Building '" + row.building() + "' is not in property '" + row.property() + "'"));
                    continue;
                }
                buildingId = buildings.get(buildingKey);
            }

            UUID unitId = null;
            if (!row.unit().isEmpty()) {
                String number = unitKey(row.unit());
                List<UnitRef> matches = units.stream()
                        .filter(u -> u.number().equals(number))
                        .filter(u -> buildingKey.isEmpty() || u.building().equals(buildingKey))
                        .toList();
                if (matches.isEmpty()) {
                    errors.add(error(row.rowNum(), "AttachedUnitNumber",
                            "Unit '" + row.unit() + "' is not in property '" + row.property() + "'"
                                    + (buildingKey.isEmpty() ? "" : ", building '" + row.building() + "'")));
                    continue;
                }
                if (matches.size() > 1) {
                    errors.add(error(row.rowNum(), "AttachedUnitNumber",
                            "Unit '" + row.unit() + "' is in more than one building of '" + row.property()
                                    + "'; give its BuildingName"));
                    continue;
                }
                unitId = matches.getFirst().id();
            }

            if (propertyId != null && unitId != null) {
                Optional<SpotRef> existing = db.spot(propertyId, row.bay());
                if (existing.isPresent() && !unitId.equals(existing.get().attachedUnitId())
                        && db.openBookingsReaching(existing.get().id())) {
                    errors.add(error(row.rowNum(), "AttachedUnitNumber",
                            "Bay '" + row.bay() + "' has an approved or pending booking for today or later;"
                                    + " it cannot be attached to a unit until that booking is released,"
                                    + " rejected or ended"));
                    continue;
                }
            }
            out.add(new Resolved(row, propertyId, buildingId, unitId));
        }
        return out;
    }

    /** How unit numbers are compared: Arabic-Indic digits folded, case ignored (as the Units sheet does). */
    static String unitKey(String unitNumber) {
        return lower(UnitRules.normalise(unitNumber.trim()));
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static ImportErrorDTO error(int rowNum, String field, String message) {
        return new ImportErrorDTO(PortfolioSheets.PARKING, rowNum, field, message);
    }
}
