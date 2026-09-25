package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.MaintenanceTicket;
import com.datagami.rentaxis.domain.entity.enums.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MaintenanceTicketRepository extends JpaRepository<MaintenanceTicket, UUID> {

    List<MaintenanceTicket> findByPropertyId(UUID propertyId);

    List<MaintenanceTicket> findByReportedBy(UUID reportedBy);

    List<MaintenanceTicket> findByAssignedTo(UUID assignedTo);

    List<MaintenanceTicket> findByStatus(TicketStatus status);

    List<MaintenanceTicket> findByPropertyIdIn(List<UUID> propertyIds);

    // Unit-scoped variants of each role branch above.
    //
    // The lease detail page needs the tickets for one unit. It used to fetch
    // every ticket in the tenant and filter client-side, so opening a lease
    // transferred the landlord's entire maintenance history — all properties,
    // all units, all time — to throw away everything but one unitId.
    //
    // The unit filter composes with the role scope rather than replacing it: a
    // renter passing a unitId must still see only tickets they reported.
    List<MaintenanceTicket> findByUnitId(UUID unitId);

    List<MaintenanceTicket> findByReportedByAndUnitId(UUID reportedBy, UUID unitId);

    List<MaintenanceTicket> findByPropertyIdInAndUnitId(List<UUID> propertyIds, UUID unitId);

    long countByStatus(TicketStatus status);

    // One renter's record, for staff (web review I3): tickets logged for them,
    // raised on one of their contracts, or reported from their portal account.
    // The caller's role scope is applied on top of this, never instead of it.
    @Query("SELECT t FROM MaintenanceTicket t LEFT JOIN t.lease l"
            + " WHERE t.onBehalfOfRenterId = :renterId OR l.renter.id = :renterId OR t.reportedBy = :userId")
    List<MaintenanceTicket> findForRenterRecord(@Param("renterId") UUID renterId, @Param("userId") UUID userId);

    @Query("SELECT t FROM MaintenanceTicket t LEFT JOIN t.lease l"
            + " WHERE t.onBehalfOfRenterId = :renterId OR l.renter.id = :renterId")
    List<MaintenanceTicket> findForRenterRecordWithoutAccount(@Param("renterId") UUID renterId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM MaintenanceTicket t WHERE t.id = :id")
    java.util.Optional<MaintenanceTicket> findByIdForUpdate(@Param("id") UUID id);

    // A renter's own list: the tickets they reported, the ones staff logged on
    // their behalf (#19, PR #342 review I2) and the ones raised on their contract
    // (re-review I1: that renter holds the closure OTP). Without the latter two a
    // phoned-in complaint never reached the renter it was about.
    @Query("SELECT t FROM MaintenanceTicket t LEFT JOIN t.lease l"
            + " WHERE t.reportedBy = :userId OR t.onBehalfOfRenterId = :renterId OR l.renter.id = :renterId")
    List<MaintenanceTicket> findForRenter(@Param("userId") UUID userId, @Param("renterId") UUID renterId);

    @Query("SELECT t FROM MaintenanceTicket t LEFT JOIN t.lease l"
            + " WHERE (t.reportedBy = :userId OR t.onBehalfOfRenterId = :renterId OR l.renter.id = :renterId)"
            + " AND t.unit.id = :unitId")
    List<MaintenanceTicket> findForRenterAndUnitId(@Param("userId") UUID userId, @Param("renterId") UUID renterId,
                                                   @Param("unitId") UUID unitId);

    /**
     * Scale P1-3: the staff tickets list, filtered and paged in the database. {@code q} is
     * {@code %term%}, lowercased and trimmed, matched against the title, the reference and
     * the unit number; {@code from}/{@code to} bound the reported date.
     */
    @org.springframework.data.jpa.repository.Query("""
        select t from MaintenanceTicket t left join t.unit u
        where t.tenantId = :tenantId
          and (cast(:propertyId as java.util.UUID) is null or t.property.id = :propertyId)
          and (cast(:status as string) is null or t.status = :status)
          and (cast(:priority as string) is null or t.priority = :priority)
          and (cast(:from as LocalDate) is null or t.reportedDate >= :from)
          and (cast(:to as LocalDate) is null or t.reportedDate <= :to)
          and (cast(:q as string) is null
               or lower(t.title) like :q or lower(t.reference) like :q or lower(u.unitNumber) like :q)
          and (:unrestricted = true or t.property.id in :propertyIds)
        """)
    org.springframework.data.domain.Page<MaintenanceTicket> searchPaged(
            @org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
            @org.springframework.data.repository.query.Param("propertyId") UUID propertyId,
            @org.springframework.data.repository.query.Param("status") com.datagami.rentaxis.domain.entity.enums.TicketStatus status,
            @org.springframework.data.repository.query.Param("priority") com.datagami.rentaxis.domain.entity.enums.TicketPriority priority,
            @org.springframework.data.repository.query.Param("from") java.time.LocalDate from,
            @org.springframework.data.repository.query.Param("to") java.time.LocalDate to,
            @org.springframework.data.repository.query.Param("q") String q,
            @org.springframework.data.repository.query.Param("unrestricted") boolean unrestricted,
            @org.springframework.data.repository.query.Param("propertyIds") java.util.Collection<UUID> propertyIds,
            org.springframework.data.domain.Pageable pageable);
}
