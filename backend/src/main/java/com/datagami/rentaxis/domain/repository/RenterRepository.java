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

    /**
     * Scale P1-3/P1-6: the renters list and picker, searched and paged in the database.
     * {@code q} is {@code %term%}, already lowercased and trimmed (the trigram indexes on
     * lower(name_en/phone/email) serve it). A property manager ({@code unrestricted} false)
     * sees the renters with a contract in one of {@code propertyIds}, and the renters with
     * no contract at all — a new renter has to be findable to be given one.
     */
    @org.springframework.data.jpa.repository.Query("""
        select r from Renter r
        where r.tenantId = :tenantId
          and (cast(:q as string) is null
               or lower(r.nameEn) like :q or lower(r.nameAr) like :q
               or lower(r.phone) like :q or lower(r.email) like :q)
          and (:unrestricted = true
               or not exists (select 1 from Lease l where l.renter = r)
               or exists (select 1 from Lease l where l.renter = r and l.unit.property.id in :propertyIds))
        """)
    org.springframework.data.domain.Page<Renter> searchPaged(
            @org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
            @org.springframework.data.repository.query.Param("q") String q,
            @org.springframework.data.repository.query.Param("unrestricted") boolean unrestricted,
            @org.springframework.data.repository.query.Param("propertyIds") Collection<UUID> propertyIds,
            org.springframework.data.domain.Pageable pageable);

    /** The named renters of the caller's organisation, scoped as {@link #searchPaged}. */
    @org.springframework.data.jpa.repository.Query("""
        select r from Renter r
        where r.tenantId = :tenantId and r.id in :ids
          and (:unrestricted = true
               or not exists (select 1 from Lease l where l.renter = r)
               or exists (select 1 from Lease l where l.renter = r and l.unit.property.id in :propertyIds))
        """)
    List<Renter> findNamed(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
                           @org.springframework.data.repository.query.Param("ids") Collection<UUID> ids,
                           @org.springframework.data.repository.query.Param("unrestricted") boolean unrestricted,
                           @org.springframework.data.repository.query.Param("propertyIds") Collection<UUID> propertyIds);
}
