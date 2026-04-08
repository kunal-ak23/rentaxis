package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.UnitListingMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UnitListingMediaRepository extends JpaRepository<UnitListingMedia, UUID> {

    List<UnitListingMedia> findByListingIdOrderBySortOrderAsc(UUID listingId);

    void deleteByListingId(UUID listingId);
}
