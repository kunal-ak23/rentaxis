package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LandlordOrgRepository extends JpaRepository<LandlordOrg, UUID> {

    Optional<LandlordOrg> findBySlug(String slug);

    /** The organisation's status alone, for the per-request bearer-token check. */
    @Query(value = "SELECT status FROM landlord_org WHERE id = :id", nativeQuery = true)
    Optional<String> findStatusById(@Param("id") UUID id);
}
