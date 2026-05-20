package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Renter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RenterRepository extends JpaRepository<Renter, UUID> {
    List<Renter> findByTenantId(UUID tenantId);

    Optional<Renter> findByUserId(UUID userId);

    /**
     * @deprecated Same tenant-filter-dependency caveat as
     *     {@link PropertyRepository#findByNameEnIn(Collection)}. Prefer
     *     {@link #findByTenantIdAndEmailIn(UUID, Collection)}.
     */
    @Deprecated
    List<Renter> findByEmailIn(Collection<String> emails);

    /**
     * Explicit tenant-scoped variant. Use in async / non-AOP-wrapped paths
     * where the Hibernate {@code tenantFilter} may not be enabled.
     */
    List<Renter> findByTenantIdAndEmailIn(UUID tenantId, Collection<String> emails);
}
