package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Property;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PropertyRepository extends JpaRepository<Property, UUID> {
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
}
