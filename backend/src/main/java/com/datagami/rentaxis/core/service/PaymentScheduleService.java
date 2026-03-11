package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PaymentScheduleDTO;
import com.datagami.rentaxis.api.dto.LeasePaymentStatsDTO;
import com.datagami.rentaxis.api.dto.PaymentSummaryDTO;
import com.datagami.rentaxis.api.dto.UpdatePaymentStatusDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
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

    @Transactional
    public List<PaymentSchedule> generateScheduleForLease(Lease lease) {
        // Idempotency check: skip if schedules already exist for this lease
        List<PaymentSchedule> existing = paymentScheduleRepository.findByLeaseId(lease.getId());
        if (!existing.isEmpty()) {
            return existing;
        }

        int terms = (lease.getPaymentTerms() == null || lease.getPaymentTerms() == 0)
                ? 1
                : lease.getPaymentTerms();

        BigDecimal totalRent = lease.getRentAmount();
        BigDecimal installmentAmount = totalRent.divide(BigDecimal.valueOf(terms), 2, RoundingMode.HALF_UP);

        // Calculate the remainder so the total exactly matches rentAmount
        BigDecimal allocatedTotal = installmentAmount.multiply(BigDecimal.valueOf(terms));
        BigDecimal remainder = totalRent.subtract(allocatedTotal);

        List<PaymentSchedule> schedules = new ArrayList<>();

        for (int i = 1; i <= terms; i++) {
            PaymentSchedule ps = new PaymentSchedule();
            ps.setLease(lease);
            ps.setUnit(lease.getUnit());
            ps.setProperty(lease.getUnit().getProperty());
            ps.setInstallmentNumber(i);
            ps.setDueDate(lease.getStartDate().plusMonths(i - 1));
            ps.setStatus(PaymentStatus.PENDING);

            // Add remainder to the last installment
            if (i == terms) {
                ps.setAmount(installmentAmount.add(remainder));
            } else {
                ps.setAmount(installmentAmount);
            }

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
        UUID tenantId = TenantContextHolder.getTenantId();

        // Debit: Bank/Cash account (A-01-01)
        Account bankAccount = accountRepository.findByCodeAndTenantId("A-01-01", tenantId)
                .orElseThrow(() -> new RuntimeException("Bank/Cash account (A-01-01) not found"));

        FinancialTransaction debitTxn = new FinancialTransaction();
        debitTxn.setDate(LocalDate.now());
        debitTxn.setDescription("Cheque cleared - Lease installment #" + payment.getInstallmentNumber());
        debitTxn.setAccount(bankAccount);
        debitTxn.setDebit(payment.getAmount());
        debitTxn.setCredit(BigDecimal.ZERO);
        debitTxn.setProperty(payment.getProperty());
        debitTxn.setUnit(payment.getUnit());
        financialTransactionService.createTransaction(debitTxn);

        // Credit: Rental Income account (C-01-01)
        Account rentalIncomeAccount = accountRepository.findByCodeAndTenantId("C-01-01", tenantId)
                .orElseThrow(() -> new RuntimeException("Rental Income account (C-01-01) not found"));

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
        dto.setStatusChangedAt(ps.getStatusChangedAt());
        dto.setNotes(ps.getNotes());
        dto.setReplacedById(ps.getReplacedBy() != null ? ps.getReplacedBy().getId() : null);
        return dto;
    }
}
