package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.UnitListingInterest;
import com.datagami.rentaxis.domain.entity.enums.InterestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UnitListingInterestRepository extends JpaRepository<UnitListingInterest, UUID> {

    List<UnitListingInterest> findByListingIdAndStatus(UUID listingId, InterestStatus status);

    Optional<UnitListingInterest> findByListingIdAndRenterUserId(UUID listingId, UUID renterUserId);
}
