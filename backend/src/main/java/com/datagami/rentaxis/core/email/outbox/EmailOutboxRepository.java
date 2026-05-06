package com.datagami.rentaxis.core.email.outbox;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, UUID> {

    Page<EmailOutbox> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<EmailOutbox> findByIdAndTenantId(UUID id, UUID tenantId);

    @Query(value = """
        SELECT * FROM email_outbox
        WHERE status = 'PENDING'
          AND scheduled_at <= (now() AT TIME ZONE 'UTC')
        ORDER BY scheduled_at ASC
        FOR UPDATE SKIP LOCKED
        LIMIT :limit
        """, nativeQuery = true)
    List<EmailOutbox> pickPending(@Param("limit") int limit);

    @Query("""
        SELECT o FROM EmailOutbox o
        WHERE o.status = com.datagami.rentaxis.core.email.outbox.EmailOutbox$Status.SENDING
          AND o.lastAttemptAt < :cutoff
        """)
    List<EmailOutbox> findStuckSending(@Param("cutoff") Instant cutoff);
}
