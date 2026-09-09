package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Unit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

@Repository
public interface UnitRepository extends JpaRepository<Unit, UUID> {
    List<Unit> findByPropertyId(UUID propertyId);

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
}
