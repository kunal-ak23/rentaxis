package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The database half of {@link ParkingSheet#resolve}: what the Parking sheet's names
 * mean in one organisation.
 *
 * <p>Transactional because both callers run on the import executor's thread, which
 * has no request-bound session: {@code TenantAspect} enables the Hibernate tenant
 * filter only inside a transaction, so an untransacted read here would run with the
 * filter off. Every query also names the tenant (or follows a property already
 * proven to be the tenant's, with an explicit tenant comparison on what comes back),
 * so the scoping does not rest on the filter alone. The persist phase calls this
 * inside its own transaction and joins it.</p>
 */
@Service
public class ParkingSheetResolver {

    private final PropertyRepository propertyRepository;
    private final BuildingRepository buildingRepository;
    private final UnitRepository unitRepository;
    private final ParkingSpotRepository spotRepository;
    private final BookingRequestRepository bookingRepository;

    private Clock clock = Clock.system(ZoneId.of("Asia/Dubai"));

    public ParkingSheetResolver(PropertyRepository propertyRepository,
                                BuildingRepository buildingRepository,
                                UnitRepository unitRepository,
                                ParkingSpotRepository spotRepository,
                                BookingRequestRepository bookingRepository) {
        this.propertyRepository = propertyRepository;
        this.buildingRepository = buildingRepository;
        this.unitRepository = unitRepository;
        this.spotRepository = spotRepository;
        this.bookingRepository = bookingRepository;
    }

    /** Tests pin "today". */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ParkingSheet.Resolved> resolve(List<ParkingSheet.Row> rows, ParkingSheet.SheetPlaces sheet,
                                               UUID tenantId, List<ImportErrorDTO> errors) {
        if (tenantId == null) {
            throw new IllegalStateException("Parking sheet resolved without a tenant; refusing unscoped reads");
        }
        return ParkingSheet.resolve(rows, sheet, db(tenantId), errors);
    }

    private ParkingSheet.Db db(UUID tenantId) {
        LocalDate today = LocalDate.now(clock);
        return new ParkingSheet.Db() {
            @Override
            public Optional<UUID> property(String name) {
                List<Property> found = propertyRepository.findByTenantIdAndNameEnLowerIn(
                        tenantId, List.of(name.trim().toLowerCase(Locale.ROOT)));
                // Two properties differing only in case: the sheet cannot say which is meant.
                return found.size() == 1 ? Optional.of(found.getFirst().getId()) : Optional.empty();
            }

            @Override
            public Map<String, UUID> buildings(UUID propertyId) {
                Map<String, UUID> out = new HashMap<>();
                for (Building b : buildingRepository.findByPropertyId(propertyId)) {
                    if (!Objects.equals(b.getTenantId(), tenantId) || b.getNameEn() == null) continue;
                    out.putIfAbsent(b.getNameEn().trim().toLowerCase(Locale.ROOT), b.getId());
                }
                return out;
            }

            @Override
            public List<ParkingSheet.UnitRef> units(UUID propertyId) {
                return unitRepository.findByPropertyIdUnordered(propertyId).stream()
                        .filter(u -> Objects.equals(u.getTenantId(), tenantId))
                        .filter(u -> u.getUnitNumber() != null)
                        .map(ParkingSheetResolver::ref)
                        .toList();
            }

            @Override
            public Optional<ParkingSheet.SpotRef> spot(UUID propertyId, String bayNumber) {
                return spotRepository.findByTenantIdAndPropertyIdAndSpotNumber(tenantId, propertyId, bayNumber)
                        .map(s -> new ParkingSheet.SpotRef(s.getId(), s.getAttachedUnitId()));
            }

            @Override
            public boolean openBookingsReaching(UUID spotId) {
                return bookingRepository.existsOpenSpotBookingReaching(tenantId, spotId, today);
            }
        };
    }

    private static ParkingSheet.UnitRef ref(Unit u) {
        String building = u.getBuilding() == null || u.getBuilding().getNameEn() == null
                ? "" : u.getBuilding().getNameEn().trim().toLowerCase(Locale.ROOT);
        return new ParkingSheet.UnitRef(u.getId(), building, ParkingSheet.unitKey(u.getUnitNumber()));
    }
}
