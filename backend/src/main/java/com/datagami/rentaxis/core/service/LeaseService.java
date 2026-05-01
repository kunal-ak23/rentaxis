package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseEventDTO;
import com.datagami.rentaxis.api.dto.TerminateWithSettlementDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import org.springframework.context.annotation.Lazy;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentMethod;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.PropertyType;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.*;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class LeaseService {

    private final LeaseRepository leaseRepository;
    private final UnitRepository unitRepository;
    private final RenterRepository renterRepository;
    private final LeaseEventRepository leaseEventRepository;
    private final LeaseDocumentRepository leaseDocumentRepository;
    private final PaymentScheduleService paymentScheduleService;
    private final PaymentScheduleRepository paymentScheduleRepository;
    private final SettlementService settlementService;
    private final UnitListingService unitListingService;

    public LeaseService(LeaseRepository leaseRepository,
                        UnitRepository unitRepository,
                        RenterRepository renterRepository,
                        LeaseEventRepository leaseEventRepository,
                        LeaseDocumentRepository leaseDocumentRepository,
                        PaymentScheduleService paymentScheduleService,
                        PaymentScheduleRepository paymentScheduleRepository,
                        SettlementService settlementService,
                        @Lazy UnitListingService unitListingService) {
        this.leaseRepository = leaseRepository;
        this.unitRepository = unitRepository;
        this.renterRepository = renterRepository;
        this.leaseEventRepository = leaseEventRepository;
        this.leaseDocumentRepository = leaseDocumentRepository;
        this.paymentScheduleService = paymentScheduleService;
        this.paymentScheduleRepository = paymentScheduleRepository;
        this.settlementService = settlementService;
        this.unitListingService = unitListingService;
    }

    @Transactional(readOnly = true)
    public List<LeaseDTO> getAllLeases() {
        UUID tenantId = TenantContextHolder.getTenantId();
        return leaseRepository.findByTenantId(tenantId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<LeaseDTO> getAllLeasesPaged(String search, Pageable pageable) {
        UUID tenantId = TenantContextHolder.getTenantId();
        String normalizedSearch = search == null ? null : search.trim();

        if (normalizedSearch == null || normalizedSearch.isEmpty()) {
            return leaseRepository.findByTenantId(tenantId, pageable).map(this::mapToDTO);
        }

        String token = normalizedSearch.toLowerCase(Locale.ROOT);
        List<LeaseDTO> filtered = leaseRepository.findByTenantId(tenantId).stream()
                .map(this::mapToDTO)
                .filter(l -> containsIgnoreCase(l.getUnitIdentifier(), token)
                        || containsIgnoreCase(l.getRenterName(), token)
                        || containsIgnoreCase(l.getPropertyName(), token)
                        || containsIgnoreCase(l.getEjariNumber(), token)
                        || (l.getStatus() != null && l.getStatus().name().toLowerCase(Locale.ROOT).contains(token)))
                .collect(Collectors.toList());

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        List<LeaseDTO> pageContent = start >= filtered.size() ? List.of() : filtered.subList(start, end);
        return new PageImpl<>(pageContent, pageable, filtered.size());
    }

    @Transactional(readOnly = true)
    public List<LeaseDTO> getLeasesByPropertyId(UUID propertyId) {
        return leaseRepository.findByUnitPropertyId(propertyId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public LeaseDTO getLeaseById(UUID id) {
        Lease lease = findLeaseWithTenantCheck(id);
        return mapToDTO(lease);
    }

    private Lease findLeaseWithTenantCheck(UUID id) {
        Lease lease = leaseRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(lease.getTenantId())) {
            throw new NotFoundException("Lease not found");
        }
        return lease;
    }

    @Transactional
    public LeaseDTO createDraftLease(CreateLeaseDTO dto) {
        Unit unit = unitRepository.findById(dto.getUnitId())
                .orElseThrow(() -> new NotFoundException("Unit not found"));

        if (unit.getStatus() != UnitStatus.VACANT) {
            throw new BusinessRuleViolationException("Cannot create lease. Unit is not vacant.");
        }

        Renter renter = renterRepository.findById(dto.getRenterId())
                .orElseThrow(() -> new NotFoundException("Renter not found"));

        Lease lease = new Lease();
        lease.setUnit(unit);
        lease.setRenter(renter);
        lease.setStartDate(dto.getStartDate());
        lease.setEndDate(dto.getEndDate());
        lease.setRentAmount(dto.getRentAmount());
        lease.setMonthlyRent(dto.getMonthlyRent());
        lease.setDepositAmount(dto.getDepositAmount());
        lease.setEjariNumber(dto.getEjariNumber());
        lease.setPaymentTerms(dto.getPaymentTerms());
        if (dto.getPaymentMethod() != null) {
            lease.setPaymentMethod(PaymentMethod.valueOf(dto.getPaymentMethod()));
        }
        if (dto.getDepositPaymentMethod() != null) {
            lease.setDepositPaymentMethod(PaymentMethod.valueOf(dto.getDepositPaymentMethod()));
        }
        lease.setPaymentReferenceNumber(dto.getPaymentReferenceNumber());

        // Lease agreement: charges, agreement date, per-component VAT flags
        lease.setAgreementDate(dto.getAgreementDate()); // null is OK; ContractGenerationService defaults to today on contract generation
        lease.setAdminFee(dto.getAdminFee() != null ? dto.getAdminFee() : BigDecimal.ZERO);
        lease.setParkingRemoteFee(dto.getParkingRemoteFee() != null ? dto.getParkingRemoteFee() : BigDecimal.ZERO);

        boolean commercialDefault = isCommercialProperty(unit);
        lease.setRentVatApplicable(dto.getRentVatApplicable() != null ? dto.getRentVatApplicable() : commercialDefault);
        lease.setAdminFeeVatApplicable(dto.getAdminFeeVatApplicable() != null ? dto.getAdminFeeVatApplicable() : commercialDefault);
        lease.setSecurityDepositVatApplicable(dto.getSecurityDepositVatApplicable() != null ? dto.getSecurityDepositVatApplicable() : commercialDefault);
        lease.setParkingRemoteVatApplicable(dto.getParkingRemoteVatApplicable() != null ? dto.getParkingRemoteVatApplicable() : commercialDefault);

        lease.setStatus(LeaseStatus.DRAFT);

        Lease savedLease = leaseRepository.save(lease);

        if (dto.getBookingDeposit() != null && dto.getBookingDeposit().getAmount() != null
                && dto.getBookingDeposit().getAmount().signum() > 0) {
            CreateLeaseDTO.BookingDepositDTO bd = dto.getBookingDeposit();
            // We can't call the public addBookingDeposit (which calls findLeaseWithTenantCheck) here because
            // we already have the saved lease in scope. Inline the booking-row creation:
            PaymentSchedule booking = new PaymentSchedule();
            booking.setLease(savedLease);
            booking.setUnit(savedLease.getUnit());
            booking.setProperty(savedLease.getUnit().getProperty());
            booking.setInstallmentNumber(0);
            booking.setDueDate(bd.getChequeDate() != null ? bd.getChequeDate() : LocalDate.now());
            booking.setChequeDate(bd.getChequeDate());
            booking.setChequeNumber(bd.getChequeNumber());
            booking.setBankName(bd.getBankName());
            booking.setAmount(bd.getAmount());
            booking.setStatus(PaymentStatus.PENDING);
            booking.setPaymentMethod(savedLease.getPaymentMethod() != null ? savedLease.getPaymentMethod().name() : "CHEQUE");
            booking.setPurposeLabel("BOOKING RECEIVED");
            booking.setBookingDeposit(true);
            paymentScheduleRepository.save(booking);
        }

        // Generate the rent installment schedule eagerly so the contract PDF's
        // Section 4 (Payment Details) is populated before the lease is
        // activated. The /ADMIN/SD/REMOTE bundling on installment 1 already
        // covers the security deposit per the client reference.
        // Booking-deposit rows already saved above are preserved by the
        // !isBookingDeposit() filter inside generateScheduleForLease.
        paymentScheduleService.generateScheduleForLease(savedLease);

        recordEvent(savedLease, null, LeaseStatus.DRAFT, "Lease drafted");

        return mapToDTO(savedLease);
    }

    @Transactional
    public PaymentSchedule addBookingDeposit(UUID leaseId, BigDecimal amount,
                                              String chequeNumber, LocalDate chequeDate, String bankName) {
        Lease lease = findLeaseWithTenantCheck(leaseId);
        PaymentSchedule ps = new PaymentSchedule();
        ps.setLease(lease);
        ps.setUnit(lease.getUnit());
        ps.setProperty(lease.getUnit().getProperty());
        ps.setInstallmentNumber(0);
        ps.setDueDate(chequeDate != null ? chequeDate : LocalDate.now());
        ps.setChequeDate(chequeDate);
        ps.setChequeNumber(chequeNumber);
        ps.setBankName(bankName);
        ps.setAmount(amount);
        ps.setStatus(PaymentStatus.PENDING);
        ps.setPaymentMethod(lease.getPaymentMethod() != null ? lease.getPaymentMethod().name() : "CHEQUE");
        ps.setPurposeLabel("BOOKING RECEIVED");
        ps.setBookingDeposit(true);
        return paymentScheduleRepository.save(ps);
    }

    @Transactional
    public LeaseDTO updateDraftLease(UUID leaseId, CreateLeaseDTO dto) {
        Lease lease = findLeaseWithTenantCheck(leaseId);

        if (lease.getStatus() != LeaseStatus.DRAFT) {
            throw new BusinessRuleViolationException("Only DRAFT leases can be edited");
        }

        // If unit changed, validate the new unit is vacant
        if (!lease.getUnit().getId().equals(dto.getUnitId())) {
            Unit newUnit = unitRepository.findById(dto.getUnitId())
                    .orElseThrow(() -> new NotFoundException("Unit not found"));
            if (newUnit.getStatus() != UnitStatus.VACANT) {
                throw new BusinessRuleViolationException("Cannot assign lease. Unit is not vacant.");
            }
            lease.setUnit(newUnit);
        }

        // If renter changed
        if (!lease.getRenter().getId().equals(dto.getRenterId())) {
            Renter renter = renterRepository.findById(dto.getRenterId())
                    .orElseThrow(() -> new NotFoundException("Renter not found"));
            lease.setRenter(renter);
        }

        lease.setStartDate(dto.getStartDate());
        lease.setEndDate(dto.getEndDate());
        lease.setRentAmount(dto.getRentAmount());
        lease.setMonthlyRent(dto.getMonthlyRent());
        lease.setDepositAmount(dto.getDepositAmount());
        lease.setEjariNumber(dto.getEjariNumber());
        lease.setPaymentTerms(dto.getPaymentTerms());
        if (dto.getPaymentMethod() != null) {
            lease.setPaymentMethod(PaymentMethod.valueOf(dto.getPaymentMethod()));
        }
        if (dto.getDepositPaymentMethod() != null) {
            lease.setDepositPaymentMethod(PaymentMethod.valueOf(dto.getDepositPaymentMethod()));
        }
        lease.setPaymentReferenceNumber(dto.getPaymentReferenceNumber());

        // Lease agreement fields (allow update; null on Boolean toggles means "no change", null on amounts means "no change")
        if (dto.getAgreementDate() != null) lease.setAgreementDate(dto.getAgreementDate());
        if (dto.getAdminFee() != null) lease.setAdminFee(dto.getAdminFee());
        if (dto.getParkingRemoteFee() != null) lease.setParkingRemoteFee(dto.getParkingRemoteFee());
        if (dto.getRentVatApplicable() != null) lease.setRentVatApplicable(dto.getRentVatApplicable());
        if (dto.getAdminFeeVatApplicable() != null) lease.setAdminFeeVatApplicable(dto.getAdminFeeVatApplicable());
        if (dto.getSecurityDepositVatApplicable() != null) lease.setSecurityDepositVatApplicable(dto.getSecurityDepositVatApplicable());
        if (dto.getParkingRemoteVatApplicable() != null) lease.setParkingRemoteVatApplicable(dto.getParkingRemoteVatApplicable());

        Lease savedLease = leaseRepository.save(lease);

        // The lease parameters (rent / dates / terms / fees / VAT) feed into the
        // installment schedule that Section 4 of the contract renders. After an
        // edit, drop the previously-generated installments (only the PENDING
        // ones — never touch booking deposits or anything already collected)
        // and regenerate so the next contract preview reflects the new numbers.
        List<PaymentSchedule> regenTargets = paymentScheduleRepository.findByLeaseId(savedLease.getId()).stream()
                .filter(p -> !p.isBookingDeposit())
                .filter(p -> p.getStatus() == PaymentStatus.PENDING)
                .toList();
        if (!regenTargets.isEmpty()) {
            paymentScheduleRepository.deleteAll(regenTargets);
            paymentScheduleRepository.flush();
        }
        paymentScheduleService.generateScheduleForLease(savedLease);

        recordEvent(savedLease, LeaseStatus.DRAFT, LeaseStatus.DRAFT, "Lease updated");

        return mapToDTO(savedLease);
    }

    @Transactional
    public LeaseDTO activateLease(UUID leaseId) {
        Lease lease = findLeaseWithTenantCheck(leaseId);

        if (lease.getStatus() != LeaseStatus.DRAFT && lease.getStatus() != LeaseStatus.PENDING_SIGNATURE) {
            throw new BusinessRuleViolationException("Can only activate DRAFT or PENDING_SIGNATURE leases");
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
        Lease lease = findLeaseWithTenantCheck(leaseId);

        if (lease.getStatus() == LeaseStatus.TERMINATED || lease.getStatus() == LeaseStatus.CLOSED) {
            throw new BusinessRuleViolationException("Lease is already terminated or closed");
        }

        LeaseStatus previousStatus = lease.getStatus();
        lease.setStatus(LeaseStatus.TERMINATED);

        Unit unit = lease.getUnit();
        unit.setStatus(UnitStatus.VACANT);
        unitRepository.save(unit);

        // Cancel pending payment schedules
        List<PaymentSchedule> pendingPayments = paymentScheduleRepository.findByLeaseId(leaseId);
        for (PaymentSchedule ps : pendingPayments) {
            if (ps.getStatus() == PaymentStatus.PENDING || ps.getStatus() == PaymentStatus.ONLINE_PENDING || ps.getStatus() == PaymentStatus.OVERDUE) {
                ps.setStatus(PaymentStatus.CANCELLED);
                paymentScheduleRepository.save(ps);
            }
        }

        Lease savedLease = leaseRepository.save(lease);
        recordEvent(savedLease, previousStatus, LeaseStatus.TERMINATED,
                notes != null ? notes : "Lease terminated early");

        // Clear listing availability and notify interested renters
        try {
            unitListingService.syncAvailableFrom(unit.getId(), null);
        } catch (Exception e) {
            // Non-critical: listing sync failure should not block termination
        }

        return mapToDTO(savedLease);
    }

    @Transactional
    public LeaseDTO extendLease(UUID leaseId, LocalDate newEndDate) {
        Lease lease = findLeaseWithTenantCheck(leaseId);

        if (lease.getStatus() != LeaseStatus.ACTIVE) {
            throw new BusinessRuleViolationException("Only ACTIVE leases can be extended");
        }
        if (!newEndDate.isAfter(lease.getEndDate())) {
            throw new BusinessRuleViolationException("New end date must be after the current end date");
        }

        LocalDate previousEndDate = lease.getEndDate();
        lease.setEndDate(newEndDate);
        Lease savedLease = leaseRepository.save(lease);
        recordEvent(savedLease, LeaseStatus.ACTIVE, LeaseStatus.ACTIVE,
                "Lease extended from " + previousEndDate + " to " + newEndDate);

        // Update listing availability and notify interested renters
        try {
            unitListingService.syncAvailableFrom(lease.getUnit().getId(), newEndDate);
        } catch (Exception e) {
            // Non-critical
        }

        return mapToDTO(savedLease);
    }

    @Transactional
    public LeaseDTO terminateWithSettlement(UUID leaseId, TerminateWithSettlementDTO dto, UUID settledBy) {
        // Create settlement first (within same transaction)
        if (dto != null && dto.getDeductions() != null && !dto.getDeductions().isEmpty()) {
            settlementService.createSettlement(leaseId, dto, settledBy);
        }
        // Then terminate
        return terminateLease(leaseId, dto != null ? dto.getNotes() : null);
    }

    @Transactional(readOnly = true)
    public List<LeaseDTO> getLeasesForRenterUser(UUID userId) {
        Renter renter = renterRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("No renter profile linked to this user"));
        return leaseRepository.findByRenterId(renter.getId()).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public LeaseDTO acceptLease(UUID leaseId, UUID userId) {
        Lease lease = findLeaseWithTenantCheck(leaseId);

        if (lease.getStatus() != LeaseStatus.PENDING_SIGNATURE) {
            throw new BusinessRuleViolationException("Can only accept leases in PENDING_SIGNATURE status");
        }

        // Verify the renter owns this lease
        Renter renter = renterRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("No renter profile linked to this user"));
        if (!lease.getRenter().getId().equals(renter.getId())) {
            throw new com.datagami.rentaxis.api.exception.AccessDeniedException("You are not authorized to accept this lease");
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
        Lease lease = findLeaseWithTenantCheck(leaseId);

        if (lease.getStatus() != LeaseStatus.PENDING_SIGNATURE) {
            throw new BusinessRuleViolationException("Can only reject leases in PENDING_SIGNATURE status");
        }

        Renter renter = renterRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("No renter profile linked to this user"));
        if (!lease.getRenter().getId().equals(renter.getId())) {
            throw new com.datagami.rentaxis.api.exception.AccessDeniedException("You are not authorized to reject this lease");
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
        dto.setPaymentMethod(lease.getPaymentMethod());
        dto.setDepositPaymentMethod(lease.getDepositPaymentMethod());
        dto.setPaymentReferenceNumber(lease.getPaymentReferenceNumber());
        dto.setMonthlyRent(lease.getMonthlyRent());
        dto.setPropertyId(lease.getUnit().getProperty().getId());
        dto.setPropertyName(lease.getUnit().getProperty().getNameEn());
        dto.setHasContract(!leaseDocumentRepository.findByLeaseId(lease.getId()).isEmpty());
        dto.setContractNumber(lease.getContractNumber());
        dto.setAgreementDate(lease.getAgreementDate());
        dto.setAdminFee(lease.getAdminFee());
        dto.setParkingRemoteFee(lease.getParkingRemoteFee());
        dto.setRentVatApplicable(lease.isRentVatApplicable());
        dto.setAdminFeeVatApplicable(lease.isAdminFeeVatApplicable());
        dto.setSecurityDepositVatApplicable(lease.isSecurityDepositVatApplicable());
        dto.setParkingRemoteVatApplicable(lease.isParkingRemoteVatApplicable());
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

    private boolean containsIgnoreCase(String value, String token) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(token);
    }

    private static boolean isCommercialProperty(Unit unit) {
        PropertyType type = unit.getProperty().getType();
        return type == PropertyType.COMMERCIAL;
    }
}
