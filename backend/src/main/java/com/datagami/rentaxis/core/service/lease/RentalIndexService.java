package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.rentalindex.RentalIndexImportResult;
import com.datagami.rentaxis.api.dto.rentalindex.RentalIndexRangeDTO;
import com.datagami.rentaxis.api.dto.rentalindex.RentalIndexRangeInput;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.TenantReferences;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.RentalIndexRange;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.UnitType;
import com.datagami.rentaxis.domain.repository.RentalIndexRangeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The DLD rental index ranges a manager enters per property × unit type × year, and
 * the Decree 43 cap a renewal derives from them ({@link Decree43}).
 *
 * <p>The ranges are typed in from the DLD calculator, never fetched: the calculator
 * sits behind a reCAPTCHA proxy that must not be called or scraped, and the official
 * API is priced out of reach. So the cap is an estimate from whatever was entered,
 * shown with the date it was entered on and a link to verify, and it never blocks a
 * renewal.</p>
 */
@Service
public class RentalIndexService {

    /** The oldest and newest contract-end years a range may be filed under. */
    static final int MIN_YEAR = 2000;
    static final int MAX_YEAR = 2100;

    private final RentalIndexRangeRepository ranges;
    private final TenantReferences refs;

    public RentalIndexService(RentalIndexRangeRepository ranges, TenantReferences refs) {
        this.ranges = ranges;
        this.refs = refs;
    }

    /**
     * What the renewal preview and the renter's renewal line carry: the range it was
     * computed from, the decree's answer, and what the operator needs to verify it.
     */
    public record ReraCap(int indexYear, BigDecimal indexMin, BigDecimal indexMax, BigDecimal indexAverage,
                          BigDecimal belowAveragePercent, BigDecimal capPercent, BigDecimal maxRent,
                          boolean exceedsCap, Instant indexEnteredAt, String dewaPremiseNumber) {
    }

    @Transactional(readOnly = true)
    public List<RentalIndexRangeDTO> list(UUID propertyId) {
        Property property = refs.property(propertyId);
        return ranges.findByPropertyIdOrderByIndexYearDescUnitTypeAsc(property.getId()).stream()
                .map(RentalIndexRangeDTO::of).toList();
    }

    /** Add the range for (unit type, year), or replace the one already there: the key is the identity. */
    @Transactional
    public RentalIndexRangeDTO upsert(UUID propertyId, RentalIndexRangeInput in, UUID callerId) {
        Property property = refs.property(propertyId);
        return RentalIndexRangeDTO.of(save(property, validate(property, in), callerId));
    }

    /**
     * A sheet of ranges, all or nothing: every row is checked first and one bad row
     * refuses the whole import with "Row N: why" for each, so a half-filed sheet
     * never leaves one bedroom category on last year's figures.
     */
    @Transactional
    public RentalIndexImportResult importRows(UUID propertyId, List<RentalIndexRangeInput> rows, UUID callerId) {
        Property property = refs.property(propertyId);
        if (rows == null || rows.isEmpty()) {
            throw new BusinessRuleViolationException("The sheet has no rows");
        }
        List<String> errors = new ArrayList<>();
        List<Valid> valid = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 2; // the sheet's header is row 1
            try {
                Valid v = validate(property, rows.get(i));
                if (!seen.add(v.unitType() + "/" + v.year())) {
                    throw new BusinessRuleViolationException(v.unitType() + " " + v.year() + " appears twice");
                }
                valid.add(v);
            } catch (BusinessRuleViolationException e) {
                errors.add("Row " + rowNo + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            return new RentalIndexImportResult(0, errors);
        }
        for (Valid v : valid) save(property, v, callerId);
        return new RentalIndexImportResult(valid.size(), List.of());
    }

    @Transactional
    public void delete(UUID propertyId, UUID id) {
        Property property = refs.property(propertyId);
        RentalIndexRange r = ranges.findById(id)
                .filter(x -> x.getProperty().getId().equals(property.getId()))
                .orElseThrow(() -> new NotFoundException("Rental index range not found"));
        ranges.delete(r);
    }

    /**
     * The cap for a unit, or empty when no range matches its property, its type and
     * the successor's end year. Callers hold the transaction (the tenant filter is
     * only on inside one); {@code LeaseRenewalService.plan} and the renter summary do.
     */
    public Optional<ReraCap> capFor(Unit unit, int endYear, BigDecimal currentRent, BigDecimal proposedRent) {
        if (unit == null || unit.getProperty() == null || unit.getType() == null) return Optional.empty();
        return ranges.findByPropertyIdAndUnitTypeAndIndexYear(unit.getProperty().getId(), unit.getType(), endYear)
                .flatMap(r -> {
                    Decree43.Result d = Decree43.evaluate(r.getMinValue(), r.getMaxValue(), currentRent, proposedRent);
                    if (d == null) return Optional.empty();
                    return Optional.of(new ReraCap(r.getIndexYear(), r.getMinValue(), r.getMaxValue(),
                            d.indexAverage(), d.belowAveragePercent(), d.capPercent(), d.maxRent(), d.exceedsCap(),
                            r.getEnteredAt(), unit.getDewaPremiseNumber()));
                });
    }

    private record Valid(UnitType unitType, int year, BigDecimal min, BigDecimal max, String note) {
    }

    private Valid validate(Property property, RentalIndexRangeInput in) {
        if (in == null) throw new BusinessRuleViolationException("Empty row");
        String building = in.building() == null ? "" : in.building().trim();
        if (!building.isEmpty() && !building.equalsIgnoreCase(property.getNameEn() == null ? "" : property.getNameEn().trim())) {
            throw new BusinessRuleViolationException("Building \"" + building + "\" is not " + property.getNameEn());
        }
        if (in.unitType() == null || in.unitType().isBlank()) {
            throw new BusinessRuleViolationException("Unit type is required");
        }
        UnitType type = parseUnitType(in.unitType());
        if (type == null) {
            throw new BusinessRuleViolationException("Unknown unit type \"" + in.unitType().trim() + "\"");
        }
        if (in.indexYear() == null || in.indexYear() < MIN_YEAR || in.indexYear() > MAX_YEAR) {
            throw new BusinessRuleViolationException("Year must be between " + MIN_YEAR + " and " + MAX_YEAR);
        }
        if (in.minValue() == null || in.maxValue() == null || in.minValue().signum() <= 0) {
            throw new BusinessRuleViolationException("Min and max must be positive amounts");
        }
        if (in.maxValue().compareTo(in.minValue()) < 0) {
            throw new BusinessRuleViolationException("Max must not be below min");
        }
        if (in.sourceNote() != null && in.sourceNote().length() > 500) {
            throw new BusinessRuleViolationException("Source note is too long (500 characters)");
        }
        return new Valid(type, in.indexYear(), in.minValue().setScale(2, java.math.RoundingMode.HALF_UP),
                in.maxValue().setScale(2, java.math.RoundingMode.HALF_UP),
                in.sourceNote() == null || in.sourceNote().isBlank() ? null : in.sourceNote().trim());
    }

    /**
     * Our enum names, plus what a sheet typed off the DLD page is likely to say:
     * "Studio", "1 BR", "1BHK", "2 bed", "Penthouse", "Retail", "Office".
     */
    static UnitType parseUnitType(String raw) {
        String s = raw.trim().toUpperCase().replaceAll("[\\s_\\-]", "");
        try {
            return UnitType.valueOf(s);
        } catch (IllegalArgumentException ignored) {
            // fall through to the sheet spellings
        }
        if (s.startsWith("STUDIO")) return UnitType.STUDIO;
        if (s.startsWith("PENTHOUSE")) return UnitType.PENTHOUSE;
        if (s.startsWith("RETAIL") || s.equals("SHOP")) return UnitType.RETAIL;
        if (s.startsWith("OFFICE")) return UnitType.OFFICE;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d)(BR|BHK|BED|BEDROOM|BEDROOMS|B)?$").matcher(s);
        if (m.matches()) {
            return switch (m.group(1)) {
                case "1" -> UnitType.BHK1;
                case "2" -> UnitType.BHK2;
                case "3" -> UnitType.BHK3;
                default -> null;
            };
        }
        return null;
    }

    private RentalIndexRange save(Property property, Valid v, UUID callerId) {
        RentalIndexRange r = ranges.findByPropertyIdAndUnitTypeAndIndexYear(property.getId(), v.unitType(), v.year())
                .orElseGet(() -> {
                    RentalIndexRange n = new RentalIndexRange();
                    n.setTenantId(property.getTenantId());
                    n.setProperty(property);
                    n.setUnitType(v.unitType());
                    n.setIndexYear(v.year());
                    return n;
                });
        r.setMinValue(v.min());
        r.setMaxValue(v.max());
        r.setSourceNote(v.note());
        r.setEnteredBy(callerId);
        r.setEnteredAt(Instant.now());
        return ranges.save(r);
    }
}
