package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PropertyGeo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PropertyGeoRepository extends JpaRepository<PropertyGeo, UUID> {

    Optional<PropertyGeo> findByPropertyId(UUID propertyId);
}
