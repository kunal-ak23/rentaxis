package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.TicketHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface TicketHistoryRepository extends JpaRepository<TicketHistory, UUID> {
    List<TicketHistory> findByTicketIdOrderByCreatedAtAsc(UUID ticketId);

    long countByTicketIdAndActionAndCreatedAtAfter(UUID ticketId, String action, java.time.Instant after);
}
