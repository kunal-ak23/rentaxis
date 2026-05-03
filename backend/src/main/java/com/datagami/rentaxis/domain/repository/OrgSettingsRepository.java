package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.OrgSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrgSettingsRepository extends JpaRepository<OrgSettings, UUID> {
    // TenantAspect activates the Hibernate tenantFilter so findAll() returns
    // only the current tenant's row(s). There is exactly one per LandlordOrg.
    List<OrgSettings> findAll();
}
