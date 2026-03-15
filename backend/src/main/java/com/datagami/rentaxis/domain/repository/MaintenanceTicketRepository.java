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

    long countByStatus(TicketStatus status);
}
