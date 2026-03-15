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
}
