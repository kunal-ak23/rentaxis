package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.TicketAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TicketAttachmentRepository extends JpaRepository<TicketAttachment, UUID> {

    List<TicketAttachment> findByTicketId(UUID ticketId);

    long countByTicketId(UUID ticketId);

    /** {@link #countByTicketId} for a page of tickets: (ticketId, count). */
    @org.springframework.data.jpa.repository.Query(
            "select x.ticket.id, count(x) from TicketAttachment x where x.ticket.id in :ticketIds group by x.ticket.id")
    List<Object[]> countByTicketIds(
            @org.springframework.data.repository.query.Param("ticketIds") java.util.Collection<UUID> ticketIds);
}
