package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PropertyContact;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PropertyContactRepository extends JpaRepository<PropertyContact, UUID> {
    List<PropertyContact> findByPropertyIdOrderBySortOrderAscCreatedAtAsc(UUID propertyId);
}
