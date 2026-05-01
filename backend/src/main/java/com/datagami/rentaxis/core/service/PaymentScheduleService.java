package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.AgingReportDTO;
import com.datagami.rentaxis.api.dto.PaymentScheduleDTO;
import com.datagami.rentaxis.api.dto.LeasePaymentStatsDTO;
import com.datagami.rentaxis.api.dto.PaymentSummaryDTO;
import com.datagami.rentaxis.api.dto.UpdatePaymentStatusDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.TransactionNature;
import com.datagami.rentaxis.api.dto.PaymentPreviewDTO;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentScheduleService {

    private final PaymentScheduleRepository paymentScheduleRepository;
    private final AccountRepository accountRepository;
    private final FinancialTransactionService financialTransactionService;
    private final AccountMappingService accountMappingService;
    private final RentCollectionSettingsRepository rentCollectionSettingsRepository;
    private final NotificationService notificationService;

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

        // Even spacing: due date i = startDate + floor(i * months / n) months.
        // For 12 months / 4 cheques → offsets [0, 3, 6, 9].
        // For 13 months / 4 cheques → offsets [0, 3, 6, 9] (last covers 4 months).
        List<PaymentSchedule> schedules = new ArrayList<>();
        BigDecimal accumulated = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            long monthOffset = (long) Math.floor((double) i * totalMonths / n);
            LocalDate dueDate = lease.getStartDate().plusMonths(monthOffset);

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
        payment.setStatusChangedAt(Instant.now());
        PaymentSchedule saved = paymentScheduleRepository.save(payment);

        // Notify renter: cheque collected
        try {
            UUID renterUserId = payment.getLease().getRenter().getUserId();
            if (renterUserId != null) {
                notificationService.notify(TenantContextHolder.getTenantId(), renterUserId,
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

        return mapToDTO(paymentScheduleRepository.save(payment));
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

    @Transactional
    public PaymentScheduleDTO bouncePayment(UUID paymentId, UpdatePaymentStatusDTO dto) {
        PaymentSchedule payment = paymentScheduleRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment not found"));

        if (payment.getStatus() != PaymentStatus.DEPOSITED) {
            throw new BusinessRuleViolationException("Can only bounce payments in DEPOSITED status");
        }

        payment.setStatus(PaymentStatus.BOUNCED);
        if (dto.getNotes() != null) {
            payment.setNotes(dto.getNotes());
        }
        payment.setStatusChangedAt(Instant.now());
        PaymentSchedule saved = paymentScheduleRepository.save(payment);

        // Notify renter: cheque bounced
        try {
            UUID renterUserId = payment.getLease().getRenter().getUserId();
            if (renterUserId != null) {
                notificationService.notify(TenantContextHolder.getTenantId(), renterUserId,
                        "PAYMENT_BOUNCED", "Cheque Bounced",
                        "Installment #" + payment.getInstallmentNumber() + " cheque of " + payment.getAmount() + " has bounced. Please arrange a replacement.",
                        "PAYMENT", payment.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to send cheque bounced notification for payment {}: {}", payment.getId(), e.getMessage());
        }

        return mapToDTO(saved);
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
        dto.setStatusChangedAt(ps.getStatusChangedAt());
        dto.setNotes(ps.getNotes());
        dto.setReplacedById(ps.getReplacedBy() != null ? ps.getReplacedBy().getId() : null);
        dto.setPurposeLabel(ps.getPurposeLabel());
        dto.setIsBookingDeposit(ps.isBookingDeposit());
        return dto;
    }
}
