package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "meeting_details")
@Getter
@Setter
public class MeetingDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false, unique = true)
    private Meeting meeting;

    @Column(name = "detail_type", length = 30, nullable = false)
    private String detailType;

    @Column(name = "payment_schedule_ids", columnDefinition = "uuid[]")
    private UUID[] paymentScheduleIds;

    @Column(name = "proposed_start_date")
    private LocalDate proposedStartDate;

    @Column(name = "proposed_end_date")
    private LocalDate proposedEndDate;

    @Column(name = "proposed_rent_amount", precision = 15, scale = 2)
    private BigDecimal proposedRentAmount;

    @Column(columnDefinition = "text")
    private String notes;

    @PrePersist
    public void onPrePersist() {
        if (this.tenantId == null) {
            this.tenantId = TenantContextHolder.getTenantId();
        }
    }
}
