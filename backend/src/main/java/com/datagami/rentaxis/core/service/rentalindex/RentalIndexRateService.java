package com.datagami.rentaxis.core.service.rentalindex;

import com.datagami.rentaxis.api.dto.rentalindex.ManualRateRequest;
import com.datagami.rentaxis.api.dto.rentalindex.ParsedResultDTO;
import com.datagami.rentaxis.api.dto.rentalindex.RentalIndexRateDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.PropertyScope;
import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.RentalIndexRate;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.DldPropertyType;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.RentalIndexSource;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.entity.enums.UnitType;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RentalIndexRateRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The rental index range store (spec 2026-11-25 §5, §8, §14.3, §14.6): ranges per building (or property) and
 * Miftah unit type, read from a DLD result PDF or entered by hand, revoked rather than edited. Every read runs in
 * a transaction so the tenant filter is on; every property-bound call goes through {@link PropertyScope}.
 */
@Service
public class RentalIndexRateService {

    /** Whether, and why not, a unit has a rental index range to check a renewal against. */
    public enum IndexStatus { OK, NO_INDEX, NOT_DUBAI, NO_EMIRATE, NOT_RESIDENTIAL }

    /** Unit types the index covers (Decree 43 residential; RETAIL and OFFICE are not). */
    public static final Set<UnitType> RESIDENTIAL =
            EnumSet.of(UnitType.STUDIO, UnitType.BHK1, UnitType.BHK2, UnitType.BHK3, UnitType.PENTHOUSE);

    static final int VALID_MONTHS = 12;
    private static final int MAX_CATEGORY = 100;
    private static final DateTimeFormatter ISSUED = new DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("d MMMM uuuu, h:mm a").toFormatter(Locale.ENGLISH);
    private static final Pattern BEDROOMS = Pattern.compile("(?i)(\\d+) ?bed");

    /** A stored DLD PDF, ready to stream. */
    public record Document(byte[] bytes, String filename) {
    }

    private final RentalIndexRateRepository rates;
    private final PropertyRepository properties;
    private final BuildingRepository buildings;
    private final UnitRepository units;
    private final LeaseRepository leases;
    private final LeaseLineRepository leaseLines;
    private final UserRepository users;
    private final PropertyScope propertyScope;
    private final TenantFeatureService features;
    private final BlobStorageService blob;
    private final Clock clock;

    public RentalIndexRateService(RentalIndexRateRepository rates, PropertyRepository properties,
                                  BuildingRepository buildings, UnitRepository units, LeaseRepository leases,
                                  LeaseLineRepository leaseLines, UserRepository users, PropertyScope propertyScope,
                                  TenantFeatureService features, BlobStorageService blob, Clock clock) {
        this.rates = rates;
        this.properties = properties;
        this.buildings = buildings;
        this.units = units;
        this.leases = leases;
        this.leaseLines = leaseLines;
        this.users = users;
        this.propertyScope = propertyScope;
        this.features = features;
        this.blob = blob;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- lookup (consumed by renewals)

    /**
     * The range in force for {@code unit} on {@code on} (spec §14.6): the newest unexpired, unrevoked range for the
     * unit's building and type, else for its property with no building and that type. Newest is the latest
     * {@code checked_on}, then {@code created_at}. Does not look at the flag, the emirate or the unit type.
     */
    @Transactional(readOnly = true)
    public Optional<RentalIndexRate> inForce(Unit unit, LocalDate on) {
        if (unit == null || unit.getType() == null || unit.getProperty() == null || on == null) {
            return Optional.empty();
        }
        UUID tenantId = unit.getTenantId();
        UUID propertyId = unit.getProperty().getId();
        PageRequest first = PageRequest.of(0, 1);
        if (unit.getBuilding() != null) {
            List<RentalIndexRate> hit = rates.inForceForBuilding(tenantId, propertyId, unit.getBuilding().getId(),
                    unit.getType(), on, first);
            if (!hit.isEmpty()) return Optional.of(hit.get(0));
        }
        return rates.inForceForProperty(tenantId, propertyId, unit.getType(), on, first).stream().findFirst();
    }

    /** The flag is on for the unit's organisation, its property is in Dubai, and its type is residential. */
    @Transactional(readOnly = true)
    public boolean applies(Unit unit) {
        if (unit == null || !flagOn(unit.getTenantId())) return false;
        return RESIDENTIAL.contains(unit.getType()) && emirateOf(unit) == Emirate.DUBAI;
    }

    /**
     * Why a unit is or is not checked against the index on {@code on}. Null when the RENTAL_INDEX flag is off for
     * the unit's organisation (not applicable: the check does not exist); otherwise, in this order, NO_EMIRATE,
     * NOT_DUBAI, NOT_RESIDENTIAL, NO_INDEX, OK.
     */
    @Transactional(readOnly = true)
    public IndexStatus statusFor(Unit unit, LocalDate on) {
        if (unit == null || !flagOn(unit.getTenantId())) return null;
        Emirate emirate = emirateOf(unit);
        if (emirate == null) return IndexStatus.NO_EMIRATE;
        if (emirate != Emirate.DUBAI) return IndexStatus.NOT_DUBAI;
        if (!RESIDENTIAL.contains(unit.getType())) return IndexStatus.NOT_RESIDENTIAL;
        return inForce(unit, on).isPresent() ? IndexStatus.OK : IndexStatus.NO_INDEX;
    }

    // ---------------------------------------------------------------- API

    /** Every range (revoked and expired included) on one property, or on every property the caller may see. */
    @Transactional(readOnly = true)
    public List<RentalIndexRateDTO> list(UUID propertyId) {
        UUID tenantId = requireTenant();
        List<RentalIndexRate> rows;
        if (propertyId != null) {
            requireProperty(propertyId);
            rows = rates.findForProperties(tenantId, List.of(propertyId));
        } else {
            List<UUID> scoped = propertyScope.scopedPropertyIds();
            if (scoped == null) {
                rows = rates.findAllForTenant(tenantId);
            } else if (scoped.isEmpty()) {
                rows = List.of();
            } else {
                rows = rates.findForProperties(tenantId, scoped);
            }
            rows = propertyScope.filter(rows, RentalIndexRate::getPropertyId);
        }
        return toDtos(rows);
    }

    /** Reads a DLD result and compares it with the unit and lease given; stores nothing. */
    @Transactional(readOnly = true)
    public ParsedResultDTO parse(byte[] pdf, UUID unitId, UUID leaseId) {
        requireTenant();
        Lease lease = null;
        if (leaseId != null) {
            lease = leases.findByIdScopedToTenant(leaseId).orElseThrow(() -> new NotFoundException("Lease not found"));
            propertyScope.requireCanAccessLease(lease);
        }
        Unit unit = unitId != null ? requireUnit(unitId) : lease != null ? lease.getUnit() : null;
        DldResult r = DldResultParser.parse(pdf);

        Boolean premise = unit == null || unit.getDewaPremiseNumber() == null ? null
                : unit.getDewaPremiseNumber().equals(r.premiseNo());
        Boolean end = lease == null || lease.getEndDate() == null ? null : lease.getEndDate().equals(r.contractEnd());
        Boolean rent = null;
        if (lease != null) {
            BigDecimal annual = annualRent(lease);
            if (annual != null) {
                rent = annual.setScale(0, RoundingMode.HALF_UP)
                        .compareTo(r.currentRent().setScale(0, RoundingMode.HALF_UP)) == 0;
            }
        }
        DldPropertyType type = DldPropertyType.fromDld(r.dldPropertyType());
        UnitType proposed = proposedUnitType(r.category());
        return new ParsedResultDTO(r.dldPercent(), r.increase(), r.rentAfter(), r.premiseNo(), r.contractEnd(),
                r.currentRent(), r.indexMin(), r.indexMax(), RentalIndexMath.average(r.indexMin(), r.indexMax()),
                r.category(), r.building(), r.area(), type == null ? null : type.name(), r.issuedAt(),
                checkedOnFor(r), proposed == null ? null : proposed.name(), premise, end, rent);
    }

    /**
     * Stores a range read from a DLD result PDF, and the PDF. With {@code unitId} the PDF is that unit's evidence:
     * refused ({@code rentalIndex.premiseMismatch}) when the unit's DEWA premise number is set and differs.
     */
    @Transactional
    public RentalIndexRateDTO addFromPdf(byte[] pdf, UUID propertyId, UUID buildingId, UnitType unitType,
                                         UUID unitId, UUID userId) {
        UUID tenantId = requireTenant();
        requireProperty(propertyId);
        requireBuilding(buildingId, propertyId);
        if (unitType == null) {
            throw new BusinessRuleViolationException("Choose the unit type this range covers");
        }
        Unit unit = null;
        if (unitId != null) {
            unit = requireUnit(unitId);
            if (!propertyId.equals(unit.getProperty().getId())) {
                throw new NotFoundException("Unit not found");
            }
        }
        DldResult r = DldResultParser.parse(pdf);
        if (unit != null && unit.getDewaPremiseNumber() != null && !unit.getDewaPremiseNumber().equals(r.premiseNo())) {
            throw new BusinessRuleViolationException(
                    "This result is for DEWA premise " + r.premiseNo() + ", not this unit's ("
                            + unit.getDewaPremiseNumber() + ").",
                    "rentalIndex.premiseMismatch",
                    Map.of("pdfPremise", r.premiseNo(), "unitPremise", unit.getDewaPremiseNumber()));
        }
        requireRange(r.indexMin(), r.indexMax());

        RentalIndexRate rate = new RentalIndexRate();
        rate.setTenantId(tenantId);
        rate.setPropertyId(propertyId);
        rate.setBuildingId(buildingId);
        rate.setUnitType(unitType);
        DldPropertyType type = DldPropertyType.fromDld(r.dldPropertyType());
        rate.setDldPropertyType(type == null ? DldPropertyType.APARTMENT : type);
        rate.setCategory(truncate(blankToNull(r.category())));
        rate.setIndexMin(r.indexMin());
        rate.setIndexMax(r.indexMax());
        rate.setAreaName(blankToNull(r.area()));
        rate.setSource(RentalIndexSource.DLD_PDF);
        rate.setSourceUnitId(unitId);
        rate.setDldPercent(r.dldPercent());
        rate.setDldIssuedAt(issuedAt(r.issuedAt()));
        LocalDate checkedOn = checkedOnFor(r);
        rate.setCheckedOn(checkedOn);
        rate.setExpiresOn(checkedOn.plusMonths(VALID_MONTHS));
        rate.setCreatedBy(requireUser(userId));
        rate.setCreatedAt(Instant.now(clock));
        // Stored last, once everything else has been accepted.
        rate.setDocumentPath(blob.uploadRentalIndexDoc(tenantId, pdf).blobPath());
        return toDtos(List.of(rates.save(rate))).get(0);
    }

    /** Stores a range entered by hand; {@code rentalIndex.rangeInvalid} when min ≤ 0 or max < min. */
    @Transactional
    public RentalIndexRateDTO addManual(ManualRateRequest req, UUID userId) {
        UUID tenantId = requireTenant();
        if (req == null || req.propertyId() == null) {
            throw new BusinessRuleViolationException("Choose the property this range covers");
        }
        requireProperty(req.propertyId());
        requireBuilding(req.buildingId(), req.propertyId());
        if (req.unitType() == null || req.dldPropertyType() == null || req.checkedOn() == null) {
            throw new BusinessRuleViolationException("A range needs a unit type, a DLD property type and a checked-on date");
        }
        requireRange(req.indexMin(), req.indexMax());
        String category = blankToNull(req.category());
        if (category != null && category.length() > MAX_CATEGORY) {
            throw new BusinessRuleViolationException("The category is longer than " + MAX_CATEGORY + " characters");
        }
        RentalIndexRate rate = new RentalIndexRate();
        rate.setTenantId(tenantId);
        rate.setPropertyId(req.propertyId());
        rate.setBuildingId(req.buildingId());
        rate.setUnitType(req.unitType());
        rate.setDldPropertyType(req.dldPropertyType());
        rate.setCategory(category);
        rate.setIndexMin(req.indexMin());
        rate.setIndexMax(req.indexMax());
        rate.setSource(RentalIndexSource.MANUAL);
        rate.setCheckedOn(req.checkedOn());
        rate.setExpiresOn(req.checkedOn().plusMonths(VALID_MONTHS));
        rate.setCreatedBy(requireUser(userId));
        rate.setCreatedAt(Instant.now(clock));
        return toDtos(List.of(rates.save(rate))).get(0);
    }

    /** Marks a range revoked (kept, flagged); revoking it again changes nothing. */
    @Transactional
    public RentalIndexRateDTO revoke(UUID id, UUID userId) {
        RentalIndexRate rate = requireRate(id);
        if (rate.getRevokedAt() == null) {
            rate.setRevokedBy(requireUser(userId));
            rate.setRevokedAt(Instant.now(clock));
            rate = rates.save(rate);
        }
        return toDtos(List.of(rate)).get(0);
    }

    /** The stored DLD PDF of a range; 404 when the range has none. */
    @Transactional(readOnly = true)
    public Document document(UUID id) {
        RentalIndexRate rate = requireRate(id);
        if (rate.getDocumentPath() == null) {
            throw new NotFoundException("Document not found");
        }
        byte[] bytes = blob.download(rate.getTenantId(), rate.getDocumentPath()).bytes();
        return new Document(bytes, "rental-index-" + rate.getCheckedOn() + ".pdf");
    }

    // ---------------------------------------------------------------- helpers

    /** DLD's category → Miftah unit type (spec §14.3): "Studio" → STUDIO, "N bed rooms+hall" → BHK N (1–3). */
    static UnitType proposedUnitType(String category) {
        if (category == null || category.isBlank()) return null;
        if (category.toLowerCase(Locale.ROOT).contains("studio")) return UnitType.STUDIO;
        Matcher m = BEDROOMS.matcher(category);
        if (!m.find()) return null;
        return switch (m.group(1)) {
            case "1" -> UnitType.BHK1;
            case "2" -> UnitType.BHK2;
            case "3" -> UnitType.BHK3;
            default -> null;
        };
    }

    private LocalDate checkedOnFor(DldResult r) {
        LocalDateTime issued = issuedAt(r.issuedAt());
        return issued != null ? issued.toLocalDate() : LocalDate.now(clock);
    }

    private static LocalDateTime issuedAt(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDateTime.parse(text.trim(), ISSUED);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** The lease's contract rent, net of discount, annualised by days when the term is not one year (§14.2). */
    private BigDecimal annualRent(Lease lease) {
        if (lease.getStartDate() == null || lease.getEndDate() == null) return null;
        List<LeaseLine> lines = leaseLines.findByLease_IdOrderBySeqNoAsc(lease.getId());
        LeaseLine rent = LeaseService.contractRentLine(lease, lines);
        if (rent == null || rent.getGrossAmount() == null) return null;
        BigDecimal net = rent.getGrossAmount()
                .subtract(rent.getDiscountAmount() == null ? BigDecimal.ZERO : rent.getDiscountAmount());
        return RentalIndexMath.annualise(net, lease.getStartDate(), lease.getEndDate());
    }

    private boolean flagOn(UUID tenantId) {
        return tenantId != null && features.isEnabled(tenantId, TenantFeature.RENTAL_INDEX);
    }

    /** The unit's property's emirate: from the property the unit carries when loaded, else read. */
    private Emirate emirateOf(Unit unit) {
        Property p = unit.getProperty();
        if (p == null) return null;
        if (org.hibernate.Hibernate.isInitialized(p)) return p.getEmirate();
        return properties.findById(p.getId()).map(Property::getEmirate).orElse(null);
    }

    private static UUID requireTenant() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new NotFoundException("Rental index feature is disabled");
        }
        return tenantId;
    }

    private void requireProperty(UUID propertyId) {
        if (propertyId == null || properties.findById(propertyId).isEmpty()) {
            throw new NotFoundException("Property not found");
        }
        propertyScope.requireCanAccessProperty(propertyId);
    }

    private void requireBuilding(UUID buildingId, UUID propertyId) {
        if (buildingId == null) return;
        Building b = buildings.findById(buildingId).orElseThrow(() -> new NotFoundException("Building not found"));
        if (b.getProperty() == null || !propertyId.equals(b.getProperty().getId())) {
            throw new NotFoundException("Building not found");
        }
    }

    private Unit requireUnit(UUID unitId) {
        Unit unit = units.findById(unitId).orElse(null);
        propertyScope.requireCanAccessUnit(unit, "Unit not found");
        return unit;
    }

    private RentalIndexRate requireRate(UUID id) {
        RentalIndexRate rate = rates.findForTenant(requireTenant(), id)
                .orElseThrow(() -> new NotFoundException("Rental index range not found"));
        propertyScope.requireCanAccessProperty(rate.getPropertyId(), "Rental index range not found");
        return rate;
    }

    private static UUID requireUser(UUID userId) {
        if (userId == null) {
            throw new BusinessRuleViolationException("No signed-in user to record against this range");
        }
        return userId;
    }

    private static void requireRange(BigDecimal min, BigDecimal max) {
        if (min == null || max == null || min.signum() <= 0 || max.compareTo(min) < 0) {
            throw new BusinessRuleViolationException(
                    "The range needs a lowest figure above zero and a highest figure no lower than it.",
                    "rentalIndex.rangeInvalid", Map.of());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s) {
        return s == null || s.length() <= MAX_CATEGORY ? s : s.substring(0, MAX_CATEGORY);
    }

    private List<RentalIndexRateDTO> toDtos(List<RentalIndexRate> rows) {
        if (rows.isEmpty()) return List.of();
        Map<UUID, String> buildingNames = names(rows.stream().map(RentalIndexRate::getBuildingId),
                buildings::findAllById, Building::getId, Building::getNameEn);
        Map<UUID, String> userNames = names(rows.stream().map(RentalIndexRate::getCreatedBy),
                users::findAllById, User::getId, User::getName);
        LocalDate today = LocalDate.now(clock);
        return rows.stream().map(r -> new RentalIndexRateDTO(r.getId(), r.getPropertyId(), r.getBuildingId(),
                buildingNames.get(r.getBuildingId()), r.getDldPropertyType().name(), r.getCategory(),
                r.getUnitType().name(), r.getIndexMin(), r.getIndexMax(),
                RentalIndexMath.average(r.getIndexMin(), r.getIndexMax()), r.getAreaName(), r.getSource().name(),
                r.getSourceUnitId(), r.getDocumentPath() != null, r.getDldPercent(), r.getDldIssuedAt(),
                r.getCheckedOn(), r.getExpiresOn(), r.getExpiresOn().isBefore(today), r.getRevokedAt() != null,
                r.getCreatedBy(), userNames.get(r.getCreatedBy()), r.getCreatedAt(), r.getRevokedBy(),
                r.getRevokedAt())).toList();
    }

    private static <T> Map<UUID, String> names(java.util.stream.Stream<UUID> ids,
                                               Function<Collection<UUID>, List<T>> load,
                                               Function<T, UUID> idOf, Function<T, String> nameOf) {
        Set<UUID> wanted = ids.filter(Objects::nonNull).collect(Collectors.toSet());
        if (wanted.isEmpty()) return new HashMap<>(); // mutable: get(null) on Map.of() throws
        Map<UUID, String> out = new HashMap<>();
        for (T t : load.apply(wanted)) out.put(idOf.apply(t), nameOf.apply(t));
        return out;
    }
}
