package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Unit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface UnitRepository extends JpaRepository<Unit, UUID> {
    List<Unit> findByPropertyId(UUID propertyId);

    @EntityGraph(attributePaths = "property")
    List<Unit> findByIdIn(Collection<UUID> ids);
}
