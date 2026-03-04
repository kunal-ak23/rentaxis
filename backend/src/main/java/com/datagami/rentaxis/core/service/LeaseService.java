package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseEventDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LeaseService {

    private final LeaseRepository leaseRepository;
    private final UnitRepository unitRepository;
    private final RenterRepository renterRepository;
    private final LeaseEventRepository leaseEventRepository;
    private final LeaseDocumentRepository leaseDocumentRepository;
    private final PaymentScheduleService paymentScheduleService;

    @Transactional(readOnly = true)
    public List<LeaseDTO> getAllLeases() {
        UUID tenantId = TenantContextHolder.getTenantId();
        return leaseRepository.findByTenantId(tenantId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaseDTO> getLeasesByPropertyId(UUID propertyId) {
        return leaseRepository.findByUnitPropertyId(propertyId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public LeaseDTO getLeaseById(UUID id) {
        Lease lease = leaseRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Lease not found"));
        return mapToDTO(lease);
    }

    @Transactional
    public LeaseDTO createDraftLease(CreateLeaseDTO dto) {
        Unit unit = unitRepository.findById(dto.getUnitId())
                .orElseThrow(() -> new RuntimeException("Unit not found"));

        if (unit.getStatus() != UnitStatus.VACANT) {
            throw new RuntimeException("Cannot create lease. Unit is not vacant.");
        }

        Renter renter = renterRepository.findById(dto.getRenterId())
                .orElseThrow(() -> new RuntimeException("Renter not found"));

        Lease lease = new Lease();
        lease.setUnit(unit);
        lease.setRenter(renter);
        lease.setStartDate(dto.getStartDate());
        lease.setEndDate(dto.getEndDate());
        lease.setRentAmount(dto.getRentAmount());
        lease.setDepositAmount(dto.getDepositAmount());
        lease.setEjariNumber(dto.getEjariNumber());
        lease.setPaymentTerms(dto.getPaymentTerms());
        lease.setStatus(LeaseStatus.DRAFT);

        Lease savedLease = leaseRepository.save(lease);
        recordEvent(savedLease, null, LeaseStatus.DRAFT, "Lease drafted");

        return mapToDTO(savedLease);
    }

    @Transactional
    public LeaseDTO activateLease(UUID leaseId) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new RuntimeException("Lease not found"));

        if (lease.getStatus() != LeaseStatus.DRAFT && lease.getStatus() != LeaseStatus.PENDING_SIGNATURE) {
            throw new RuntimeException("Can only activate DRAFT or PENDING_SIGNATURE leases");
        }

        LeaseStatus previousStatus = lease.getStatus();
        lease.setStatus(LeaseStatus.ACTIVE);

        Unit unit = lease.getUnit();
        unit.setStatus(UnitStatus.OCCUPIED);
        unitRepository.save(unit);

        Lease savedLease = leaseRepository.save(lease);
        recordEvent(savedLease, previousStatus, LeaseStatus.ACTIVE, "Lease activated");

        paymentScheduleService.generateScheduleForLease(savedLease);

        return mapToDTO(savedLease);
    }

    @Transactional
    public LeaseDTO terminateLease(UUID leaseId, String notes) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new RuntimeException("Lease not found"));

        if (lease.getStatus() == LeaseStatus.TERMINATED || lease.getStatus() == LeaseStatus.CLOSED) {
            throw new RuntimeException("Lease is already terminated or closed");
        }

        LeaseStatus previousStatus = lease.getStatus();
        lease.setStatus(LeaseStatus.TERMINATED);

        Unit unit = lease.getUnit();
        unit.setStatus(UnitStatus.VACANT);
        unitRepository.save(unit);

        Lease savedLease = leaseRepository.save(lease);
        recordEvent(savedLease, previousStatus, LeaseStatus.TERMINATED,
                notes != null ? notes : "Lease terminated early");

        return mapToDTO(savedLease);
    }

    @Transactional(readOnly = true)
    public List<LeaseDTO> getLeasesForRenterUser(UUID userId) {
        Renter renter = renterRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("No renter profile linked to this user"));
        return leaseRepository.findByRenterId(renter.getId()).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public LeaseDTO acceptLease(UUID leaseId, UUID userId) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new RuntimeException("Lease not found"));

        if (lease.getStatus() != LeaseStatus.PENDING_SIGNATURE) {
            throw new RuntimeException("Can only accept leases in PENDING_SIGNATURE status");
        }

        // Verify the renter owns this lease
        Renter renter = renterRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("No renter profile linked to this user"));
        if (!lease.getRenter().getId().equals(renter.getId())) {
            throw new RuntimeException("You are not authorized to accept this lease");
        }

        LeaseStatus previousStatus = lease.getStatus();
        lease.setStatus(LeaseStatus.ACTIVE);

        Unit unit = lease.getUnit();
        unit.setStatus(UnitStatus.OCCUPIED);
        unitRepository.save(unit);

        Lease savedLease = leaseRepository.save(lease);
        recordEvent(savedLease, previousStatus, LeaseStatus.ACTIVE, "Lease accepted by renter");

        paymentScheduleService.generateScheduleForLease(savedLease);

        return mapToDTO(savedLease);
    }

    @Transactional
    public LeaseDTO rejectLease(UUID leaseId, UUID userId) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new RuntimeException("Lease not found"));

        if (lease.getStatus() != LeaseStatus.PENDING_SIGNATURE) {
            throw new RuntimeException("Can only reject leases in PENDING_SIGNATURE status");
        }

        Renter renter = renterRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("No renter profile linked to this user"));
        if (!lease.getRenter().getId().equals(renter.getId())) {
            throw new RuntimeException("You are not authorized to reject this lease");
        }

        LeaseStatus previousStatus = lease.getStatus();
        lease.setStatus(LeaseStatus.DRAFT);
        Lease savedLease = leaseRepository.save(lease);
        recordEvent(savedLease, previousStatus, LeaseStatus.DRAFT, "Lease rejected by renter");

        return mapToDTO(savedLease);
    }

    @Transactional(readOnly = true)
    public List<LeaseEventDTO> getLeaseEvents(UUID leaseId) {
        return leaseEventRepository.findByLeaseIdOrderByCreatedAtDesc(leaseId).stream()
                .map(this::mapEventToDTO)
                .collect(Collectors.toList());
    }

    private void recordEvent(Lease lease, LeaseStatus prev, LeaseStatus next, String notes) {
        LeaseEvent event = new LeaseEvent();
        event.setLease(lease);
        event.setPreviousState(prev);
        event.setNewState(next);
        event.setNotes(notes);
        event.setCreatedAt(Instant.now());
        leaseEventRepository.save(event);
    }

    private LeaseDTO mapToDTO(Lease lease) {
        LeaseDTO dto = new LeaseDTO();
        dto.setId(lease.getId());
        dto.setUnitId(lease.getUnit().getId());
        dto.setRenterId(lease.getRenter().getId());
        dto.setUnitIdentifier(lease.getUnit().getUnitNumber());
        dto.setRenterName(lease.getRenter().getNameEn());
        dto.setStartDate(lease.getStartDate());
        dto.setEndDate(lease.getEndDate());
        dto.setStatus(lease.getStatus());
        dto.setRentAmount(lease.getRentAmount());
        dto.setDepositAmount(lease.getDepositAmount());
        dto.setEjariNumber(lease.getEjariNumber());
        dto.setPaymentTerms(lease.getPaymentTerms());
        dto.setPropertyId(lease.getUnit().getProperty().getId());
        dto.setPropertyName(lease.getUnit().getProperty().getNameEn());
        dto.setHasContract(!leaseDocumentRepository.findByLeaseId(lease.getId()).isEmpty());
        return dto;
    }

    private LeaseEventDTO mapEventToDTO(LeaseEvent event) {
        LeaseEventDTO dto = new LeaseEventDTO();
        dto.setId(event.getId());
        dto.setLeaseId(event.getLease().getId());
        dto.setPreviousState(event.getPreviousState());
        dto.setNewState(event.getNewState());
        dto.setNotes(event.getNotes());
        dto.setCreatedAt(event.getCreatedAt());
        dto.setCreatedBy(event.getCreatedBy());
        return dto;
    }
}
