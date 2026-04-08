package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.UnitListingAmenityEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UnitListingAmenityRepository extends JpaRepository<UnitListingAmenityEntry, UUID> {

    List<UnitListingAmenityEntry> findByListingId(UUID listingId);

    void deleteByListingId(UUID listingId);
}
