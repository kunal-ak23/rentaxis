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
     * <p>It returns a list and orders it because, until changeset 186, <b>nothing
     * in the schema said there is one row per tenant</b>, so a duplicate would
     * have made the answer depend on row order. Changeset 186 removed any
     * duplicates (keeping the lowest id, the row this returns first) and added
     * {@code uq_org_settings_tenant}, so the list now holds at most one row; the
     * order stays so the read is deterministic either way.</p>
     */
    List<OrgSettings> findByTenantIdOrderByIdAsc(UUID tenantId);
}
