package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface LandlordOrgRepository extends JpaRepository<LandlordOrg, UUID> {
}
