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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PaymentScheduleService {

    private final PaymentScheduleRepository paymentScheduleRepository;
    private final AccountRepository accountRepository;
    private final FinancialTransactionService financialTransactionService;
    private final AccountMappingService accountMappingService;
    private final RentCollectionSettingsRepository rentCollectionSettingsRepository;

    @Transactional
    public List<PaymentSchedule> generateScheduleForLease(Lease lease) {
        List<PaymentSchedule> existing = paymentScheduleRepository.findByLeaseId(lease.getId());
        if (!existing.isEmpty()) {
            return existing;
        }

        PaymentPreviewDTO preview = previewSchedule(
                lease.getUnit().getProperty().getId(),
                lease.getStartDate(),
                lease.getEndDate(),
                lease.getRentAmount());

        List<PaymentSchedule> schedules = new ArrayList<>();
        for (PaymentPreviewDTO.PaymentPreviewLine line : preview.getLines()) {
            PaymentSchedule ps = new PaymentSchedule();
            ps.setLease(lease);
            ps.setUnit(lease.getUnit());
            ps.setProperty(lease.getUnit().getProperty());
            ps.setInstallmentNumber(line.getInstallmentNumber());
            ps.setDueDate(line.getDueDate());
            ps.setAmount(line.getAmount());
            ps.setStatus(PaymentStatus.PENDING);
            ps.setPaymentMethod(lease.getPaymentMethod() != null ? lease.getPaymentMethod().name() : "CHEQUE");
            schedules.add(ps);
        }

        return paymentScheduleRepository.saveAll(schedules);
    }

    @Transactional(readOnly = true)
    public List<PaymentScheduleDTO> getPaymentsForLease(UUID leaseId) {
        return paymentScheduleRepository.findByLeaseId(leaseId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PaymentScheduleDTO> getPaymentsForProperty(UUID propertyId, PaymentStatus status) {
        List<PaymentSchedule> payments;
        if (propertyId != null && status != null) {
            payments = paymentScheduleRepository.findByPropertyIdAndStatusIn(propertyId, List.of(status));
        } else if (propertyId != null) {
            payments = paymentScheduleRepository.findByPropertyId(propertyId);
        } else if (status != null) {
            payments = paymentScheduleRepository.findByStatus(status);
        } else {
            payments = paymentScheduleRepository.findAll();
        }
        return payments.stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
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

        return mapToDTO(paymentScheduleRepository.save(payment));
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
        financialTransactionService.createTransaction(creditTxn);

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

        return mapToDTO(paymentScheduleRepository.save(payment));
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
            schedules = paymentScheduleRepository.findAll().stream()
                    .filter(ps -> ps.getLease() != null && ps.getLease().getUnit() != null
                            && ps.getLease().getUnit().getProperty() != null
                            && ps.getLease().getUnit().getProperty().getId().equals(propertyId))
                    .toList();
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
    public PaymentPreviewDTO previewSchedule(UUID propertyId, LocalDate startDate, LocalDate endDate, BigDecimal rentAmount) {
        // Look up property's due day (default to 1 if not configured)
        var settings = rentCollectionSettingsRepository.findByPropertyId(propertyId).orElse(null);
        int dueDay = (settings != null && settings.getDueDayOfMonth() != null && settings.getDueDayOfMonth() >= 1 && settings.getDueDayOfMonth() <= 28)
                ? settings.getDueDayOfMonth() : 1;
        boolean onlineEnabled = settings != null && Boolean.TRUE.equals(settings.getOnlinePaymentEnabled());

        long totalDays = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate);
        if (totalDays <= 0) throw new RuntimeException("End date must be after start date");

        BigDecimal dailyRate = rentAmount.divide(BigDecimal.valueOf(totalDays), 10, RoundingMode.HALF_UP);

        List<PaymentPreviewDTO.PaymentPreviewLine> lines = new ArrayList<>();

        // Calculate first due date on the configured day of month after lease start
        LocalDate firstDueDate;
        if (startDate.getDayOfMonth() == dueDay) {
            firstDueDate = startDate;
        } else if (startDate.getDayOfMonth() < dueDay) {
            firstDueDate = startDate.withDayOfMonth(dueDay);
        } else {
            firstDueDate = startDate.plusMonths(1).withDayOfMonth(dueDay);
        }

        int installment = 1;

        // Pro-rata first payment if lease doesn't start on due day
        if (!startDate.equals(firstDueDate) && firstDueDate.isBefore(endDate)) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(startDate, firstDueDate);
            BigDecimal proRataAmount = dailyRate.multiply(BigDecimal.valueOf(days)).setScale(2, RoundingMode.HALF_UP);

            PaymentPreviewDTO.PaymentPreviewLine line = new PaymentPreviewDTO.PaymentPreviewLine();
            line.setInstallmentNumber(installment++);
            line.setDueDate(startDate);
            line.setPeriodStart(startDate);
            line.setPeriodEnd(firstDueDate.minusDays(1));
            line.setAmount(proRataAmount);
            line.setProRata(true);
            lines.add(line);
        }

        // Generate monthly payments
        LocalDate currentDue = firstDueDate.isBefore(endDate) ? firstDueDate : startDate;
        while (currentDue.isBefore(endDate)) {
            LocalDate nextDue = currentDue.plusMonths(1);
            // Keep the due day consistent
            try {
                nextDue = nextDue.withDayOfMonth(dueDay);
            } catch (Exception e) {
                nextDue = nextDue.withDayOfMonth(nextDue.lengthOfMonth());
            }

            LocalDate periodEnd;
            boolean isLast = false;
            if (!nextDue.isAfter(endDate)) {
                periodEnd = nextDue.minusDays(1);
            } else {
                periodEnd = endDate;
                isLast = true;
            }

            long days = java.time.temporal.ChronoUnit.DAYS.between(currentDue, periodEnd) + 1;
            BigDecimal amount = dailyRate.multiply(BigDecimal.valueOf(days)).setScale(2, RoundingMode.HALF_UP);

            PaymentPreviewDTO.PaymentPreviewLine line = new PaymentPreviewDTO.PaymentPreviewLine();
            line.setInstallmentNumber(installment++);
            line.setDueDate(currentDue);
            line.setPeriodStart(currentDue);
            line.setPeriodEnd(periodEnd);
            line.setAmount(amount);
            line.setProRata(isLast && days < 25);
            lines.add(line);

            if (isLast) break;
            currentDue = nextDue;
        }

        // Adjust last payment so total exactly equals rentAmount
        BigDecimal calculatedTotal = lines.stream()
                .map(PaymentPreviewDTO.PaymentPreviewLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal diff = rentAmount.subtract(calculatedTotal);
        if (diff.compareTo(BigDecimal.ZERO) != 0 && !lines.isEmpty()) {
            PaymentPreviewDTO.PaymentPreviewLine last = lines.get(lines.size() - 1);
            last.setAmount(last.getAmount().add(diff));
        }

        PaymentPreviewDTO dto = new PaymentPreviewDTO();
        dto.setLines(lines);
        dto.setTotalAmount(rentAmount);
        dto.setTotalPayments(lines.size());
        dto.setDueDayOfMonth(dueDay);
        dto.setDefaultPaymentMethod(onlineEnabled ? "ONLINE" : "CHEQUE");
        return dto;
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
        return dto;
    }
}
