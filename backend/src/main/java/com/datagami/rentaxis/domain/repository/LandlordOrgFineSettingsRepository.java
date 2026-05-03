package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LandlordOrgFineSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LandlordOrgFineSettingsRepository extends JpaRepository<LandlordOrgFineSettings, UUID> {
    Optional<LandlordOrgFineSettings> findByLandlordOrgId(UUID landlordOrgId);
}
