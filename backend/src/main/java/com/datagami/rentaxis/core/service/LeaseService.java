package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseEventDTO;
import com.datagami.rentaxis.api.dto.TerminateWithSettlementDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.LeasePayload;
import com.datagami.rentaxis.api.dto.LeaseChargeDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.ChargeFrequency;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;
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
import java.time.OffsetDateTime;
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
    private final LeaseAttachmentRepository leaseAttachmentRepository;
    private final PaymentScheduleService paymentScheduleService;
    private final PaymentScheduleRepository paymentScheduleRepository;
    private final LeaseChargeRepository leaseChargeRepository;
    private final SettlementService settlementService;
    private final UnitListingService unitListingService;
    private final ApplicationEventPublisher events;

    public LeaseService(LeaseRepository leaseRepository,
                        UnitRepository unitRepository,
                        RenterRepository renterRepository,
                        LeaseEventRepository leaseEventRepository,
                        LeaseDocumentRepository leaseDocumentRepository,
                        LeaseAttachmentRepository leaseAttachmentRepository,
                        PaymentScheduleService paymentScheduleService,
                        PaymentScheduleRepository paymentScheduleRepository,
                        LeaseChargeRepository leaseChargeRepository,
                        SettlementService settlementService,
                        @Lazy UnitListingService unitListingService,
                        ApplicationEventPublisher events) {
        this.leaseRepository = leaseRepository;
        this.unitRepository = unitRepository;
        this.renterRepository = renterRepository;
        this.leaseEventRepository = leaseEventRepository;
        this.leaseDocumentRepository = leaseDocumentRepository;
        this.leaseAttachmentRepository = leaseAttachmentRepository;
        this.paymentScheduleService = paymentScheduleService;
        this.paymentScheduleRepository = paymentScheduleRepository;
        this.leaseChargeRepository = leaseChargeRepository;
        this.settlementService = settlementService;
        this.unitListingService = unitListingService;
        this.events = events;
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

    /**
     * Marks the lease's unit occupied, refusing if another lease already holds
     * it.
     *
     * <p>Activation used to set the unit OCCUPIED unconditionally. Nothing
     * re-read the unit after {@code createDraftLease}'s vacancy check, and that
     * check does not reserve anything — the unit stays VACANT while a draft
     * exists — so two drafts on one unit could both activate. Both leases then
     * generated schedules and invoiced their renters, and the unit's
     * {@code currentTenantName} and {@code actualRent} reflected whichever
     * activated last. Changesets 68 and 70 exist to repair exactly this drift in
     * production, and 68's {@code SELECT DISTINCT ON (unit_id)} already had to
     * pick one of several ACTIVE leases per unit in live data.
     *
     * <p>The unit row is locked FOR UPDATE first so the check and the flip
     * cannot interleave with a concurrent activation. A service-layer check
     * without the lock still races; see also changeset 80, which adds the
     * database constraint that closes the window for good.
     */
    private void claimUnitForLease(Lease lease) {
        Unit unit = unitRepository.findByIdForUpdate(lease.getUnit().getId())
                .orElseThrow(() -> new NotFoundException("Unit not found"));

        boolean heldByAnother = leaseRepository.findByUnitIdAndStatus(unit.getId(), LeaseStatus.ACTIVE).stream()
                .anyMatch(other -> !other.getId().equals(lease.getId()));
        if (heldByAnother) {
            throw new BusinessRuleViolationException(
                    "This unit already has an active lease. Terminate it before activating another.");
        }

        unit.setStatus(UnitStatus.OCCUPIED);
        unit.setCurrentTenantName(lease.getRenter().getNameEn());
        unit.setActualRent(lease.getRentAmount() != null ? lease.getRentAmount() : BigDecimal.ZERO);
        unitRepository.save(unit);
    }

    /**
     * Vacates the unit only when no other ACTIVE lease still holds it.
     *
     * <p>Termination used to vacate unconditionally, so ending the older of two
     * overlapping leases wiped the occupancy of the one still running — the unit
     * showed VACANT and became lettable a third time while a renter was living
     * in it. Overlaps should no longer be creatable, but production already
     * contains some, and this must not make those worse.
     */
    private void releaseUnitIfNoOtherActiveLease(Lease lease) {
        Unit unit = unitRepository.findByIdForUpdate(lease.getUnit().getId())
                .orElse(lease.getUnit());

        List<Lease> stillActive = leaseRepository.findByUnitIdAndStatus(unit.getId(), LeaseStatus.ACTIVE).stream()
                .filter(other -> !other.getId().equals(lease.getId()))
                .toList();
        if (!stillActive.isEmpty()) {
            // Leave the unit as it is: another lease is live on it. Its own
            // termination will vacate the unit.
            return;
        }

        unit.setStatus(UnitStatus.VACANT);
        unit.setCurrentTenantName(null);
        unit.setActualRent(BigDecimal.ZERO);
        unitRepository.save(unit);
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
        lease.setInstallmentDistribution(dto.getInstallmentDistribution() != null
                ? dto.getInstallmentDistribution() : InstallmentDistribution.LAST_LARGER);
        if (dto.getPaymentMethod() != null) {
            lease.setPaymentMethod(PaymentMethod.valueOf(dto.getPaymentMethod()));
        }
        if (dto.getDepositPaymentMethod() != null) {
            lease.setDepositPaymentMethod(PaymentMethod.valueOf(dto.getDepositPaymentMethod()));
        }
        lease.setPaymentReferenceNumber(dto.getPaymentReferenceNumber());

        // Lease agreement: agreement date + rent VAT toggle. Other charges are
        // now flexible LeaseCharge rows persisted separately below.
        lease.setAgreementDate(dto.getAgreementDate()); // null is OK; ContractGenerationService defaults to today on contract generation

        boolean commercialDefault = isCommercialProperty(unit);
        lease.setRentVatApplicable(dto.getRentVatApplicable() != null ? dto.getRentVatApplicable() : commercialDefault);

        lease.setStatus(LeaseStatus.DRAFT);

        Lease savedLease = leaseRepository.save(lease);

        // Persist the flexible charges, then emit one-time-charge and
        // security-deposit schedule rows (real collected money, additive VAT).
        syncCharges(savedLease, dto.getCharges());
        createOneTimeChargeAndDepositRows(savedLease, dto.getCharges());

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
        // activated. Per-installment charges are folded into each rent row;
        // one-time charges and the security deposit are their own rows (created
        // above). Booking-deposit / SD / charge rows are preserved by the
        // generation guard inside generateScheduleForLease.
        paymentScheduleService.generateScheduleForLease(savedLease);

        recordEvent(savedLease, null, LeaseStatus.DRAFT, "Lease drafted");

        events.publishEvent(new EmailEvent(this,
                EmailEventType.LEASE_CREATED,
                savedLease.getTenantId(),
                buildLeasePayload(savedLease),
                "LEASE_CREATED:" + savedLease.getId()));

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
        lease.setInstallmentDistribution(dto.getInstallmentDistribution() != null
                ? dto.getInstallmentDistribution() : InstallmentDistribution.LAST_LARGER);
        if (dto.getPaymentMethod() != null) {
            lease.setPaymentMethod(PaymentMethod.valueOf(dto.getPaymentMethod()));
        }
        if (dto.getDepositPaymentMethod() != null) {
            lease.setDepositPaymentMethod(PaymentMethod.valueOf(dto.getDepositPaymentMethod()));
        }
        lease.setPaymentReferenceNumber(dto.getPaymentReferenceNumber());

        // Lease agreement fields (allow update; null on Boolean toggles means "no change")
        if (dto.getAgreementDate() != null) lease.setAgreementDate(dto.getAgreementDate());
        if (dto.getRentVatApplicable() != null) lease.setRentVatApplicable(dto.getRentVatApplicable());

        Lease savedLease = leaseRepository.save(lease);

        // The lease parameters (rent / dates / terms / charges / VAT) feed into
        // the installment schedule that Section 4 of the contract renders. After
        // an edit, drop the previously-generated installments AND the
        // charge / security-deposit rows (only the PENDING ones — never touch
        // booking deposits or anything already collected) and regenerate so the
        // next contract preview reflects the new numbers.
        List<PaymentSchedule> regenTargets = paymentScheduleRepository.findByLeaseId(savedLease.getId()).stream()
                .filter(p -> !p.isBookingDeposit())
                .filter(p -> p.getStatus() == PaymentStatus.PENDING)
                .toList();
        if (!regenTargets.isEmpty()) {
            paymentScheduleRepository.deleteAll(regenTargets);
            paymentScheduleRepository.flush();
        }
        // Re-sync the flexible charges when the client sends them (null = no
        // change to charges, e.g. a lighter edit that only touches dates/rent).
        if (dto.getCharges() != null) {
            syncCharges(savedLease, dto.getCharges());
        }
        createOneTimeChargeAndDepositRows(savedLease, leaseChargeRepository.findByLeaseId(savedLease.getId()).stream()
                .map(LeaseService::toChargeDTO)
                .toList());
        paymentScheduleService.generateScheduleForLease(savedLease);

        recordEvent(savedLease, LeaseStatus.DRAFT, LeaseStatus.DRAFT, "Lease updated");

        return mapToDTO(savedLease);
    }

    /**
     * Bulk-update the editable fields on a lease's payment schedule. Allowed
     * only on DRAFT or PENDING_SIGNATURE leases; once the lease is activated,
     * the schedule is locked.
     * <p>
     * Per-row payment method drives which fields are required:
     * <ul>
     *     <li>CHEQUE: chequeNumber, chequeDate, bankName all required</li>
     *     <li>BANK_TRANSFER / ONLINE: bankName + chequeDate (= transfer date) required</li>
     *     <li>CASH: just amount + dueDate; chequeDate optional (= receipt date)</li>
     * </ul>
     */
    @Transactional
    public List<PaymentSchedule> updatePaymentSchedule(UUID leaseId, com.datagami.rentaxis.api.dto.UpdatePaymentScheduleDTO dto) {
        Lease lease = findLeaseWithTenantCheck(leaseId);
        if (lease.getStatus() != LeaseStatus.DRAFT && lease.getStatus() != LeaseStatus.PENDING_SIGNATURE) {
            throw new BusinessRuleViolationException(
                    "Payment schedule can only be edited while the lease is DRAFT or PENDING_SIGNATURE");
        }
        if (dto.getRows() == null || dto.getRows().isEmpty()) {
            return paymentScheduleRepository.findByLeaseId(leaseId);
        }
        java.util.Map<UUID, PaymentSchedule> existing = paymentScheduleRepository.findByLeaseId(leaseId).stream()
                .collect(java.util.stream.Collectors.toMap(PaymentSchedule::getId, p -> p));

        for (com.datagami.rentaxis.api.dto.UpdatePaymentScheduleDTO.Row row : dto.getRows()) {
            PaymentSchedule ps = existing.get(row.getScheduleId());
            if (ps == null) {
                throw new NotFoundException("Payment schedule row " + row.getScheduleId() + " does not belong to lease " + leaseId);
            }
            // Don't allow editing rows that have already been collected.
            if (ps.getStatus() != PaymentStatus.PENDING) {
                throw new BusinessRuleViolationException(
                        "Cannot edit payment schedule row " + ps.getInstallmentNumber()
                                + " — it is already in status " + ps.getStatus());
            }
            String methodRaw = row.getPaymentMethod() == null ? "" : row.getPaymentMethod().trim().toUpperCase();
            switch (methodRaw) {
                case "CHEQUE" -> {
                    if (isBlank(row.getChequeNumber()) || row.getChequeDate() == null || isBlank(row.getBankName())) {
                        throw new BusinessRuleViolationException(
                                "CHEQUE rows require chequeNumber, chequeDate and bankName");
                    }
                }
                case "BANK_TRANSFER", "ONLINE" -> {
                    if (isBlank(row.getBankName()) || row.getChequeDate() == null) {
                        throw new BusinessRuleViolationException(
                                row.getPaymentMethod() + " rows require bankName and a transfer date");
                    }
                }
                case "CASH" -> {
                    // Only amount + dueDate required; nothing else mandatory.
                }
                default -> throw new BusinessRuleViolationException(
                        "Unsupported payment method: " + row.getPaymentMethod());
            }

            ps.setDueDate(row.getDueDate());
            ps.setAmount(row.getAmount());
            ps.setPaymentMethod(methodRaw);
            ps.setChequeNumber(emptyToNull(row.getChequeNumber()));
            ps.setChequeDate(row.getChequeDate());
            ps.setBankName(emptyToNull(row.getBankName()));
            ps.setChequeImageUrl(emptyToNull(row.getChequeImageUrl()));
            ps.setChequeImageBlobPath(emptyToNull(row.getChequeImageBlobPath()));
            ps.setChequeImageUploadedAt(resolveChequeImageUploadedAt(row.getChequeImageBlobPath(), row.getChequeImageUploadedAt()));
        }
        return paymentScheduleRepository.saveAll(existing.values().stream()
                // Return rows in installment order so the UI can render them deterministically.
                .sorted(java.util.Comparator
                        .comparing(PaymentSchedule::isBookingDeposit)
                        .thenComparingInt(PaymentSchedule::getInstallmentNumber))
                .toList());
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String emptyToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private static OffsetDateTime resolveChequeImageUploadedAt(String blobPath, OffsetDateTime uploadedAt) {
        if (isBlank(blobPath)) {
            return null;
        }
        return uploadedAt != null ? uploadedAt : OffsetDateTime.now();
    }

    /**
     * Hard-delete a DRAFT lease and all rows that hang off it (payment
     * schedule, lease events, attachments). Only DRAFT is supported — once a
     * lease has gone to PENDING_SIGNATURE / ACTIVE / TERMINATED there are
     * downstream financial entries that should not be silently removed.
     */
    @Transactional
    public void deleteDraftLease(UUID leaseId) {
        Lease lease = findLeaseWithTenantCheck(leaseId);
        if (lease.getStatus() != LeaseStatus.DRAFT) {
            throw new BusinessRuleViolationException("Only DRAFT leases can be deleted");
        }
        // Defensive: a DRAFT lease shouldn't have any contract documents, but
        // if a previous flow left one behind, drop the row(s) too.
        leaseDocumentRepository.deleteAll(leaseDocumentRepository.findByLeaseId(leaseId));
        leaseAttachmentRepository.deleteAll(leaseAttachmentRepository.findByLeaseId(leaseId));
        paymentScheduleRepository.deleteAll(paymentScheduleRepository.findByLeaseId(leaseId));
        // lease_charges FK is NO ACTION (like payment_schedules); remove the
        // lease's charges before deleting the lease or the FK constraint blocks it.
        leaseChargeRepository.deleteAll(leaseChargeRepository.findByLeaseId(leaseId));
        leaseEventRepository.deleteAll(leaseEventRepository.findByLeaseIdOrderByCreatedAtDesc(leaseId));
        leaseRepository.delete(lease);
    }

    @Transactional
    public LeaseDTO activateLease(UUID leaseId) {
        Lease lease = findLeaseWithTenantCheck(leaseId);

        if (lease.getStatus() != LeaseStatus.DRAFT && lease.getStatus() != LeaseStatus.PENDING_SIGNATURE) {
            throw new BusinessRuleViolationException("Can only activate DRAFT or PENDING_SIGNATURE leases");
        }

        LeaseStatus previousStatus = lease.getStatus();
        lease.setStatus(LeaseStatus.ACTIVE);

        claimUnitForLease(lease);

        Lease savedLease = leaseRepository.save(lease);
        recordEvent(savedLease, previousStatus, LeaseStatus.ACTIVE, "Lease activated");

        paymentScheduleService.generateScheduleForLease(savedLease);

        events.publishEvent(new EmailEvent(this,
                EmailEventType.LEASE_ACTIVATED,
                savedLease.getTenantId(),
                buildLeasePayload(savedLease),
                "LEASE_ACTIVATED:" + savedLease.getId()));

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

        releaseUnitIfNoOtherActiveLease(lease);

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

        events.publishEvent(new EmailEvent(this,
                EmailEventType.LEASE_TERMINATED,
                savedLease.getTenantId(),
                buildLeasePayload(savedLease),
                "LEASE_TERMINATED:" + savedLease.getId() + ":" + Instant.now().toEpochMilli()));

        // Clear listing availability and notify interested renters
        try {
            unitListingService.syncAvailableFrom(savedLease.getUnit().getId(), null);
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

        claimUnitForLease(lease);

        Lease savedLease = leaseRepository.save(lease);
        recordEvent(savedLease, previousStatus, LeaseStatus.ACTIVE, "Lease accepted by renter");

        paymentScheduleService.generateScheduleForLease(savedLease);

        events.publishEvent(new EmailEvent(this,
                EmailEventType.LEASE_SIGNED,
                savedLease.getTenantId(),
                buildLeasePayload(savedLease),
                "LEASE_SIGNED:" + savedLease.getId()));

        events.publishEvent(new EmailEvent(this,
                EmailEventType.LEASE_ACTIVATED,
                savedLease.getTenantId(),
                buildLeasePayload(savedLease),
                "LEASE_ACTIVATED:" + savedLease.getId()));

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
        dto.setInstallmentDistribution(lease.getInstallmentDistribution());
        dto.setPaymentMethod(lease.getPaymentMethod());
        dto.setDepositPaymentMethod(lease.getDepositPaymentMethod());
        dto.setPaymentReferenceNumber(lease.getPaymentReferenceNumber());
        dto.setMonthlyRent(lease.getMonthlyRent());
        dto.setPropertyId(lease.getUnit().getProperty().getId());
        dto.setPropertyName(lease.getUnit().getProperty().getNameEn());
        dto.setHasContract(!leaseDocumentRepository.findByLeaseId(lease.getId()).isEmpty());
        dto.setContractNumber(lease.getContractNumber());
        dto.setAgreementDate(lease.getAgreementDate());
        dto.setRentVatApplicable(lease.isRentVatApplicable());
        dto.setCharges(leaseChargeRepository.findByLeaseId(lease.getId()).stream()
                .map(LeaseService::toChargeDTO)
                .collect(Collectors.toList()));
        return dto;
    }

    private static LeaseChargeDTO toChargeDTO(LeaseCharge c) {
        LeaseChargeDTO dto = new LeaseChargeDTO();
        dto.setName(c.getName());
        dto.setAmount(c.getAmount());
        dto.setVatApplicable(c.isVatApplicable());
        dto.setFrequency(c.getFrequency());
        return dto;
    }

    /**
     * Replace the lease's persisted {@link LeaseCharge} rows with the supplied
     * DTOs (delete-then-insert). Called on create and on draft edits so charges
     * stay in sync with the wizard. {@code tenant_id} is set explicitly from the
     * lease; {@link BaseTenantEntity#onPrePersist} would also default it, but
     * setting it here is harmless and keeps the row self-consistent.
     */
    private void syncCharges(Lease lease, List<LeaseChargeDTO> charges) {
        List<LeaseCharge> existing = leaseChargeRepository.findByLeaseId(lease.getId());
        if (!existing.isEmpty()) {
            leaseChargeRepository.deleteAll(existing);
            leaseChargeRepository.flush();
        }
        if (charges == null) {
            return;
        }
        for (LeaseChargeDTO c : charges) {
            LeaseCharge charge = new LeaseCharge();
            charge.setLease(lease);
            charge.setTenantId(lease.getTenantId());
            charge.setName(c.getName());
            charge.setAmount(c.getAmount() != null ? c.getAmount() : BigDecimal.ZERO);
            charge.setVatApplicable(c.isVatApplicable());
            charge.setFrequency(c.getFrequency() != null ? c.getFrequency() : ChargeFrequency.ONE_TIME);
            leaseChargeRepository.save(charge);
        }
    }

    /**
     * Emit a schedule row per ONE_TIME charge (additive VAT baked in) and one
     * row for the refundable security deposit (never VAT). Per-installment
     * charges are folded into the rent rows by PaymentScheduleService instead.
     * <p>
     * Idempotent: this runs both on create and on every draft edit (where only
     * PENDING non-booking rows are dropped beforehand). A SECURITY DEPOSIT row
     * or a one-time charge row that already exists in a non-PENDING state (e.g.
     * already collected) is NOT recreated, so editing a lease never duplicates a
     * deposit / charge that's already been paid. Matching is by row kind
     * (is_security_deposit / is_charge + purposeLabel), across all statuses.
     */
    private void createOneTimeChargeAndDepositRows(Lease lease, List<LeaseChargeDTO> charges) {
        List<PaymentSchedule> existingRows = paymentScheduleRepository.findByLeaseId(lease.getId());
        java.util.Set<String> existingChargeLabels = existingRows.stream()
                .filter(PaymentSchedule::isCharge)
                .map(PaymentSchedule::getPurposeLabel)
                .collect(Collectors.toSet());
        boolean depositRowExists = existingRows.stream().anyMatch(PaymentSchedule::isSecurityDeposit);

        if (charges != null) {
            for (LeaseChargeDTO c : charges) {
                if (c.getFrequency() != ChargeFrequency.ONE_TIME) continue;
                // Skip a one-time charge that already has a row for this lease
                // (any status) — avoids duplicating a charge already collected.
                if (existingChargeLabels.contains(c.getName())) continue;
                PaymentSchedule row = PaymentScheduleService.newOneTimeChargeRow(
                        lease, c.getName(),
                        PaymentScheduleService.withVat(c.getAmount(), c.isVatApplicable()),
                        PaymentScheduleService.vatOf(c.getAmount(), c.isVatApplicable()));
                paymentScheduleRepository.save(row);
                // Guard against duplicate names within the same charges payload.
                existingChargeLabels.add(c.getName());
            }
        }

        // Only create the SD row when there's a deposit AND no SD row already
        // exists for this lease (any status) — never duplicate a collected SD.
        if (!depositRowExists
                && lease.getDepositAmount() != null && lease.getDepositAmount().signum() > 0) {
            paymentScheduleRepository.save(
                    PaymentScheduleService.newSecurityDepositRow(lease, lease.getDepositAmount()));
        }
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

    private LeasePayload buildLeasePayload(Lease lease) {
        BigDecimal monthly = lease.getMonthlyRent() != null ? lease.getMonthlyRent() : lease.getRentAmount();
        return new LeasePayload(
                lease.getId(),
                lease.getRenter().getUserId(),
                null,  // propertyManagerUserId — not stored on Lease; RecipientResolver falls back to tenant admins
                lease.getUnit().getUnitNumber(),
                lease.getUnit().getProperty().getNameEn(),
                lease.getStartDate() != null ? lease.getStartDate().toString() : null,
                lease.getEndDate() != null ? lease.getEndDate().toString() : null,
                monthly != null ? monthly.toPlainString() : null,
                null   // contractSignedUrl — not available at runtime; template uses safe-nav
        );
    }
}
