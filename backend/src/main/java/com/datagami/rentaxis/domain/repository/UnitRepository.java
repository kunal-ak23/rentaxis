package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.core.util.NaturalOrderComparator;
import com.datagami.rentaxis.domain.entity.Unit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

@Repository
public interface UnitRepository extends JpaRepository<Unit, UUID> {
    /**
     * A property's units, in the order a human reads a building: by unit number.
     *
     * <p>The ordering is stated here rather than left to the database, which
     * returned them in insertion-adjacent but effectively arbitrary order —
     * creating A-101 … A-302 in sequence listed back as A-101, A-201, A-102,
     * A-103, A-203, A-202, A-301, A-302. Unusable for a tower of any size, and
     * it also made the list jump around as rows were updated.</p>
     *
     * <p>{@code order by u.unitNumber asc} in SQL is lexicographic — it put
     * "A-1001" before "A-201" — so the actual ordering is done in Java with
     * {@link NaturalOrderComparator}, which reads a trailing run of digits as a
     * number. The query below fetches unsorted; sorting the fetched (small,
     * per-property) list is cheaper than teaching Postgres to do it.</p>
     */
    default List<Unit> findByPropertyId(UUID propertyId) {
        List<Unit> units = findByPropertyIdUnordered(propertyId);
        units.sort(Comparator.comparing(Unit::getUnitNumber, NaturalOrderComparator.INSTANCE));
        return units;
    }

    @Query("select u from Unit u where u.property.id = :propertyId")
    List<Unit> findByPropertyIdUnordered(@Param("propertyId") UUID propertyId);

    /**
     * Unit counts per status within the caller's properties — the dashboard's
     * occupancy tiles in one aggregate rather than a scan the service then walks.
     *
     * <p>Returns {@code [UnitStatus, Long count]}. Statuses with no units are
     * absent; the caller zero-fills. Scoping is the register's shape, and a caller
     * scoped to nothing is answered without a query, so {@code propertyIds} is
     * never empty.</p>
     */
    @Query("""
        select u.status, count(u) from Unit u
        where (:unrestricted = true or u.property.id in :propertyIds)
        group by u.status
        """)
    List<Object[]> countByStatusInScope(@Param("unrestricted") boolean unrestricted,
                                        @Param("propertyIds") Collection<UUID> propertyIds);

    @EntityGraph(attributePaths = "property")
    List<Unit> findByIdIn(Collection<UUID> ids);


    /**
     * Locks the unit row so an occupancy check and the status flip that follows
     * it cannot interleave with a concurrent activation of another lease on the
     * same unit. See LeaseService.claimUnitForLease.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM Unit u WHERE u.id = :id")
    Optional<Unit> findByIdForUpdate(@Param("id") UUID id);

    /** [propertyId, unit count] for the tenant, in one query (the P&L's allocation by units). */
    @Query("select u.property.id, count(u) from Unit u where u.tenantId = :tenantId and u.property is not null group by u.property.id")
    List<Object[]> countByProperty(@Param("tenantId") UUID tenantId);
}
