package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.LeaseAttachmentRepository;
import com.datagami.rentaxis.domain.repository.LeaseChargeRepository;
import com.datagami.rentaxis.domain.repository.LeaseInteractionRepository;
import com.datagami.rentaxis.domain.repository.LeaseDocumentRepository;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;

class LeaseServiceUnitOccupancyTest {

    private LeaseRepository leaseRepository;
    private UnitRepository unitRepository;
    private RenterRepository renterRepository;
    private PaymentScheduleRepository paymentScheduleRepository;
    private PaymentScheduleService paymentScheduleService;
    private LeaseInteractionRepository leaseInteractionRepository;
    private LeaseService service;

    @BeforeEach
    void setUp() {
        leaseRepository = mock(LeaseRepository.class);
        unitRepository = mock(UnitRepository.class);
        renterRepository = mock(RenterRepository.class);
        paymentScheduleRepository = mock(PaymentScheduleRepository.class);
        paymentScheduleService = mock(PaymentScheduleService.class);
        leaseInteractionRepository = mock(LeaseInteractionRepository.class);
        LeaseDocumentRepository leaseDocumentRepository = mock(LeaseDocumentRepository.class);
        LeaseChargeRepository leaseChargeRepository = mock(LeaseChargeRepository.class);

        service = new LeaseService(
                leaseRepository,
                unitRepository,
                renterRepository,
                mock(LeaseEventRepository.class),
                leaseDocumentRepository,
                mock(LeaseAttachmentRepository.class),
                paymentScheduleService,
                paymentScheduleRepository,
                leaseChargeRepository,
                leaseInteractionRepository,
                mock(SettlementService.class),
                mock(UnitListingService.class),
                mock(ApplicationEventPublisher.class));

        when(leaseRepository.save(any(Lease.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(leaseDocumentRepository.findByLeaseId(any())).thenReturn(List.of());
        when(leaseChargeRepository.findByLeaseId(any())).thenReturn(List.of());
        when(paymentScheduleRepository.findByLeaseId(any())).thenReturn(List.of());
        // Activation and termination now take the unit row FOR UPDATE so the
        // occupancy check and the status flip cannot interleave with a
        // concurrent activation; hand the same instance back.
        when(unitRepository.findByIdForUpdate(any()))
                .thenAnswer(inv -> Optional.empty());
        when(unitRepository.save(any(Unit.class))).thenAnswer(inv -> inv.getArgument(0));
        // No other lease holds any unit unless a test says so.
        when(leaseRepository.findByUnitIdAndStatus(any(), any())).thenReturn(List.of());
        when(paymentScheduleService.extendScheduleForLease(any(), any(), any())).thenReturn(List.of());
    }

    /** Makes findByIdForUpdate resolve to this lease's unit. */
    private void lockableUnit(Lease lease) {
        when(unitRepository.findByIdForUpdate(lease.getUnit().getId()))
                .thenReturn(Optional.of(lease.getUnit()));
    }

    @Test
    void activateLease_updatesUnitRevenueAndTenantName() {
        Lease lease = lease(LeaseStatus.DRAFT);
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        lockableUnit(lease);

        service.activateLease(lease.getId());

        assertThat(lease.getUnit().getStatus()).isEqualTo(UnitStatus.OCCUPIED);
        assertThat(lease.getUnit().getActualRent()).isEqualByComparingTo("72000");
        assertThat(lease.getUnit().getCurrentTenantName()).isEqualTo("Test Renter");
    }

    @Test
    void renterAcceptance_updatesUnitRevenueAndTenantName() {
        Lease lease = lease(LeaseStatus.PENDING_SIGNATURE);
        UUID userId = UUID.randomUUID();
        lease.getRenter().setUserId(userId);
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        when(renterRepository.findByUserId(userId)).thenReturn(Optional.of(lease.getRenter()));
        lockableUnit(lease);

        service.acceptLease(lease.getId(), userId);

        assertThat(lease.getUnit().getStatus()).isEqualTo(UnitStatus.OCCUPIED);
        assertThat(lease.getUnit().getActualRent()).isEqualByComparingTo("72000");
        assertThat(lease.getUnit().getCurrentTenantName()).isEqualTo("Test Renter");
    }

    @Test
    void termination_clearsUnitRevenueAndTenantName() {
        Lease lease = lease(LeaseStatus.ACTIVE);
        lease.getUnit().setStatus(UnitStatus.OCCUPIED);
        lease.getUnit().setActualRent(new BigDecimal("72000"));
        lease.getUnit().setCurrentTenantName("Test Renter");
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        lockableUnit(lease);

        service.terminateLease(lease.getId(), "Move out complete");

        assertThat(lease.getUnit().getStatus()).isEqualTo(UnitStatus.VACANT);
        assertThat(lease.getUnit().getActualRent()).isZero();
        assertThat(lease.getUnit().getCurrentTenantName()).isNull();
    }

    private Lease lease(LeaseStatus status) {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setNameEn("Test Property");

        Unit unit = new Unit();
        unit.setId(UUID.randomUUID());
        unit.setUnitNumber("101");
        unit.setProperty(property);

        Renter renter = new Renter();
        renter.setId(UUID.randomUUID());
        renter.setNameEn("Test Renter");

        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setTenantId(UUID.randomUUID());
        lease.setUnit(unit);
        lease.setRenter(renter);
        lease.setStatus(status);
        lease.setStartDate(LocalDate.of(2026, 1, 1));
        lease.setEndDate(LocalDate.of(2026, 12, 31));
        lease.setRentAmount(new BigDecimal("72000"));
        return lease;
    }

    // ---- one active lease per unit (#197) ----------------------------------

    /** A different lease already ACTIVE on the same unit. */
    private Lease otherActiveLeaseOn(Lease lease) {
        Lease other = new Lease();
        other.setId(UUID.randomUUID());
        other.setStatus(LeaseStatus.ACTIVE);
        other.setUnit(lease.getUnit());
        return other;
    }

    @Test
    void activateLease_refusesWhenAnotherLeaseAlreadyHoldsTheUnit() {
        Lease lease = lease(LeaseStatus.DRAFT);
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        lockableUnit(lease);
        when(leaseRepository.findByUnitIdAndStatus(lease.getUnit().getId(), LeaseStatus.ACTIVE))
                .thenReturn(List.of(otherActiveLeaseOn(lease)));

        // Before the fix both leases activated and both renters were invoiced
        // for the same unit.
        assertThatThrownBy(() -> service.activateLease(lease.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already has an active lease");
    }

    @Test
    void renterAcceptance_refusesWhenAnotherLeaseAlreadyHoldsTheUnit() {
        Lease lease = lease(LeaseStatus.PENDING_SIGNATURE);
        UUID userId = UUID.randomUUID();
        lease.getRenter().setUserId(userId);
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        when(renterRepository.findByUserId(userId)).thenReturn(Optional.of(lease.getRenter()));
        lockableUnit(lease);
        when(leaseRepository.findByUnitIdAndStatus(lease.getUnit().getId(), LeaseStatus.ACTIVE))
                .thenReturn(List.of(otherActiveLeaseOn(lease)));

        // The renter self-service path repeated the same unguarded activation.
        assertThatThrownBy(() -> service.acceptLease(lease.getId(), userId))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void activateLease_isNotBlockedByItsOwnAlreadyActiveRow() {
        Lease lease = lease(LeaseStatus.DRAFT);
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        lockableUnit(lease);
        // The lease being activated must not count as "another" holder.
        when(leaseRepository.findByUnitIdAndStatus(lease.getUnit().getId(), LeaseStatus.ACTIVE))
                .thenReturn(List.of(lease));

        service.activateLease(lease.getId());

        assertThat(lease.getUnit().getStatus()).isEqualTo(UnitStatus.OCCUPIED);
    }

    @Test
    void termination_leavesTheUnitOccupiedWhenAnotherLeaseIsStillActive() {
        Lease lease = lease(LeaseStatus.ACTIVE);
        lease.getUnit().setStatus(UnitStatus.OCCUPIED);
        lease.getUnit().setActualRent(new BigDecimal("72000"));
        lease.getUnit().setCurrentTenantName("Sitting Renter");
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        lockableUnit(lease);
        when(leaseRepository.findByUnitIdAndStatus(lease.getUnit().getId(), LeaseStatus.ACTIVE))
                .thenReturn(List.of(lease, otherActiveLeaseOn(lease)));

        service.terminateLease(lease.getId(), "Move out complete");

        // Terminating the older of two overlapping leases used to wipe the
        // occupancy of the one still running, freeing a unit someone lives in.
        assertThat(lease.getUnit().getStatus()).isEqualTo(UnitStatus.OCCUPIED);
        assertThat(lease.getUnit().getCurrentTenantName()).isEqualTo("Sitting Renter");
        assertThat(lease.getUnit().getActualRent()).isEqualByComparingTo("72000");
    }

    // ---- extension bills the months it adds (#198) --------------------------

    @Test
    void extendLease_billsTheExtensionThroughTheScheduleService() {
        Lease lease = lease(LeaseStatus.ACTIVE);
        lease.setStartDate(java.time.LocalDate.of(2026, 1, 1));
        lease.setEndDate(java.time.LocalDate.of(2026, 12, 31));
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));

        java.time.LocalDate newEnd = java.time.LocalDate.of(2027, 6, 30);
        service.extendLease(lease.getId(), newEnd);

        // The wiring is the point: extendLease used to move the end date and
        // nothing else, so the extra months were never invoiced. Asserting on
        // the collaborator call is what stops that regressing — a test that only
        // exercised the schedule service directly would not have caught it.
        verify(paymentScheduleService).extendScheduleForLease(
                any(Lease.class),
                eq(java.time.LocalDate.of(2026, 12, 31)),
                eq(newEnd));
        assertThat(lease.getEndDate()).isEqualTo(newEnd);
    }

    // ---- draft deletion releases lease_interactions (#200) ------------------

    @Test
    void deleteDraftLease_alsoRemovesLeaseInteractions() {
        Lease lease = lease(LeaseStatus.DRAFT);
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));

        service.deleteDraftLease(lease.getId());

        // lease_interactions.lease_id is NOT NULL with no ON DELETE clause, and
        // softDelete only stamps deletedAt — the row keeps holding the FK. A
        // single note therefore made the draft permanently undeletable behind an
        // opaque 500, so this delete has to happen before the lease goes.
        verify(leaseInteractionRepository).deleteByLeaseId(lease.getId());
        verify(leaseRepository).delete(lease);
    }
}
