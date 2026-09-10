package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.MaintenanceTicket;
import com.datagami.rentaxis.domain.entity.enums.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
