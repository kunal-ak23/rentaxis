package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;

@Repository
public interface BookingRequestRepository extends JpaRepository<BookingRequest, UUID> {

    /**
     * Admin inbox: every filter optional. Explicit tenantId in the JPQL, belt and
     * braces on top of the Hibernate tenantFilter, matching repo convention.
     * Callers MUST supply a Sort (controllers default to createdAt ASC); JPQL adds no ORDER BY.
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

    /**
     * Property-manager inbox across every property assigned to that manager.
     * The controller resolves the assignment ids from the authenticated user;
     * tenantId remains explicit so a stale/cross-tenant assignment cannot leak.
     */
    @Query("""
        SELECT b FROM BookingRequest b
        WHERE b.tenantId = :tenantId
          AND b.propertyId IN :propertyIds
          AND (:status IS NULL OR b.status = :status)
          AND (:resourceType IS NULL OR b.resourceType = :resourceType)
        """)
    Page<BookingRequest> searchAssignedProperties(
            @Param("tenantId") UUID tenantId,
            @Param("propertyIds") Collection<UUID> propertyIds,
            @Param("status") BookingRequestStatus status,
            @Param("resourceType") BookingResourceType resourceType,
            Pageable pageable);

    List<BookingRequest> findByTenantIdAndRenterUserIdOrderByCreatedAtAsc(UUID tenantId, UUID renterUserId);

    Optional<BookingRequest> findFirstByTenantIdAndRenterUserIdAndAmenityIdAndStatus(
            UUID tenantId, UUID renterUserId, UUID amenityId, BookingRequestStatus status);

    Optional<BookingRequest> findFirstByTenantIdAndRenterUserIdAndParkingSpotIdAndStatus(
            UUID tenantId, UUID renterUserId, UUID parkingSpotId, BookingRequestStatus status);

    /** Break-it R3 ops3 F7: the renter's open (PENDING/APPROVED) bookings of one amenity — overlap candidates. */
    List<BookingRequest> findByTenantIdAndRenterUserIdAndAmenityIdAndStatusIn(
            UUID tenantId, UUID renterUserId, UUID amenityId, Collection<BookingRequestStatus> statuses);

    /**
     * Attached parking (changeset 175): whether an APPROVED booking of the bay, other
     * than {@code excludeId}, has dates overlapping {@code [from, to]} (inclusive). A
     * stored null bound is an open end; callers pass {@code BookingService.FAR_PAST} /
     * {@code FAR_FUTURE} for theirs. The JPQL twin of ex_booking_spot_no_overlap.
     */
    @Query("""
        SELECT COUNT(b) > 0 FROM BookingRequest b
        WHERE b.tenantId = :tenantId AND b.parkingSpotId = :spotId
          AND b.status = com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus.APPROVED
          AND b.id <> :excludeId
          AND (b.preferredEndDate IS NULL OR b.preferredEndDate >= :from)
          AND (b.preferredDate IS NULL OR b.preferredDate <= :to)
        """)
    boolean existsApprovedSpotOverlap(@Param("tenantId") UUID tenantId, @Param("spotId") UUID spotId,
                                      @Param("from") LocalDate from, @Param("to") LocalDate to,
                                      @Param("excludeId") UUID excludeId);

    /**
     * The bays among {@code spotIds} held on {@code day}: an APPROVED booking whose
     * dates cover it. An ended booking no longer holds its bay.
     */
    @Query("""
        SELECT DISTINCT b.parkingSpotId FROM BookingRequest b
        WHERE b.tenantId = :tenantId AND b.parkingSpotId IN :spotIds
          AND b.status = com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus.APPROVED
          AND (b.preferredDate IS NULL OR b.preferredDate <= :day)
          AND (b.preferredEndDate IS NULL OR b.preferredEndDate >= :day)
        """)
    List<UUID> findSpotIdsHeldOn(@Param("tenantId") UUID tenantId,
                                 @Param("spotIds") Collection<UUID> spotIds,
                                 @Param("day") LocalDate day);

    /**
     * APPROVED bookings of these bays that have not ended by {@code day} — the
     * renter's "booked from … to …" hint. Dates only reach the renter, never who.
     */
    @Query("""
        SELECT b FROM BookingRequest b
        WHERE b.tenantId = :tenantId AND b.parkingSpotId IN :spotIds
          AND b.status = com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus.APPROVED
          AND (b.preferredEndDate IS NULL OR b.preferredEndDate >= :day)
        ORDER BY b.preferredDate ASC NULLS FIRST, b.createdAt ASC
        """)
    List<BookingRequest> findApprovedSpotBookingsNotEndedBy(@Param("tenantId") UUID tenantId,
                                                            @Param("spotIds") Collection<UUID> spotIds,
                                                            @Param("day") LocalDate day);

    /**
     * Whether the bay has a PENDING or APPROVED booking reaching {@code day} or later
     * (an open end always does) — such a bay cannot be attached to a unit.
     */
    @Query("""
        SELECT COUNT(b) > 0 FROM BookingRequest b
        WHERE b.tenantId = :tenantId AND b.parkingSpotId = :spotId
          AND b.status IN (com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus.PENDING,
                           com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus.APPROVED)
          AND (b.preferredEndDate IS NULL OR b.preferredEndDate >= :day)
        """)
    boolean existsOpenSpotBookingReaching(@Param("tenantId") UUID tenantId, @Param("spotId") UUID spotId,
                                          @Param("day") LocalDate day);

    long countByAmenityIdAndStatus(UUID amenityId, BookingRequestStatus status);

    long countByParkingSpotIdAndStatus(UUID parkingSpotId, BookingRequestStatus status);

    /** Any request at all, in any status, for one bay of one tenant (import-batch discard). */
    long countByTenantIdAndParkingSpotId(UUID tenantId, UUID parkingSpotId);

    List<BookingRequest> findByAmenityIdAndStatusInOrderByCreatedAtAsc(
            UUID amenityId, Collection<BookingRequestStatus> statuses);

    List<BookingRequest> findByParkingSpotIdAndStatusInOrderByCreatedAtAsc(
            UUID parkingSpotId, Collection<BookingRequestStatus> statuses);

    /**
     * Row-locked read for status transitions (approve/reject/cancel/release).
     * lock.timeout 0 = fail fast instead of queueing behind a concurrent decision.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select b from BookingRequest b where b.id = :id")
    Optional<BookingRequest> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
        SELECT b.amenityId, COUNT(b) FROM BookingRequest b
        WHERE b.tenantId = :tenantId AND b.amenityId IN :amenityIds AND b.status = :status
        GROUP BY b.amenityId
        """)
    List<Object[]> countByAmenityIdIn(@Param("tenantId") UUID tenantId,
                                      @Param("amenityIds") Collection<UUID> amenityIds,
                                      @Param("status") BookingRequestStatus status);

    @Query("""
        SELECT b.parkingSpotId, COUNT(b) FROM BookingRequest b
        WHERE b.tenantId = :tenantId AND b.parkingSpotId IN :spotIds AND b.status = :status
        GROUP BY b.parkingSpotId
        """)
    List<Object[]> countByParkingSpotIdIn(@Param("tenantId") UUID tenantId,
                                          @Param("spotIds") Collection<UUID> spotIds,
                                          @Param("status") BookingRequestStatus status);

    @Modifying
    @Query("DELETE FROM BookingRequest b WHERE b.renterUserId = :userId")
    void deleteByRenterUserId(@Param("userId") UUID userId);
}
