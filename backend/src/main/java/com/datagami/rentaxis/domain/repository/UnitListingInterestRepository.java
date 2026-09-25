package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.UnitListingInterest;
import com.datagami.rentaxis.domain.entity.enums.InterestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UnitListingInterestRepository extends JpaRepository<UnitListingInterest, UUID> {

    List<UnitListingInterest> findByListingIdAndStatus(UUID listingId, InterestStatus status);

    Page<UnitListingInterest> findByListingIdAndStatus(UUID listingId, InterestStatus status, Pageable pageable);

    /** PR #361 R1: the drawer lists live and converted enquiries. */
    Page<UnitListingInterest> findByListingIdAndStatusIn(UUID listingId, java.util.Collection<InterestStatus> statuses,
                                                         Pageable pageable);

    long countByListingIdAndStatus(UUID listingId, InterestStatus status);

    @Query("""
            select interest.listingId as listingId, count(interest) as interestCount
            from UnitListingInterest interest
            where interest.listingId in :listingIds and interest.status = :status
            group by interest.listingId
            """)
    List<ListingInterestCount> countByListingIdsAndStatus(
            @Param("listingIds") Collection<UUID> listingIds,
            @Param("status") InterestStatus status);

    Optional<UnitListingInterest> findByListingIdAndRenterUserId(UUID listingId, UUID renterUserId);

    List<UnitListingInterest> findByRenterUserIdAndStatus(UUID renterUserId, InterestStatus status);

    interface ListingInterestCount {
        UUID getListingId();

        long getInterestCount();
    }
}
