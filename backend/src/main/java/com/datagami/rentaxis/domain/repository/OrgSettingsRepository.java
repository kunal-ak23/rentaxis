package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.OrgSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrgSettingsRepository extends JpaRepository<OrgSettings, UUID> {
    List<OrgSettings> findAll();

    /**
     * This tenant's settings row, oldest id first.
     *
     * <p>Named rather than left to {@code findAll()} plus the Hibernate filter:
     * the filter only reaches a query on the session it was enabled on, so the
     * callers that had no transaction were reading every landlord's row and
     * taking the first one. See {@code OrgSettingsService}.</p>
     *
     * <p>It returns a list and orders it because <b>nothing in the schema says
     * there is one row per tenant</b> — {@code org_settings} has no unique
     * constraint on {@code tenant_id} or {@code landlord_org_id} (changesets 01
     * and 49 are the only ones that touch the table). "There is exactly one per
     * LandlordOrg" is a convention the code keeps, not a rule the database
     * enforces, so a duplicate would otherwise make the answer depend on row
     * order. Deterministic is what a hotfix can do here; the constraint belongs
     * in a migration of its own.</p>
     */
    List<OrgSettings> findByTenantIdOrderByIdAsc(UUID tenantId);
}
