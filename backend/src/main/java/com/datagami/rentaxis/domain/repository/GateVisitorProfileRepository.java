package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.GateVisitorProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GateVisitorProfileRepository extends JpaRepository<GateVisitorProfile, UUID> {
    Optional<GateVisitorProfile> findByTenantIdAndPropertyIdAndPhoneNormalized(
            UUID tenantId, UUID propertyId, String phoneNormalized);
}
