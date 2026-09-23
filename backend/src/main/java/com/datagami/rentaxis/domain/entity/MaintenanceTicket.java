package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.TicketCategory;
import com.datagami.rentaxis.domain.entity.enums.TicketPriority;
import com.datagami.rentaxis.domain.entity.enums.TicketStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "maintenance_tickets")
@Getter
@Setter
public class MaintenanceTicket extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id")
    private Unit unit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id")
    private Lease lease;

    @Column(name = "reported_by", nullable = false)
    private UUID reportedBy;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private TicketCategory category;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TicketPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private TicketStatus status = TicketStatus.OPEN;

    @Column(name = "estimated_resolution_hours")
    private Integer estimatedResolutionHours;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closure_otp", length = 6)
    private String closureOtp;

    /**
     * Wrong OTPs entered against the current closure code (PR #342 review I2).
     * At {@code MaintenanceTicketService.MAX_OTP_ATTEMPTS} the code is discarded
     * and a new one has to be issued; reset whenever one is.
     */
    @Column(name = "closure_otp_failed_attempts", nullable = false)
    private int closureOtpFailedAttempts = 0;

    @Column(name = "satisfaction_rating")
    private Integer satisfactionRating;

    @Column(name = "satisfaction_comment", columnDefinition = "text")
    private String satisfactionComment;

    @Column(name = "on_behalf_of")
    private String onBehalfOf;

    /** The renter this was logged for, when staff picked one (#19); null on legacy free-text rows. */
    @Column(name = "on_behalf_of_renter_id")
    private UUID onBehalfOfRenterId;

    /** Human reference, "TKT-yy/n" per tenant and calendar year (#20); set at creation. */
    @Column(name = "reference", length = 20)
    private String reference;

    /**
     * The day the tenant actually reported the issue, distinct from
     * {@link #createdAt} (the instant the row was recorded). A complaint phoned in
     * on Tuesday and logged on Thursday is reported on Tuesday; without this the
     * two collapse and every SLA and the maintenance history are measured from the
     * wrong day.
     */
    @Column(name = "reported_date", nullable = false)
    private LocalDate reportedDate = LocalDate.now();

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onPreUpdate() {
        this.updatedAt = Instant.now();
    }
}
