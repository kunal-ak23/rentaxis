package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LeaseReminder;
import com.datagami.rentaxis.domain.entity.enums.ReminderChannel;
import com.datagami.rentaxis.domain.entity.enums.ReminderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LeaseReminderRepository extends JpaRepository<LeaseReminder, UUID> {

    List<LeaseReminder> findByOpportunityId(UUID opportunityId);

    @Modifying
    @Query("""
        UPDATE LeaseReminder r
        SET r.status = :status, r.lastError = :reason, r.updatedAt = CURRENT_TIMESTAMP
        WHERE r.opportunity.id = :opportunityId
          AND r.status = com.datagami.rentaxis.domain.entity.enums.ReminderStatus.PENDING
    """)
    int bulkSkipPendingForOpportunity(@Param("opportunityId") UUID opportunityId,
                                       @Param("status") ReminderStatus status,
                                       @Param("reason") String reason);

    boolean existsByOpportunityIdAndSlotAndChannel(UUID opportunityId, short slot, ReminderChannel channel);
}
