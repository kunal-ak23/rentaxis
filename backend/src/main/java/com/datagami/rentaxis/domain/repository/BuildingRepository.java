package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Building;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BuildingRepository extends JpaRepository<Building, UUID> {
    List<Building> findByPropertyId(UUID propertyId);
}
