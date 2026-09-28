package com.datagami.rentaxis.testsupport;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A renter's current contract (ACTIVE, today inside the term) on a fresh unit of
 * the property — what a renter needs before they may raise a ticket (break-it R3
 * portal3 F1).
 */
public final class CurrentLeaseFixture {

    private CurrentLeaseFixture() {
    }

    public static Lease currentLease(UnitRepository unitRepo, LeaseRepository leaseRepo,
                                     UUID tenantId, Property property, Renter renter) {
        Unit unit = new Unit();
        unit.setProperty(property);
        unit.setUnitNumber("U-" + UUID.randomUUID().toString().substring(0, 8));
        unit.setTenantId(tenantId);
        unit = unitRepo.save(unit);

        Lease lease = new Lease();
        lease.setUnit(unit);
        lease.setRenter(renter);
        lease.setTenantId(tenantId);
        lease.setStartDate(LocalDate.now().minusMonths(1));
        lease.setEndDate(LocalDate.now().plusMonths(11));
        lease.setRentAmount(BigDecimal.valueOf(60000));
        lease.setStatus(LeaseStatus.ACTIVE);
        return leaseRepo.save(lease);
    }
}
