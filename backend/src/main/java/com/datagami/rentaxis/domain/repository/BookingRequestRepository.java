package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
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
public interface BookingRequestRepository extends JpaRepository<BookingRequest, UUID> {

    /**
     * Admin inbox: every filter optional. Explicit tenantId in the JPQL, belt and
     * braces on top of the Hibernate tenantFilter, matching repo convention.
     */
    @Query("""
        SELECT b FROM BookingRequest b
        WHERE b.tenantId = :tenantId
          AND (:propertyId IS NULL OR b.propertyId = :propertyId)
          AND (:status IS NULL OR b.status = :status)
          AND (:resourceType IS NULL OR b.resourceType = :resourceType)
        """)
    Page<BookingRequest> search(@Param("tenantId") UUID tenantId,
                                @Param("propertyId") UUID propertyId,
                                @Param("status") BookingRequestStatus status,
                                @Param("resourceType") BookingResourceType resourceType,
                                Pageable pageable);

    List<BookingRequest> findByTenantIdAndRenterUserIdOrderByCreatedAtAsc(UUID tenantId, UUID renterUserId);

    Optional<BookingRequest> findFirstByTenantIdAndRenterUserIdAndAmenityIdAndStatus(
            UUID tenantId, UUID renterUserId, UUID amenityId, BookingRequestStatus status);

    Optional<BookingRequest> findFirstByTenantIdAndRenterUserIdAndParkingSpotIdAndStatus(
            UUID tenantId, UUID renterUserId, UUID parkingSpotId, BookingRequestStatus status);

    boolean existsByParkingSpotIdAndStatus(UUID parkingSpotId, BookingRequestStatus status);

    long countByAmenityIdAndStatus(UUID amenityId, BookingRequestStatus status);

    long countByParkingSpotIdAndStatus(UUID parkingSpotId, BookingRequestStatus status);

    List<BookingRequest> findByAmenityIdAndStatusInOrderByCreatedAtAsc(
            UUID amenityId, Collection<BookingRequestStatus> statuses);

    List<BookingRequest> findByParkingSpotIdAndStatusInOrderByCreatedAtAsc(
            UUID parkingSpotId, Collection<BookingRequestStatus> statuses);
}
