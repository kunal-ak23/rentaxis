package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Property;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PropertyRepository extends JpaRepository<Property, UUID> {

    /**
     * How many properties the caller may see — the dashboard's first tile.
     *
     * <p>{@code unrestricted}/{@code propertyIds} are the register's scoping shape
     * ({@code LeaseAccessPolicy.visiblePropertyIds}). A caller scoped to nothing is
     * answered without a query at all, so {@code propertyIds} is never empty here.</p>
     */
    @Query("""
        select count(p) from Property p
        where (:unrestricted = true or p.id in :propertyIds)
        """)
    long countInScope(@Param("unrestricted") boolean unrestricted,
                      @Param("propertyIds") Collection<UUID> propertyIds);
    /**
     * @deprecated Relies on the {@code tenantFilter} Hibernate filter being
     *     enabled by {@code TenantAspect} on the current session. That works
     *     in the typical request path but is fragile under {@code @Async} or
     *     any code path where the aspect doesn't fire. Prefer
     *     {@link #findByTenantIdAndNameEnIn(UUID, Collection)} which makes
     *     the tenant scope explicit at the SQL level.
     */
    @Deprecated
    List<Property> findByNameEnIn(Collection<String> names);

    /**
     * Explicit tenant-scoped variant. Doesn't depend on the Hibernate
     * filter, so it stays correct under async/AOP-bypass paths.
     */
    List<Property> findByTenantIdAndNameEnIn(UUID tenantId, Collection<String> names);

    /**
     * Explicit tenant-scoped existence check. Used to verify a property
     * belongs to a given tenant before assigning it to a user, without
     * relying on the {@code tenantFilter} aspect.
     */
    boolean existsByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Explicit tenant-scoped batch lookup by id. Exists so the gate-pass
     * summaries can resolve property names for a whole list in one query
     * instead of one per row, and so guard-facing paths carry their tenant
     * scope in the SQL rather than depending on the {@code tenantFilter}
     * aspect having fired.
     *
     * <p>Prefer this over {@link #findAllById(Iterable)} on any tenant-scoped
     * path: a cross-tenant id passed to that one is caught only by the filter.
     */
    List<Property> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    /** Explicitly tenant-scoped portfolio ordering for finance summaries. */
    List<Property> findByTenantIdOrderByNameEnAsc(UUID tenantId);
}
