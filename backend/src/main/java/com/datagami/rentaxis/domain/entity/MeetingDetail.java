package com.datagami.rentaxis.domain.entity;

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
public class MeetingDetail extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false, unique = true)
    private Meeting meeting;

    @Column(name = "detail_type", length = 30, nullable = false)
    private String detailType;

    /**
     * The cheques a CHEQUE_REPLACEMENT meeting is about.
     *
     * <p>Mapped to {@code cheque_ids} (changeset 83). The old
     * {@code payment_schedule_ids} column is left in place and unmapped until the
     * payment-schedule table itself goes: dropping a column in the same change
     * that stops reading it leaves no way back if anything still points at it.</p>
     */
    @Column(name = "cheque_ids", columnDefinition = "uuid[]")
    private UUID[] chequeIds;

    @Column(name = "proposed_start_date")
    private LocalDate proposedStartDate;

    @Column(name = "proposed_end_date")
    private LocalDate proposedEndDate;

    @Column(name = "proposed_rent_amount", precision = 15, scale = 2)
    private BigDecimal proposedRentAmount;

    @Column(columnDefinition = "text")
    private String notes;
}
