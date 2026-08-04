package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PropertyAmenityRepository extends JpaRepository<PropertyAmenity, UUID> {

    Page<PropertyAmenity> findByTenantId(UUID tenantId, Pageable pageable);

    Page<PropertyAmenity> findByTenantIdAndPropertyId(UUID tenantId, UUID propertyId, Pageable pageable);

    List<PropertyAmenity> findByTenantIdAndPropertyIdAndActiveTrue(UUID tenantId, UUID propertyId);
}
