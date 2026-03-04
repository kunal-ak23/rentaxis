package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RentCollectionSettingsRepository extends JpaRepository<RentCollectionSettings, UUID> {

    Optional<RentCollectionSettings> findByPropertyId(UUID propertyId);
}
