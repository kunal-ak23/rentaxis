package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.LeaseAttachmentRepository;
import com.datagami.rentaxis.domain.repository.LeaseChargeRepository;
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

class LeaseServiceUnitOccupancyTest {

    private LeaseRepository leaseRepository;
    private UnitRepository unitRepository;
    private RenterRepository renterRepository;
    private PaymentScheduleRepository paymentScheduleRepository;
    private LeaseService service;

    @BeforeEach
    void setUp() {
        leaseRepository = mock(LeaseRepository.class);
        unitRepository = mock(UnitRepository.class);
        renterRepository = mock(RenterRepository.class);
        paymentScheduleRepository = mock(PaymentScheduleRepository.class);
        LeaseDocumentRepository leaseDocumentRepository = mock(LeaseDocumentRepository.class);
        LeaseChargeRepository leaseChargeRepository = mock(LeaseChargeRepository.class);

        service = new LeaseService(
                leaseRepository,
                unitRepository,
                renterRepository,
                mock(LeaseEventRepository.class),
                leaseDocumentRepository,
                mock(LeaseAttachmentRepository.class),
                mock(PaymentScheduleService.class),
                paymentScheduleRepository,
                leaseChargeRepository,
                mock(SettlementService.class),
                mock(UnitListingService.class),
                mock(ApplicationEventPublisher.class));

        when(leaseRepository.save(any(Lease.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(leaseDocumentRepository.findByLeaseId(any())).thenReturn(List.of());
        when(leaseChargeRepository.findByLeaseId(any())).thenReturn(List.of());
        when(paymentScheduleRepository.findByLeaseId(any())).thenReturn(List.of());
    }

    @Test
    void activateLease_updatesUnitRevenueAndTenantName() {
        Lease lease = lease(LeaseStatus.DRAFT);
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));

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
}
