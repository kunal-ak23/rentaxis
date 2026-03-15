package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ticket_history")
@Getter
@Setter
public class TicketHistory extends BaseTenantEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private MaintenanceTicket ticket;

    @Column(nullable = false, length = 50)
    private String action; // CREATED, STATUS_CHANGED, ASSIGNED, REASSIGNED, ETA_SET, RATED

    @Column(name = "from_status", length = 30)
    private String fromStatus;

    @Column(name = "to_status", length = 30)
    private String toStatus;

    @Column(name = "assigned_from")
    private UUID assignedFrom;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "performed_by", nullable = false)
    private UUID performedBy;

    @Column(name = "performed_by_name")
    private String performedByName;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
