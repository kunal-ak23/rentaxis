package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.AgingReportDTO;
import com.datagami.rentaxis.api.dto.BulkAttachChequeItem;
import com.datagami.rentaxis.api.dto.BulkAttachErrorRow;
import com.datagami.rentaxis.api.dto.PaymentScheduleDTO;
import com.datagami.rentaxis.api.dto.LeasePaymentStatsDTO;
import com.datagami.rentaxis.api.dto.PaymentSummaryDTO;
import com.datagami.rentaxis.api.dto.UpdatePaymentStatusDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.ChequePayload;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.TransactionNature;
import com.datagami.rentaxis.api.dto.PaymentPreviewDTO;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentScheduleService {

    private final PaymentScheduleRepository paymentScheduleRepository;
    private final LeaseRepository leaseRepository;
    private final AccountRepository accountRepository;
    private final FinancialTransactionService financialTransactionService;
    private final AccountMappingService accountMappingService;
    private final RentCollectionSettingsRepository rentCollectionSettingsRepository;
    private final NotificationService notificationService;
    private final FineConfigResolver fineConfigResolver;
    private final PaymentPenaltyRepository paymentPenaltyRepository;
    private final LeaseEventRepository leaseEventRepository;
    private final ApplicationEventPublisher events;

    /** Injected Spring-managed ObjectMapper (honours date/time config, custom modules). */
    private final ObjectMapper objectMapper;

    @Transactional
    public List<PaymentSchedule> generateScheduleForLease(Lease lease) {
        List<PaymentSchedule> existing = paymentScheduleRepository.findByLeaseId(lease.getId());
        // Booking-deposit rows are created up-front during draft creation and must
        // not block normal installment generation on activation.
        boolean hasInstallments = existing.stream().anyMatch(p -> !p.isBookingDeposit());
        if (hasInstallments) {
            return existing;
        }

        long totalMonths = java.time.temporal.ChronoUnit.MONTHS.between(lease.getStartDate(), lease.getEndDate());
        if (totalMonths < 1) totalMonths = 1;

        BigDecimal monthlyRent;
        if (lease.getMonthlyRent() != null && lease.getMonthlyRent().compareTo(BigDecimal.ZERO) > 0) {
            monthlyRent = lease.getMonthlyRent();
        } else if (lease.getRentAmount() != null && lease.getRentAmount().compareTo(BigDecimal.ZERO) > 0) {
            monthlyRent = lease.getRentAmount().divide(BigDecimal.valueOf(totalMonths), 2, RoundingMode.HALF_UP);
        } else {
            monthlyRent = BigDecimal.ZERO;
        }

        // Honor lease.paymentTerms — N installments distributed across the lease
        // tenure, not one cheque per month. paymentTerms == months falls back to
        // the previous monthly cadence; missing/<=0 also defaults to monthly.
        int n;
        if (lease.getPaymentTerms() != null && lease.getPaymentTerms() > 0) {
            n = lease.getPaymentTerms();
        } else {
            n = (int) totalMonths;
        }
        if (n > totalMonths) n = (int) totalMonths;
        if (n < 1) n = 1;

        BigDecimal totalRent = monthlyRent.multiply(BigDecimal.valueOf(totalMonths));
        BigDecimal perInstallment = totalRent.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);

        // Honor the property-level RentCollectionSettings.dueDayOfMonth so every
        // cheque lands on the conventional payment day for the property (e.g.
        // "always due on the 1st"). When unset, fall back to the start date's
        // day-of-month. The dueDayOfMonth column is constrained to 1..28 in
        // RentCollectionSettings; we additionally clamp to the target month's
        // length to handle February + 31-day setups gracefully.
        Integer settingsDueDay = rentCollectionSettingsRepository
                .findByPropertyId(lease.getUnit().getProperty().getId())
                .map(s -> s.getDueDayOfMonth())
                .orElse(null);
        Integer dueDay = (settingsDueDay != null && settingsDueDay >= 1 && settingsDueDay <= 31)
                ? settingsDueDay
                : null;

        // Even spacing: due date i = startDate + floor(i * months / n) months.
        // For 12 months / 4 cheques → offsets [0, 3, 6, 9].
        // For 13 months / 4 cheques → offsets [0, 3, 6, 9] (last covers 4 months).
        List<PaymentSchedule> schedules = new ArrayList<>();
        BigDecimal accumulated = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            long monthOffset = (long) Math.floor((double) i * totalMonths / n);
            LocalDate dueDate = lease.getStartDate().plusMonths(monthOffset);
            if (dueDay != null) {
                int clamped = Math.min(dueDay, dueDate.lengthOfMonth());
                dueDate = dueDate.withDayOfMonth(clamped);
            }

            // Last installment carries the rounding remainder so the sum equals totalRent exactly.
            BigDecimal amount = (i == n - 1) ? totalRent.subtract(accumulated) : perInstallment;
            accumulated = accumulated.add(amount);

            PaymentSchedule ps = new PaymentSchedule();
            ps.setLease(lease);
            ps.setUnit(lease.getUnit());
            ps.setProperty(lease.getUnit().getProperty());
            ps.setInstallmentNumber(i + 1);
            ps.setDueDate(dueDate);
            ps.setAmount(amount);
            ps.setStatus(PaymentStatus.PENDING);

            String label = "RENT - " + ordinalOf(i + 1) + " INSTALLMENT";
            if (i == 0) {
                boolean hasBundledCharges = nz(lease.getAdminFee()).signum() > 0
                        || nz(lease.getDepositAmount()).signum() > 0
                        || nz(lease.getParkingRemoteFee()).signum() > 0;
                if (hasBundledCharges) {
                    label += "/ADMIN/SD/REMOTE";
                }
            }
            ps.setPurposeLabel(label);
            ps.setPaymentMethod(lease.getPaymentMethod() != null ? lease.getPaymentMethod().name() : "CHEQUE");
            schedules.add(ps);
        }

        return paymentScheduleRepository.saveAll(schedules);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    @Transactional(readOnly = true)
    public List<PaymentScheduleDTO> getPaymentsForLease(UUID leaseId) {
        return paymentScheduleRepository.findByLeaseId(leaseId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<PaymentScheduleDTO> getPaymentsForProperty(UUID propertyId, PaymentStatus status, String renterName, Pageable pageable) {
        String normalizedRenterName = (renterName == null || renterName.trim().isEmpty()) ? null : renterName.trim();
        if (normalizedRenterName == null) {
            Page<PaymentSchedule> payments = paymentScheduleRepository.findFiltered(propertyId, status, pageable);
            return payments.map(this::mapToDTO);
        }

        // Avoid DB text operations on renter names because some prod datasets store this value in binary-compatible columns.
        String searchToken = normalizedRenterName.toLowerCase(Locale.ROOT);
        List<PaymentScheduleDTO> filtered = paymentScheduleRepository.findForRenterSearch(propertyId, status).stream()
                .map(this::mapToDTO)
                .filter(dto -> dto.getRenterName() != null && dto.getRenterName().toLowerCase(Locale.ROOT).contains(searchToken))
                .collect(Collectors.toList());

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        List<PaymentScheduleDTO> pageContent = start >= filtered.size() ? List.of() : filtered.subList(start, end);
        return new PageImpl<>(pageContent, pageable, filtered.size());
    }

    @Transactional(readOnly = true)
    public PaymentSummaryDTO getSummary(UUID propertyId) {
        List<PaymentSchedule> payments;
        if (propertyId != null) {
            payments = paymentScheduleRepository.findByPropertyId(propertyId);
        } else {
            payments = paymentScheduleRepository.findAll();
        }

        PaymentSummaryDTO summary = new PaymentSummaryDTO();
        summary.setTotalPayments(payments.size());
        summary.setTotalAmount(BigDecimal.ZERO);
        summary.setPendingAmount(BigDecimal.ZERO);
        summary.setCollectedAmount(BigDecimal.ZERO);
        summary.setClearedAmount(BigDecimal.ZERO);
        summary.setOverdueAmount(BigDecimal.ZERO);

        int pendingCount = 0, collectedCount = 0, depositedCount = 0, clearedCount = 0, bouncedCount = 0, overdueCount = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal pendingAmount = BigDecimal.ZERO;
        BigDecimal collectedAmount = BigDecimal.ZERO;
        BigDecimal clearedAmount = BigDecimal.ZERO;
        BigDecimal overdueAmount = BigDecimal.ZERO;

        LocalDate today = LocalDate.now();

        for (PaymentSchedule ps : payments) {
            totalAmount = totalAmount.add(ps.getAmount());

            switch (ps.getStatus()) {
                case PENDING -> {
                    pendingCount++;
                    pendingAmount = pendingAmount.add(ps.getAmount());
                }
                case COLLECTED -> {
                    collectedCount++;
                    collectedAmount = collectedAmount.add(ps.getAmount());
                }
                case DEPOSITED -> depositedCount++;
                case CLEARED -> {
                    clearedCount++;
                    clearedAmount = clearedAmount.add(ps.getAmount());
                }
                case BOUNCED -> bouncedCount++;
                default -> { }
            }

            // Overdue: status is PENDING or COLLECTED and dueDate is before today
            if ((ps.getStatus() == PaymentStatus.PENDING || ps.getStatus() == PaymentStatus.COLLECTED)
                    && ps.getDueDate().isBefore(today)) {
                overdueCount++;
                overdueAmount = overdueAmount.add(ps.getAmount());
            }
        }

        summary.setTotalPayments(payments.size());
        summary.setPendingCount(pendingCount);
        summary.setCollectedCount(collectedCount);
        summary.setDepositedCount(depositedCount);
        summary.setClearedCount(clearedCount);
        summary.setBouncedCount(bouncedCount);
        summary.setOverdueCount(overdueCount);
        summary.setTotalAmount(totalAmount);
        summary.setPendingAmount(pendingAmount);
        summary.setCollectedAmount(collectedAmount);
        summary.setClearedAmount(clearedAmount);
        summary.setOverdueAmount(overdueAmount);

        return summary;
    }

    @Transactional
    public PaymentScheduleDTO collectPayment(UUID paymentId, UpdatePaymentStatusDTO dto) {
        PaymentSchedule payment = paymentScheduleRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment not found"));

        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new BusinessRuleViolationException("Can only collect payments in PENDING status");
        }

        payment.setStatus(PaymentStatus.COLLECTED);
        payment.setChequeNumber(dto.getChequeNumber());
        payment.setBankName(dto.getBankName());
        payment.setPayerName(dto.getPayerName());
        payment.setChequeDate(dto.getChequeDate());
        payment.setChequeImageUrl(dto.getChequeImageUrl());
        payment.setChequeImageBlobPath(dto.getChequeImageBlobPath());
        payment.setChequeImageUploadedAt(resolveChequeImageUploadedAt(dto));
        payment.setStatusChangedAt(Instant.now());
        PaymentSchedule saved = paymentScheduleRepository.save(payment);

        // Structured email event: CHEQUE_RECEIVED
        events.publishEvent(new EmailEvent(this,
                EmailEventType.CHEQUE_RECEIVED,
                saved.getTenantId(),
                new ChequePayload(
                        saved.getId(),
                        saved.getLease().getId(),
                        saved.getLease().getRenter().getUserId(),
                        null,  // propertyManagerUserId — not stored on Lease; RecipientResolver falls back to tenant admins
                        saved.getInstallmentNumber(),
                        saved.getChequeNumber(),
                        saved.getBankName(),
                        saved.getAmount() != null ? saved.getAmount().toPlainString() + " AED" : null,
                        saved.getDueDate() != null ? saved.getDueDate().toString() : null,
                        null,  // depositDateIso — not yet deposited
                        null   // failureReason — not applicable
                ),
                "CHEQUE_RECEIVED:" + saved.getId()));

        // Notify renter in-app: cheque collected (CHEQUE_RECEIVED EmailEvent covers email)
        try {
            UUID renterUserId = payment.getLease().getRenter().getUserId();
            if (renterUserId != null) {
                notificationService.notifyInApp(TenantContextHolder.getTenantId(), renterUserId,
                        "PAYMENT_COLLECTED", "Cheque Collected",
                        "Installment #" + payment.getInstallmentNumber() + " cheque has been collected and is being processed.",
                        "PAYMENT", payment.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to send cheque collected notification for payment {}: {}", payment.getId(), e.getMessage());
        }

        return mapToDTO(saved);
    }

    @Transactional
    public List<PaymentScheduleDTO> bulkAttachCheques(UUID leaseId, List<BulkAttachChequeItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BulkAttachValidationException("items must not be empty");
        }

        // Spec §7.2 step 1: lease must exist and belong to the caller's tenant.
        // Uses findByIdScopedToTenant (JPQL) so the Hibernate tenant filter
        // applies — Spring Data's default findById bypasses @Filter on
        // load-by-key in Hibernate 7, which would otherwise allow a caller in
        // tenant A to operate on a lease id from tenant B. Both not-found and
        // cross-tenant collapse to 404.
        leaseRepository.findByIdScopedToTenant(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));

        // Detect duplicate scheduleId / chequeNumber within the request.
        List<BulkAttachErrorRow> errors = new ArrayList<>();
        Set<UUID> seenScheduleIds = new HashSet<>();
        Set<String> seenChequeNumbers = new HashSet<>();
        for (BulkAttachChequeItem it : items) {
            if (!seenScheduleIds.add(it.getScheduleId())) {
                errors.add(new BulkAttachErrorRow(it.getScheduleId(), "duplicate_schedule_id_in_request"));
            }
            if (!seenChequeNumbers.add(it.getChequeNumber())) {
                errors.add(new BulkAttachErrorRow(it.getScheduleId(), "duplicate_cheque_number_in_request"));
            }
        }
        if (!errors.isEmpty()) {
            throw new BulkAttachValidationException(errors, false);
        }

        // Load every targeted schedule in one shot.
        List<UUID> scheduleIds = items.stream().map(BulkAttachChequeItem::getScheduleId).toList();
        List<PaymentSchedule> schedules = paymentScheduleRepository.findAllById(scheduleIds);
        Map<UUID, PaymentSchedule> byId = schedules.stream()
                .collect(Collectors.toMap(PaymentSchedule::getId, s -> s));

        // Validate each row.
        List<BulkAttachErrorRow> notPending = new ArrayList<>();
        List<BulkAttachErrorRow> badRows = new ArrayList<>();
        for (BulkAttachChequeItem it : items) {
            PaymentSchedule ps = byId.get(it.getScheduleId());
            if (ps == null) {
                badRows.add(new BulkAttachErrorRow(it.getScheduleId(), "schedule_not_found"));
                continue;
            }
            if (!ps.getLease().getId().equals(leaseId)) {
                badRows.add(new BulkAttachErrorRow(it.getScheduleId(), "schedule_not_in_lease"));
                continue;
            }
            if (ps.getStatus() != PaymentStatus.PENDING) {
                notPending.add(new BulkAttachErrorRow(it.getScheduleId(), "schedule_not_pending"));
            }
        }
        if (!badRows.isEmpty()) {
            throw new BulkAttachValidationException(badRows, false);
        }
        if (!notPending.isEmpty()) {
            throw new BulkAttachValidationException(notPending, true);
        }

        // Cheque number conflict against other schedules on this lease.
        List<PaymentSchedule> leaseSchedules = paymentScheduleRepository.findByLeaseId(leaseId);
        Set<UUID> targetIds = new HashSet<>(scheduleIds);
        Set<String> existingChequeNumbers = leaseSchedules.stream()
                .filter(ps -> !targetIds.contains(ps.getId()))
                .map(PaymentSchedule::getChequeNumber)
                .filter(n -> n != null && !n.isBlank())
                .collect(Collectors.toSet());
        List<BulkAttachErrorRow> chequeConflicts = items.stream()
                .filter(it -> existingChequeNumbers.contains(it.getChequeNumber()))
                .map(it -> new BulkAttachErrorRow(it.getScheduleId(), "cheque_number_already_used_on_lease"))
                .toList();
        if (!chequeConflicts.isEmpty()) {
            throw new BulkAttachValidationException(chequeConflicts, false);
        }

        // Apply.
        Instant now = Instant.now();
        List<PaymentSchedule> updated = new ArrayList<>(items.size());
        for (BulkAttachChequeItem it : items) {
            PaymentSchedule ps = byId.get(it.getScheduleId());
            ps.setStatus(PaymentStatus.COLLECTED);
            ps.setChequeNumber(it.getChequeNumber());
            ps.setBankName(it.getBankName());
            ps.setPayerName(it.getPayerName());
            ps.setChequeDate(it.getChequeDate());
            ps.setChequeImageUrl(it.getImageUrl());
            ps.setChequeImageBlobPath(it.getImageBlobPath());
            ps.setChequeImageUploadedAt(it.getImageUploadedAt());
            ps.setStatusChangedAt(now);
            updated.add(paymentScheduleRepository.save(ps));
        }

        // Fire one CHEQUE_RECEIVED event per row, mirroring single-cheque collectPayment.
        for (PaymentSchedule saved : updated) {
            events.publishEvent(new EmailEvent(this,
                    EmailEventType.CHEQUE_RECEIVED,
                    saved.getTenantId(),
                    new ChequePayload(
                            saved.getId(),
                            saved.getLease().getId(),
                            saved.getLease().getRenter().getUserId(),
                            null,
                            saved.getInstallmentNumber(),
                            saved.getChequeNumber(),
                            saved.getBankName(),
                            saved.getAmount() != null ? saved.getAmount().toPlainString() + " AED" : null,
                            saved.getDueDate() != null ? saved.getDueDate().toString() : null,
                            null,
                            null
                    ),
                    "CHEQUE_RECEIVED:" + saved.getId()));
        }

        // In-app notification per row, mirroring single-cheque collectPayment.
        for (PaymentSchedule saved : updated) {
            try {
                UUID renterUserId = saved.getLease().getRenter().getUserId();
                if (renterUserId != null) {
                    notificationService.notifyInApp(TenantContextHolder.getTenantId(), renterUserId,
                            "PAYMENT_COLLECTED", "Cheque Collected",
                            "Installment #" + saved.getInstallmentNumber() + " cheque has been collected and is being processed.",
                            "PAYMENT", saved.getId());
                }
            } catch (Exception e) {
                log.warn("Failed to send cheque collected notification for payment {}: {}", saved.getId(), e.getMessage());
            }
        }

        return updated.stream().map(this::mapToDTO).toList();
    }

    @Transactional
    public PaymentScheduleDTO depositPayment(UUID paymentId, UpdatePaymentStatusDTO dto) {
        PaymentSchedule payment = paymentScheduleRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment not found"));

        if (payment.getStatus() != PaymentStatus.COLLECTED) {
            throw new BusinessRuleViolationException("Can only deposit payments in COLLECTED status");
        }

        payment.setStatus(PaymentStatus.DEPOSITED);
        if (dto.getNotes() != null) {
            payment.setNotes(dto.getNotes());
        }
        payment.setStatusChangedAt(Instant.now());
        PaymentSchedule deposited = paymentScheduleRepository.save(payment);

        // Structured email event: CHEQUE_DEPOSITED
        events.publishEvent(new EmailEvent(this,
                EmailEventType.CHEQUE_DEPOSITED,
                deposited.getTenantId(),
                new ChequePayload(
                        deposited.getId(),
                        deposited.getLease().getId(),
                        deposited.getLease().getRenter().getUserId(),
                        null,  // propertyManagerUserId — not stored on Lease; RecipientResolver falls back to tenant admins
                        deposited.getInstallmentNumber(),
                        deposited.getChequeNumber(),
                        deposited.getBankName(),
                        deposited.getAmount() != null ? deposited.getAmount().toPlainString() + " AED" : null,
                        deposited.getDueDate() != null ? deposited.getDueDate().toString() : null,
                        java.time.LocalDate.now().toString(),
                        null   // failureReason — not applicable
                ),
                "CHEQUE_DEPOSITED:" + deposited.getId()));

        return mapToDTO(deposited);
    }

    @Transactional
    public PaymentScheduleDTO clearPayment(UUID paymentId, UpdatePaymentStatusDTO dto) {
        PaymentSchedule payment = paymentScheduleRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment not found"));

        if (payment.getStatus() != PaymentStatus.DEPOSITED) {
            throw new BusinessRuleViolationException("Can only clear payments in DEPOSITED status");
        }

        payment.setStatus(PaymentStatus.CLEARED);
        payment.setStatusChangedAt(Instant.now());
        paymentScheduleRepository.save(payment);

        // Auto-create financial transactions
        // Try to resolve from account mappings first, fall back to hardcoded defaults
        AccountMapping mapping = accountMappingService.resolveMapping(TransactionNature.RENT_PAYMENT_CLEARED);

        Account bankAccount;
        Account rentalIncomeAccount;

        if (mapping != null) {
            bankAccount = mapping.getDebitAccount();
            rentalIncomeAccount = mapping.getCreditAccount();
        } else {
            // Fallback to hardcoded defaults
            UUID tenantId = TenantContextHolder.getTenantId();
            bankAccount = accountRepository.findByCodeAndTenantId("A-01-01", tenantId)
                    .orElseThrow(() -> new RuntimeException("Bank/Cash account (A-01-01) not found. Please configure account mappings."));
            rentalIncomeAccount = accountRepository.findByCodeAndTenantId("C-01-01", tenantId)
                    .orElseThrow(() -> new RuntimeException("Rental Income account (C-01-01) not found. Please configure account mappings."));
        }

        FinancialTransaction debitTxn = new FinancialTransaction();
        debitTxn.setDate(LocalDate.now());
        debitTxn.setDescription("Cheque cleared - Lease installment #" + payment.getInstallmentNumber());
        debitTxn.setAccount(bankAccount);
        debitTxn.setDebit(payment.getAmount());
        debitTxn.setCredit(BigDecimal.ZERO);
        debitTxn.setProperty(payment.getProperty());
        debitTxn.setUnit(payment.getUnit());
        financialTransactionService.createTransaction(debitTxn);

        FinancialTransaction creditTxn = new FinancialTransaction();
        creditTxn.setDate(LocalDate.now());
        creditTxn.setDescription("Rental income - Lease installment #" + payment.getInstallmentNumber());
        creditTxn.setAccount(rentalIncomeAccount);
        creditTxn.setDebit(BigDecimal.ZERO);
        creditTxn.setCredit(payment.getAmount());
        creditTxn.setProperty(payment.getProperty());
        creditTxn.setUnit(payment.getUnit());

        // Stamp VAT fields based on the lease's rent VAT toggle (UAE 5% standard rate).
        // The cheque amount is gross (face value); we extract VAT as gross * 5/105.
        // Only the credit (rental-income) leg is stamped — VAT is tracked on income lines,
        // not on the bank/cash debit leg.
        // Note: bundled first cheques (admin fee / SD / parking remote rolled into installment 1)
        // are treated as rent here. A future enhancement could split them per-component using
        // lease.isAdminFeeVatApplicable / isSecurityDepositVatApplicable / isParkingRemoteVatApplicable.
        boolean rentVatApplicable = payment.getLease().isRentVatApplicable();
        if (rentVatApplicable) {
            BigDecimal gross = payment.getAmount();
            BigDecimal vatAmount = gross
                    .multiply(new BigDecimal("5"))
                    .divide(new BigDecimal("105"), 2, RoundingMode.HALF_UP);
            BigDecimal netAmount = gross.subtract(vatAmount);
            creditTxn.setVatApplicable(true);
            creditTxn.setVatRate(new BigDecimal("5.00"));
            creditTxn.setVatAmount(vatAmount);
            creditTxn.setGrossAmount(gross);
            creditTxn.setNetAmount(netAmount);
        }
        // else: leave defaults (vatApplicable=false, vatRate=0, vatAmount=0,
        // grossAmount=0, netAmount=0) matching pre-M9 behavior for residential leases.

        financialTransactionService.createTransaction(creditTxn);

        // Notify renter that payment has been cleared
        try {
            UUID renterUserId = payment.getLease().getRenter().getUserId();
            if (renterUserId != null) {
                notificationService.notify(TenantContextHolder.getTenantId(),
                        renterUserId,
                        "PAYMENT_CLEARED", "Payment Cleared",
                        "Installment #" + payment.getInstallmentNumber() + " has been cleared. Receipt available.",
                        "PAYMENT", payment.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to send payment cleared notification for payment {}: {}", payment.getId(), e.getMessage());
        }

        return mapToDTO(payment);
    }

    /**
     * Legacy entry point kept for backward compatibility with the existing
     * {@code PUT /api/v1/payments/{id}/bounce} endpoint and any older clients
     * that haven't been migrated to {@link #markFailed} yet. It now delegates
     * to {@link #markFailed} with {@link ChequeFailureReason#BOUNCE} so callers
     * automatically pick up the new fine + penalty + financial-transaction
     * side effects without any client change.
     */
    @Transactional
    public PaymentScheduleDTO bouncePayment(UUID paymentId, UpdatePaymentStatusDTO dto) {
        return markFailed(paymentId, ChequeFailureReason.BOUNCE, dto.getNotes()).schedule();
    }

    /**
     * Mark a deposited payment as failed (bounced / signature mismatched /
     * account closed). Records the fine snapshot, posts the cheque-bounce
     * journal entry, fires renter notifications, and writes a lease event for
     * audit. Only payments in {@link PaymentStatus#DEPOSITED} are accepted —
     * cheques that never made it to the bank can't bounce.
     */
    @Transactional
    public MarkFailedResult markFailed(UUID paymentId, ChequeFailureReason reason, String notes) {
        PaymentSchedule s = paymentScheduleRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment not found"));

        if (s.getStatus() != PaymentStatus.DEPOSITED) {
            throw new BusinessRuleViolationException(
                    "Can only mark payments in DEPOSITED status as failed (current: " + s.getStatus() + ")");
        }

        s.setStatus(PaymentStatus.BOUNCED);
        s.setFailureReason(reason);
        s.setStatusChangedAt(Instant.now());
        if (notes != null && !notes.isBlank()) {
            s.setNotes(notes);
        }
        PaymentSchedule saved = paymentScheduleRepository.save(s);

        UUID tenantId = TenantContextHolder.getTenantId();
        FineConfig cfg = fineConfigResolver.resolve(saved.getProperty().getId(), tenantId);
        BigDecimal fineAmount = cfg.amountFor(reason);

        PaymentPenalty penalty = new PaymentPenalty();
        penalty.setPaymentScheduleId(saved.getId());
        penalty.setLeaseId(saved.getLease().getId());
        penalty.setPenaltyType("CHEQUE_FAILURE");
        penalty.setFailureReason(reason);   // snapshot — eliminates per-row schedule join in PenaltyController
        penalty.setPenaltyAmount(fineAmount);
        penalty.setDaysOverdue(0);
        penalty.setFineGraceDays(cfg.graceDays());
        penalty.setFinePerDayRate(cfg.perDayRate());
        penalty.setLastCalculatedAt(LocalDateTime.now());
        PaymentPenalty savedPenalty = paymentPenaltyRepository.save(penalty);

        // Post CHEQUE_BOUNCED financial transaction (existing nature, existing
        // AccountMapping). NOT wrapped in try/catch — recordChequeBounce
        // participates in this same outer JPA transaction, so swallowing its
        // exception poisons the connection and the eventual commit fails with a
        // confusing TransactionSystemException. Letting it propagate triggers
        // proper rollback of the status change + penalty + audit so the books
        // stay consistent with the schedule state.
        financialTransactionService.recordChequeBounce(saved);

        // Renter notifications — both PAYMENT_BOUNCED (existing template) and
        // PENALTY_INCURRED (extracted to NotificationService.sendPenaltyIncurred
        // in M7). Wrapped so a downed mailer never blocks the status transition
        // (notifications are best-effort, not part of the audit-critical path).
        try {
            UUID renterUserId = saved.getLease().getRenter().getUserId();
            if (renterUserId != null) {
                notificationService.notify(tenantId, renterUserId,
                        "PAYMENT_BOUNCED", "Cheque Failed",
                        "Installment #" + saved.getInstallmentNumber() + " cheque of " + saved.getAmount()
                                + " was marked " + reason + ". A fine of " + fineAmount
                                + " AED has been added. Please arrange a replacement and clear the fine.",
                        "PAYMENT", saved.getId());
                notificationService.sendPenaltyIncurred(saved, reason, fineAmount, savedPenalty.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to send mark-failed notifications for payment {}: {}", saved.getId(), e.getMessage());
        }

        // Lease event audit. The existing LeaseEvent entity has no eventType
        // column, so the marker is embedded into `notes` as JSON (serialized
        // via Jackson so any future field containing quotes is escaped
        // correctly). The lease's current status is preserved for both
        // previousState and newState because mark-failed is a payment-level
        // transition, not a lease one — but newState is NOT NULL on the
        // entity, so we fall back to ACTIVE when the lease has no status set
        // (e.g. drafts in flight) to avoid a DataIntegrityViolationException
        // at flush time.
        LeaseStatus currentStatus = saved.getLease().getStatus();
        LeaseStatus auditState = currentStatus != null ? currentStatus : LeaseStatus.ACTIVE;
        LeaseEvent ev = new LeaseEvent();
        ev.setLease(saved.getLease());
        ev.setPreviousState(auditState);
        ev.setNewState(auditState);
        ev.setNotes(buildLeaseEventNotes(reason, saved.getId(), savedPenalty.getId(), fineAmount));
        ev.setCreatedAt(Instant.now());
        leaseEventRepository.save(ev);

        return new MarkFailedResult(mapToDTO(saved), savedPenalty);
    }

    private String buildLeaseEventNotes(
            ChequeFailureReason reason, UUID paymentScheduleId, UUID penaltyId, BigDecimal fineAmount) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("eventType", "PAYMENT_FAILED_" + reason.name());
        payload.put("paymentScheduleId", paymentScheduleId.toString());
        payload.put("penaltyId", penaltyId.toString());
        payload.put("fineAmount", fineAmount.toPlainString());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Should never happen for a Map<String,String>; fall back to a
            // best-effort marker so the audit row still records the event type.
            log.warn("Failed to serialize lease event notes JSON: {}", e.getMessage());
            return "{\"eventType\":\"PAYMENT_FAILED_" + reason.name() + "\"}";
        }
    }

    @Transactional
    public PaymentScheduleDTO replacePayment(UUID paymentId, UpdatePaymentStatusDTO dto) {
        PaymentSchedule oldPayment = paymentScheduleRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment not found"));

        if (oldPayment.getStatus() != PaymentStatus.BOUNCED) {
            throw new BusinessRuleViolationException("Can only replace payments in BOUNCED status");
        }

        // Create new replacement payment
        PaymentSchedule newPayment = new PaymentSchedule();
        newPayment.setLease(oldPayment.getLease());
        newPayment.setUnit(oldPayment.getUnit());
        newPayment.setProperty(oldPayment.getProperty());
        newPayment.setAmount(oldPayment.getAmount());
        newPayment.setInstallmentNumber(oldPayment.getInstallmentNumber());
        newPayment.setStatus(PaymentStatus.PENDING);
        newPayment.setDueDate(LocalDate.now().plusDays(30));
        newPayment.setChequeNumber(dto.getChequeNumber());
        newPayment.setBankName(dto.getBankName());
        newPayment.setPayerName(dto.getPayerName());
        newPayment.setChequeDate(dto.getChequeDate());
        newPayment.setChequeImageUrl(dto.getChequeImageUrl());
        newPayment.setChequeImageBlobPath(dto.getChequeImageBlobPath());
        newPayment.setChequeImageUploadedAt(resolveChequeImageUploadedAt(dto));

        PaymentSchedule savedNewPayment = paymentScheduleRepository.save(newPayment);

        // Update old payment
        oldPayment.setReplacedBy(savedNewPayment);
        oldPayment.setStatus(PaymentStatus.REPLACED);
        oldPayment.setStatusChangedAt(Instant.now());
        paymentScheduleRepository.save(oldPayment);

        return mapToDTO(savedNewPayment);
    }


    @Transactional(readOnly = true)
    public List<LeasePaymentStatsDTO> getPaymentStatsByLeaseIds(List<UUID> leaseIds) {
        List<LeasePaymentStatsDTO> result = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (UUID leaseId : leaseIds) {
            List<PaymentSchedule> payments = paymentScheduleRepository.findByLeaseId(leaseId);

            LeasePaymentStatsDTO stats = new LeasePaymentStatsDTO();
            stats.setLeaseId(leaseId);
            stats.setTotalPayments(payments.size());

            int clearedCount = 0;
            int pendingCount = 0;
            int overdueCount = 0;
            BigDecimal totalAmount = BigDecimal.ZERO;
            BigDecimal clearedAmount = BigDecimal.ZERO;
            BigDecimal overdueAmount = BigDecimal.ZERO;

            for (PaymentSchedule ps : payments) {
                totalAmount = totalAmount.add(ps.getAmount());

                if (ps.getStatus() == PaymentStatus.CLEARED) {
                    clearedCount++;
                    clearedAmount = clearedAmount.add(ps.getAmount());
                } else if (ps.getStatus() == PaymentStatus.PENDING) {
                    pendingCount++;
                }

                if ((ps.getStatus() == PaymentStatus.PENDING || ps.getStatus() == PaymentStatus.COLLECTED)
                        && ps.getDueDate().isBefore(today)) {
                    overdueCount++;
                    overdueAmount = overdueAmount.add(ps.getAmount());
                }
            }

            stats.setClearedPayments(clearedCount);
            stats.setPendingPayments(pendingCount);
            stats.setOverduePayments(overdueCount);
            stats.setTotalAmount(totalAmount);
            stats.setClearedAmount(clearedAmount);
            stats.setOverdueAmount(overdueAmount);

            result.add(stats);
        }

        return result;
    }

    @Transactional(readOnly = true)
    public AgingReportDTO getAgingReport(UUID propertyId) {
        List<PaymentSchedule> schedules;
        if (propertyId != null) {
            schedules = paymentScheduleRepository.findByPropertyId(propertyId);
        } else {
            schedules = paymentScheduleRepository.findAll();
        }

        LocalDate today = LocalDate.now();
        List<PaymentSchedule> overdue = schedules.stream()
                .filter(ps -> ps.getStatus() == PaymentStatus.PENDING
                        && ps.getDueDate().isBefore(today))
                .toList();

        // Define buckets
        String[] labels = {"Current", "1-30 Days", "31-60 Days", "61-90 Days", "90+ Days"};
        List<AgingReportDTO.AgingBucket> buckets = new ArrayList<>();
        for (String label : labels) {
            AgingReportDTO.AgingBucket bucket = new AgingReportDTO.AgingBucket();
            bucket.setLabel(label);
            bucket.setDetails(new ArrayList<>());
            buckets.add(bucket);
        }

        BigDecimal totalOutstanding = BigDecimal.ZERO;

        for (PaymentSchedule ps : overdue) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(ps.getDueDate(), today);
            int bucketIdx;
            if (days <= 0) bucketIdx = 0;
            else if (days <= 30) bucketIdx = 1;
            else if (days <= 60) bucketIdx = 2;
            else if (days <= 90) bucketIdx = 3;
            else bucketIdx = 4;

            AgingReportDTO.AgingDetail detail = new AgingReportDTO.AgingDetail();
            if (ps.getLease() != null && ps.getLease().getRenter() != null) {
                detail.setRenterName(ps.getLease().getRenter().getNameEn());
            }
            if (ps.getLease() != null && ps.getLease().getUnit() != null) {
                detail.setUnitNumber(ps.getLease().getUnit().getUnitNumber());
                if (ps.getLease().getUnit().getProperty() != null) {
                    detail.setPropertyName(ps.getLease().getUnit().getProperty().getNameEn());
                }
            }
            detail.setAmount(ps.getAmount());
            detail.setDaysOverdue((int) days);
            detail.setDueDate(ps.getDueDate().toString());

            buckets.get(bucketIdx).getDetails().add(detail);
            buckets.get(bucketIdx).setAmount(buckets.get(bucketIdx).getAmount().add(ps.getAmount()));
            buckets.get(bucketIdx).setCount(buckets.get(bucketIdx).getCount() + 1);
            totalOutstanding = totalOutstanding.add(ps.getAmount());
        }

        AgingReportDTO dto = new AgingReportDTO();
        dto.setBuckets(buckets);
        dto.setTotalOutstanding(totalOutstanding);
        return dto;
    }

    @Transactional(readOnly = true)
    /**
     * Overload that honors paymentTerms. When paymentTerms is null / <= 0 it
     * falls through to the original monthly-with-pro-rata cadence; when it's
     * set, the preview mirrors what {@link #generateScheduleForLease} will
     * produce — N installments evenly distributed across the lease tenure,
     * snapped to the property's RentCollectionSettings.dueDayOfMonth.
     */
    public PaymentPreviewDTO previewSchedule(UUID propertyId, LocalDate startDate, LocalDate endDate, BigDecimal monthlyRent, Integer paymentTerms) {
        if (paymentTerms == null || paymentTerms <= 0) {
            return previewSchedule(propertyId, startDate, endDate, monthlyRent);
        }
        if (!endDate.isAfter(startDate)) throw new RuntimeException("End date must be after start date");
        if (monthlyRent == null || monthlyRent.compareTo(BigDecimal.ZERO) <= 0) throw new RuntimeException("Monthly rent must be greater than zero");

        long totalMonths = java.time.temporal.ChronoUnit.MONTHS.between(startDate, endDate);
        if (totalMonths < 1) totalMonths = 1;

        int n = paymentTerms;
        if (n > totalMonths) n = (int) totalMonths;

        // Apply property due day if configured (matches generateScheduleForLease).
        Integer settingsDueDay = rentCollectionSettingsRepository.findByPropertyId(propertyId)
                .map(s -> s.getDueDayOfMonth()).orElse(null);
        Integer dueDay = (settingsDueDay != null && settingsDueDay >= 1 && settingsDueDay <= 31) ? settingsDueDay : null;

        BigDecimal totalRent = monthlyRent.multiply(BigDecimal.valueOf(totalMonths));
        BigDecimal perInstallment = totalRent.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);

        List<PaymentPreviewDTO.PaymentPreviewLine> lines = new ArrayList<>();
        BigDecimal accumulated = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            long monthOffset = (long) Math.floor((double) i * totalMonths / n);
            LocalDate dueDate = startDate.plusMonths(monthOffset);
            if (dueDay != null) {
                dueDate = dueDate.withDayOfMonth(Math.min(dueDay, dueDate.lengthOfMonth()));
            }
            // Period covers from this installment's due date until the next
            // installment's due date (or lease end on the last one).
            LocalDate periodEnd;
            if (i == n - 1) {
                periodEnd = endDate;
            } else {
                long nextOffset = (long) Math.floor((double) (i + 1) * totalMonths / n);
                LocalDate nextDue = startDate.plusMonths(nextOffset);
                if (dueDay != null) nextDue = nextDue.withDayOfMonth(Math.min(dueDay, nextDue.lengthOfMonth()));
                periodEnd = nextDue.minusDays(1);
            }
            BigDecimal amount = (i == n - 1) ? totalRent.subtract(accumulated) : perInstallment;
            accumulated = accumulated.add(amount);

            PaymentPreviewDTO.PaymentPreviewLine line = new PaymentPreviewDTO.PaymentPreviewLine();
            line.setInstallmentNumber(i + 1);
            line.setDueDate(dueDate);
            line.setPeriodStart(dueDate);
            line.setPeriodEnd(periodEnd);
            line.setAmount(amount);
            line.setProRata(false);
            lines.add(line);
        }

        PaymentPreviewDTO dto = new PaymentPreviewDTO();
        dto.setLines(lines);
        dto.setTotalAmount(totalRent);
        dto.setTotalPayments(n);
        dto.setDueDayOfMonth(dueDay != null ? dueDay : startDate.getDayOfMonth());
        // Default payment method comes from the property settings — same as the monthly preview.
        var settings = rentCollectionSettingsRepository.findByPropertyId(propertyId).orElse(null);
        boolean onlineEnabled = settings != null && Boolean.TRUE.equals(settings.getOnlinePaymentEnabled());
        // Cheque-first default: ONLINE only when the property explicitly enables it
        // via RentCollectionSettings.onlinePaymentEnabled=true.
        dto.setDefaultPaymentMethod(onlineEnabled ? "ONLINE" : "CHEQUE");
        return dto;
    }

    public PaymentPreviewDTO previewSchedule(UUID propertyId, LocalDate startDate, LocalDate endDate, BigDecimal monthlyRent) {
        // Look up property settings
        var settings = rentCollectionSettingsRepository.findByPropertyId(propertyId).orElse(null);
        int dueDay = (settings != null && settings.getDueDayOfMonth() != null && settings.getDueDayOfMonth() >= 1 && settings.getDueDayOfMonth() <= 28)
                ? settings.getDueDayOfMonth() : 1;
        boolean onlineEnabled = settings != null && Boolean.TRUE.equals(settings.getOnlinePaymentEnabled());

        if (!endDate.isAfter(startDate)) throw new RuntimeException("End date must be after start date");
        if (monthlyRent == null || monthlyRent.compareTo(BigDecimal.ZERO) <= 0) throw new RuntimeException("Monthly rent must be greater than zero");

        boolean startsOnDueDay = startDate.getDayOfMonth() == dueDay;
        List<PaymentPreviewDTO.PaymentPreviewLine> lines = new ArrayList<>();
        int installment = 1;

        if (startsOnDueDay) {
            // Simple case: lease starts on due day — equal monthly payments
            long months = java.time.temporal.ChronoUnit.MONTHS.between(startDate, endDate);
            LocalDate afterMonths = startDate.plusMonths(months);
            if (afterMonths.isBefore(endDate)) months++;
            if (months < 1) months = 1;

            for (int i = 0; i < (int) months; i++) {
                LocalDate due = startDate.plusMonths(i);
                LocalDate pEnd = (i == (int) months - 1) ? endDate : startDate.plusMonths(i + 1).minusDays(1);

                PaymentPreviewDTO.PaymentPreviewLine line = new PaymentPreviewDTO.PaymentPreviewLine();
                line.setInstallmentNumber(installment++);
                line.setDueDate(due);
                line.setPeriodStart(due);
                line.setPeriodEnd(pEnd);
                line.setAmount(monthlyRent);
                line.setProRata(false);
                lines.add(line);
            }
        } else {
            // Lease starts mid-month: pro-rata first, then full months on due day, pro-rata last
            // First due date on the configured day
            LocalDate firstDueDate;
            if (startDate.getDayOfMonth() < dueDay) {
                firstDueDate = startDate.withDayOfMonth(dueDay);
            } else {
                firstDueDate = startDate.plusMonths(1).withDayOfMonth(dueDay);
            }

            // Pro-rata first payment (lease start → day before first due date)
            long proRataDays = java.time.temporal.ChronoUnit.DAYS.between(startDate, firstDueDate);
            if (proRataDays > 0 && firstDueDate.isBefore(endDate)) {
                long totalDaysInMonth = java.time.temporal.ChronoUnit.DAYS.between(
                        firstDueDate.minusMonths(1), firstDueDate);
                BigDecimal proRataAmount = monthlyRent.multiply(BigDecimal.valueOf(proRataDays))
                        .divide(BigDecimal.valueOf(totalDaysInMonth), 2, RoundingMode.HALF_UP);

                PaymentPreviewDTO.PaymentPreviewLine first = new PaymentPreviewDTO.PaymentPreviewLine();
                first.setInstallmentNumber(installment++);
                first.setDueDate(startDate);
                first.setPeriodStart(startDate);
                first.setPeriodEnd(firstDueDate.minusDays(1));
                first.setAmount(proRataAmount);
                first.setProRata(true);
                lines.add(first);
            }

            // Full monthly payments on due day
            LocalDate currentDue = firstDueDate;
            while (currentDue.isBefore(endDate)) {
                LocalDate nextDue = currentDue.plusMonths(1);
                try { nextDue = nextDue.withDayOfMonth(dueDay); }
                catch (Exception e) { nextDue = nextDue.withDayOfMonth(nextDue.lengthOfMonth()); }

                boolean isLast = !nextDue.isBefore(endDate);
                LocalDate pEnd = isLast ? endDate : nextDue.minusDays(1);

                if (isLast) {
                    // Pro-rata last payment
                    long totalDaysInMonth = java.time.temporal.ChronoUnit.DAYS.between(currentDue, currentDue.plusMonths(1));
                    long actualDays = java.time.temporal.ChronoUnit.DAYS.between(currentDue, endDate);
                    // If it's close to a full month, just use full amount
                    BigDecimal amount = (actualDays >= totalDaysInMonth - 2)
                            ? monthlyRent
                            : monthlyRent.multiply(BigDecimal.valueOf(actualDays))
                                .divide(BigDecimal.valueOf(totalDaysInMonth), 2, RoundingMode.HALF_UP);

                    PaymentPreviewDTO.PaymentPreviewLine line = new PaymentPreviewDTO.PaymentPreviewLine();
                    line.setInstallmentNumber(installment++);
                    line.setDueDate(currentDue);
                    line.setPeriodStart(currentDue);
                    line.setPeriodEnd(pEnd);
                    line.setAmount(amount);
                    line.setProRata(actualDays < totalDaysInMonth - 2);
                    lines.add(line);
                } else {
                    PaymentPreviewDTO.PaymentPreviewLine line = new PaymentPreviewDTO.PaymentPreviewLine();
                    line.setInstallmentNumber(installment++);
                    line.setDueDate(currentDue);
                    line.setPeriodStart(currentDue);
                    line.setPeriodEnd(pEnd);
                    line.setAmount(monthlyRent);
                    line.setProRata(false);
                    lines.add(line);
                }
                if (isLast) break;
                currentDue = nextDue;
            }
        }

        BigDecimal totalRent = lines.stream()
                .map(PaymentPreviewDTO.PaymentPreviewLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        PaymentPreviewDTO dto = new PaymentPreviewDTO();
        dto.setLines(lines);
        dto.setTotalAmount(totalRent);
        dto.setTotalPayments(lines.size());
        dto.setDueDayOfMonth(dueDay);
        // Cheque-first default: ONLINE only when the property explicitly enables it
        // via RentCollectionSettings.onlinePaymentEnabled=true.
        dto.setDefaultPaymentMethod(onlineEnabled ? "ONLINE" : "CHEQUE");
        return dto;
    }

    private static String ordinalOf(int n) {
        if (n % 100 >= 11 && n % 100 <= 13) return n + "TH";
        return switch (n % 10) {
            case 1 -> n + "ST";
            case 2 -> n + "ND";
            case 3 -> n + "RD";
            default -> n + "TH";
        };
    }

    /** Public alias for {@link #mapToDTO} so other services / controllers can map without re-implementing. */
    public PaymentScheduleDTO toDTO(PaymentSchedule ps) {
        return mapToDTO(ps);
    }

    private OffsetDateTime resolveChequeImageUploadedAt(UpdatePaymentStatusDTO dto) {
        if (dto.getChequeImageBlobPath() == null || dto.getChequeImageBlobPath().isBlank()) {
            return null;
        }
        return dto.getChequeImageUploadedAt() != null ? dto.getChequeImageUploadedAt() : OffsetDateTime.now();
    }

    private PaymentScheduleDTO mapToDTO(PaymentSchedule ps) {
        PaymentScheduleDTO dto = new PaymentScheduleDTO();
        dto.setId(ps.getId());
        dto.setLeaseId(ps.getLease().getId());
        dto.setUnitId(ps.getUnit().getId());
        dto.setPropertyId(ps.getProperty().getId());
        dto.setUnitIdentifier(ps.getUnit().getUnitNumber());
        dto.setRenterName(ps.getLease().getRenter().getNameEn());
        dto.setPropertyName(ps.getProperty().getNameEn());
        dto.setInstallmentNumber(ps.getInstallmentNumber());
        dto.setDueDate(ps.getDueDate());
        dto.setAmount(ps.getAmount());
        dto.setStatus(ps.getStatus());
        dto.setChequeNumber(ps.getChequeNumber());
        dto.setBankName(ps.getBankName());
        dto.setPayerName(ps.getPayerName());
        dto.setChequeDate(ps.getChequeDate());
        dto.setChequeImageUrl(ps.getChequeImageUrl());
        dto.setChequeImageBlobPath(ps.getChequeImageBlobPath());
        dto.setChequeImageUploadedAt(ps.getChequeImageUploadedAt());
        dto.setStatusChangedAt(ps.getStatusChangedAt());
        dto.setNotes(ps.getNotes());
        dto.setReplacedById(ps.getReplacedBy() != null ? ps.getReplacedBy().getId() : null);
        dto.setPurposeLabel(ps.getPurposeLabel());
        dto.setIsBookingDeposit(ps.isBookingDeposit());
        dto.setPaymentMethod(ps.getPaymentMethod());
        return dto;
    }
}
