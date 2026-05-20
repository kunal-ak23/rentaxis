package com.datagami.rentaxis.testsupport;

import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public class RenewalTestFixtures {

    public static Lease createActiveLease(LandlordOrgRepository orgRepo, UserRepository userRepo,
                                          RenterRepository renterRepo, PropertyRepository propertyRepo,
                                          UnitRepository unitRepo, LeaseRepository leaseRepo,
                                          UUID tenantId, LocalDate startDate, LocalDate endDate) {
        User u = new User();
        u.setEmail("renter+" + UUID.randomUUID() + "@test");
        u.setName("Test Renter");
        u.setRole(UserRole.RENTER);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        u = userRepo.save(u);

        Renter r = new Renter();
        r.setUserId(u.getId());
        r.setNameEn("Test Renter");
        r.setTenantId(tenantId);
        r = renterRepo.save(r);

        Property p = new Property();
        // Unique per fixture invocation so the new per-tenant uniqueness
        // index (ux_properties_tenant_name_en_lower, migration 60) doesn't
        // collide when a test calls createActiveLease more than once in the
        // same tenant.
        p.setNameEn("Test Property " + UUID.randomUUID().toString().substring(0, 8));
        p.setEmirate(Emirate.DUBAI);
        p.setTenantId(tenantId);
        p = propertyRepo.save(p);

        Unit unit = new Unit();
        unit.setProperty(p);
        unit.setUnitNumber("A1");
        unit.setTenantId(tenantId);
        unit = unitRepo.save(unit);

        Lease lease = new Lease();
        lease.setUnit(unit);
        lease.setRenter(r);
        lease.setTenantId(tenantId);
        lease.setStartDate(startDate);
        lease.setEndDate(endDate);
        lease.setMonthlyRent(BigDecimal.valueOf(5000));
        lease.setStatus(LeaseStatus.ACTIVE);
        return leaseRepo.save(lease);
    }
}
