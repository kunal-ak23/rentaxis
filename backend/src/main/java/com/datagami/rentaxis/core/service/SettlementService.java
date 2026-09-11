package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.SaveSettlementDTO;
import com.datagami.rentaxis.api.dto.SettlementPreviewDTO;
import com.datagami.rentaxis.api.dto.SettlementResponseDTO;
import com.datagami.rentaxis.api.dto.TerminateWithSettlementDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.enums.AdditionCategory;
import com.datagami.rentaxis.domain.entity.enums.LineItemType;
import com.datagami.rentaxis.domain.entity.enums.SettlementStatus;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.AccountMapping;
import com.datagami.rentaxis.domain.entity.FinancialTransaction;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseSettlement;
import com.datagami.rentaxis.domain.entity.LeaseSettlementDeduction;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.TransactionNature;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.LeaseSettlementDeductionRepository;
import com.datagami.rentaxis.domain.repository.LeaseSettlementRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SettlementService {

    private final LeaseSettlementRepository leaseSettlementRepository;
    private final LeaseSettlementDeductionRepository leaseSettlementDeductionRepository;
    private final LeaseRepository leaseRepository;
    private final com.datagami.rentaxis.core.security.LeaseAccessPolicy leaseAccessPolicy;
    private final PaymentScheduleRepository paymentScheduleRepository;
    private final PenaltyService penaltyService;
    private final DeductionAttachmentService deductionAttachmentService;
    private final AccountMappingService accountMappingService;
    private final AccountRepository accountRepository;
    private final FinancialTransactionService financialTransactionService;

    @Transactional(readOnly = true)
    public SettlementPreviewDTO getSettlementPreview(UUID leaseId) {
        // Settlement carries deposit, deductions and final balances. The role
        // gate allows PROPERTY_MANAGER, so without this a manager could read
        // and write settlements for properties they were never assigned.
        leaseAccessPolicy.requireReadable(leaseRepository.findById(leaseId).orElse(null));
        Lease lease = findLeaseWithTenantCheck(leaseId);

        BigDecimal depositAmount = lease.getDepositAmount() != null ? lease.getDepositAmount() : BigDecimal.ZERO;

        // Sum all PENDING and ONLINE_PENDING payment schedules as unpaid rent
        List<PaymentSchedule> payments = paymentScheduleRepository.findByLeaseId(leaseId);
        BigDecimal unpaidRentTotal = payments.stream()
                .filter(ps -> ps.getStatus() == PaymentStatus.PENDING || ps.getStatus() == PaymentStatus.ONLINE_PENDING || ps.getStatus() == PaymentStatus.OVERDUE)
                .map(PaymentSchedule::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Get total unwaived penalties
        BigDecimal penaltyTotal = penaltyService.getTotalUnwaivedPenalties(leaseId);

        BigDecimal suggestedRefund = depositAmount.subtract(unpaidRentTotal).subtract(penaltyTotal);

        SettlementPreviewDTO preview = new SettlementPreviewDTO();
        preview.setDepositAmount(depositAmount);
        preview.setUnpaidRentTotal(unpaidRentTotal);
        preview.setPenaltyTotal(penaltyTotal);
        preview.setSuggestedRefund(suggestedRefund);
        return preview;
    }

    @Transactional
    public LeaseSettlement createSettlement(UUID leaseId, TerminateWithSettlementDTO dto, UUID settledBy) {
        Lease lease = findLeaseWithTenantCheck(leaseId);

        BigDecimal depositAmount = lease.getDepositAmount() != null ? lease.getDepositAmount() : BigDecimal.ZERO;

        LeaseSettlement settlement = new LeaseSettlement();
        settlement.setLeaseId(leaseId);
        settlement.setDepositAmount(depositAmount);
        settlement.setNotes(dto.getNotes());
        settlement.setSettledBy(settledBy);
        settlement.setSettledAt(LocalDateTime.now());
        settlement.setStatus(SettlementStatus.FINALIZED);

        // Calculate total deductions from provided items
        BigDecimal totalDeductions = BigDecimal.ZERO;
        if (dto.getDeductions() != null) {
            totalDeductions = dto.getDeductions().stream()
                    .map(TerminateWithSettlementDTO.DeductionItemDTO::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        settlement.setTotalDeductions(totalDeductions);
        settlement.setTotalAdditions(BigDecimal.ZERO);
        // Legacy direct-terminate path: all items treated as DEDUCTIONS. Use saveDraft + finalizeSettlement for additions support.
        settlement.setRefundAmount(depositAmount.subtract(totalDeductions));

        LeaseSettlement savedSettlement = leaseSettlementRepository.save(settlement);

        // Create deduction records
        if (dto.getDeductions() != null) {
            for (TerminateWithSettlementDTO.DeductionItemDTO item : dto.getDeductions()) {
                LeaseSettlementDeduction deduction = new LeaseSettlementDeduction();
                deduction.setSettlementId(savedSettlement.getId());
                deduction.setCategory(item.getCategory());
                deduction.setDescription(item.getDescription());
                deduction.setAmount(item.getAmount());
                deduction.setAutoCalculated(false);
                leaseSettlementDeductionRepository.save(deduction);
            }
        }

        // This legacy path finalizes in one step rather than going through
        // finalizeSettlement, so it has to release the deposit liability itself
        // — otherwise settling via terminate-with-settlement would leave the
        // B-01-02 balance standing while settling via the draft flow cleared it.
        postDepositReleaseEntries(savedSettlement);

        return savedSettlement;
    }

    @Transactional
    public LeaseSettlement saveDraft(UUID leaseId, SaveSettlementDTO dto, UUID userId) {
        // Settlement carries deposit, deductions and final balances. The role
        // gate allows PROPERTY_MANAGER, so without this a manager could read
        // and write settlements for properties they were never assigned.
        leaseAccessPolicy.requireReadable(leaseRepository.findById(leaseId).orElse(null));
        Lease lease = findLeaseWithTenantCheck(leaseId);
        BigDecimal depositAmount = lease.getDepositAmount() != null ? lease.getDepositAmount() : BigDecimal.ZERO;

        Optional<LeaseSettlement> existingOpt = leaseSettlementRepository.findByLeaseId(leaseId);
        LeaseSettlement settlement;

        if (existingOpt.isPresent()) {
            settlement = existingOpt.get();
            if (settlement.getStatus() == SettlementStatus.FINALIZED) {
                throw new IllegalStateException("Settlement is already finalized");
            }
        } else {
            settlement = new LeaseSettlement();
            settlement.setLeaseId(leaseId);
            settlement.setStatus(SettlementStatus.DRAFT);
        }

        settlement.setDepositAmount(depositAmount);
        settlement.setNotes(dto.getNotes());

        BigDecimal totalDeductions = BigDecimal.ZERO;
        BigDecimal totalAdditions = BigDecimal.ZERO;
        if (dto.getDeductions() != null) {
            for (SaveSettlementDTO.DeductionItemDTO item : dto.getDeductions()) {
                if (item.getType() == LineItemType.ADDITION) {
                    totalAdditions = totalAdditions.add(item.getAmount());
                } else {
                    totalDeductions = totalDeductions.add(item.getAmount());
                }
            }
        }
        settlement.setTotalDeductions(totalDeductions);
        settlement.setTotalAdditions(totalAdditions);
        settlement.setRefundAmount(depositAmount.subtract(totalDeductions).add(totalAdditions));

        LeaseSettlement savedSettlement = leaseSettlementRepository.save(settlement);

        // Reconcile deductions: update existing (preserving attachments), create new, delete removed
        List<LeaseSettlementDeduction> oldDeductions =
                leaseSettlementDeductionRepository.findBySettlementIdOrderByCreatedAtAsc(savedSettlement.getId());
        Map<UUID, LeaseSettlementDeduction> oldById = oldDeductions.stream()
                .collect(Collectors.toMap(LeaseSettlementDeduction::getId, d -> d));

        Set<UUID> incomingIds = new HashSet<>();
        if (dto.getDeductions() != null) {
            for (SaveSettlementDTO.DeductionItemDTO item : dto.getDeductions()) {
                if (item.getId() != null && oldById.containsKey(item.getId())) {
                    // Update existing deduction in-place (preserves attachments)
                    LeaseSettlementDeduction existing = oldById.get(item.getId());
                    existing.setCategory(item.getCategory());
                    existing.setDescription(item.getDescription());
                    existing.setAmount(item.getAmount());
                    existing.setAutoCalculated(item.isAutoCalculated());
                    // Set type and additionCategory
                    LineItemType lineItemType = item.getType() != null ? item.getType() : LineItemType.DEDUCTION;
                    existing.setType(lineItemType);
                    if (lineItemType == LineItemType.ADDITION && item.getAdditionCategory() != null) {
                        try {
                            existing.setAdditionCategory(AdditionCategory.valueOf(item.getAdditionCategory()));
                        } catch (IllegalArgumentException e) {
                            throw new IllegalArgumentException("Invalid additionCategory: " + item.getAdditionCategory());
                        }
                        existing.setCategory(null);
                    } else {
                        existing.setAdditionCategory(null);
                    }
                    leaseSettlementDeductionRepository.save(existing);
                    incomingIds.add(item.getId());
                } else {
                    // New deduction
                    LeaseSettlementDeduction deduction = new LeaseSettlementDeduction();
                    deduction.setSettlementId(savedSettlement.getId());
                    deduction.setCategory(item.getCategory());
                    deduction.setDescription(item.getDescription());
                    deduction.setAmount(item.getAmount());
                    deduction.setAutoCalculated(item.isAutoCalculated());
                    // Set type and additionCategory
                    LineItemType lineItemType = item.getType() != null ? item.getType() : LineItemType.DEDUCTION;
                    deduction.setType(lineItemType);
                    if (lineItemType == LineItemType.ADDITION && item.getAdditionCategory() != null) {
                        try {
                            deduction.setAdditionCategory(AdditionCategory.valueOf(item.getAdditionCategory()));
                        } catch (IllegalArgumentException e) {
                            throw new IllegalArgumentException("Invalid additionCategory: " + item.getAdditionCategory());
                        }
                        deduction.setCategory(null);
                    } else {
                        deduction.setAdditionCategory(null);
                    }
                    leaseSettlementDeductionRepository.save(deduction);
                }
            }
        }

        // Delete only deductions that were removed (and their attachments via CASCADE)
        for (LeaseSettlementDeduction old : oldDeductions) {
            if (!incomingIds.contains(old.getId())) {
                deductionAttachmentService.deleteAllByDeductionId(old.getId());
                leaseSettlementDeductionRepository.delete(old);
            }
        }

        return savedSettlement;
    }

    @Transactional
    public LeaseSettlement finalizeSettlement(UUID leaseId, UUID settledBy) {
        // Settlement carries deposit, deductions and final balances. The role
        // gate allows PROPERTY_MANAGER, so without this a manager could read
        // and write settlements for properties they were never assigned.
        leaseAccessPolicy.requireReadable(leaseRepository.findById(leaseId).orElse(null));
        findLeaseWithTenantCheck(leaseId);
        LeaseSettlement settlement = leaseSettlementRepository.findByLeaseId(leaseId)
                .orElseThrow(() -> new NotFoundException("No settlement found for this lease"));

        if (settlement.getStatus() == SettlementStatus.FINALIZED) {
            throw new IllegalStateException("Settlement is already finalized");
        }

        settlement.setStatus(SettlementStatus.FINALIZED);
        settlement.setSettledBy(settledBy);
        settlement.setSettledAt(LocalDateTime.now());

        LeaseSettlement finalized = leaseSettlementRepository.save(settlement);
        postDepositReleaseEntries(finalized);
        return finalized;
    }

    /**
     * Releases the security-deposit liability when a settlement is finalized.
     *
     * <p>Deposits are credited to B-01-02 "Security Deposits" when the deposit
     * cheque clears (see {@code PaymentScheduleService.clearPayment}). Until
     * this existed, settlement posted nothing at all, so the liability was never
     * released and the books carried every deposit ever taken, forever.
     *
     * <p>The entry set is:
     * <ul>
     *   <li>debit B-01-02 for the full deposit — the liability is discharged</li>
     *   <li>credit the bank for the refund actually paid back</li>
     *   <li>the remainder (deductions net of additions) is retained by the
     *       landlord and becomes other income; if additions exceeded the
     *       deposit the landlord paid out more than it held, so that excess is
     *       debited instead</li>
     * </ul>
     * which balances in both directions, since
     * {@code refund = deposit - deductions + additions}.
     *
     * <p>Deliberately not itemised per deduction category: the categories on
     * LeaseSettlementDeduction do not map onto seeded expense accounts, and
     * inventing that mapping would put guesses in the ledger. The retained total
     * lands in one Other Income line that reconciles to the settlement record.
     */
    private void postDepositReleaseEntries(LeaseSettlement settlement) {
        BigDecimal deposit = nz(settlement.getDepositAmount());
        BigDecimal refund = nz(settlement.getRefundAmount());
        if (deposit.signum() == 0 && refund.signum() == 0) {
            return;
        }

        AccountMapping mapping = accountMappingService.resolveMapping(TransactionNature.SECURITY_DEPOSIT_REFUNDED);
        Account depositLiability;
        Account bank;
        if (mapping != null) {
            depositLiability = mapping.getDebitAccount();
            bank = mapping.getCreditAccount();
        } else {
            UUID tenantId = TenantContextHolder.getTenantId();
            depositLiability = accountRepository.findByCodeAndTenantId("B-01-02", tenantId).orElse(null);
            bank = accountRepository.findByCodeAndTenantId("A-02-02", tenantId).orElse(null);
        }
        // A tenant with no chart of accounts should still be able to settle a
        // lease; the settlement record itself is the source of truth. Skip the
        // ledger entries rather than failing the settlement.
        if (depositLiability == null || bank == null) {
            return;
        }

        UUID leaseId = settlement.getLeaseId();
        LocalDate today = LocalDate.now();

        if (deposit.signum() > 0) {
            post(depositLiability, deposit, BigDecimal.ZERO, today,
                    "Security deposit released - settlement for lease " + leaseId);
        }
        if (refund.signum() > 0) {
            post(bank, BigDecimal.ZERO, refund, today,
                    "Security deposit refunded - settlement for lease " + leaseId);
        }

        BigDecimal retained = deposit.subtract(refund);
        if (retained.signum() != 0) {
            Account otherIncome = accountRepository
                    .findByCodeAndTenantId("C-01-02", TenantContextHolder.getTenantId()).orElse(null);
            if (otherIncome != null) {
                if (retained.signum() > 0) {
                    post(otherIncome, BigDecimal.ZERO, retained, today,
                            "Settlement deductions retained - lease " + leaseId);
                } else {
                    post(otherIncome, retained.negate(), BigDecimal.ZERO, today,
                            "Settlement additions paid beyond deposit - lease " + leaseId);
                }
            }
        }
    }

    private void post(Account account, BigDecimal debit, BigDecimal credit, LocalDate date, String description) {
        FinancialTransaction txn = new FinancialTransaction();
        txn.setDate(date);
        txn.setDescription(description);
        txn.setAccount(account);
        txn.setDebit(debit);
        txn.setCredit(credit);
        financialTransactionService.createTransaction(txn);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    @Transactional(readOnly = true)
    public Optional<LeaseSettlement> getSettlement(UUID leaseId) {
        return leaseSettlementRepository.findByLeaseId(leaseId);
    }

    @Transactional(readOnly = true)
    public List<LeaseSettlementDeduction> getSettlementDeductions(UUID settlementId) {
        return leaseSettlementDeductionRepository.findBySettlementIdOrderByCreatedAtAsc(settlementId);
    }

    @Transactional(readOnly = true)
    public SettlementResponseDTO buildSettlementResponse(UUID leaseId) {
        // Settlement carries deposit, deductions and final balances. The role
        // gate allows PROPERTY_MANAGER, so without this a manager could read
        // and write settlements for properties they were never assigned.
        leaseAccessPolicy.requireReadable(leaseRepository.findById(leaseId).orElse(null));
        LeaseSettlement settlement = leaseSettlementRepository.findByLeaseId(leaseId)
                .orElseThrow(() -> new NotFoundException("Settlement not found"));

        List<LeaseSettlementDeduction> deductions =
                leaseSettlementDeductionRepository.findBySettlementIdOrderByCreatedAtAsc(settlement.getId());

        SettlementResponseDTO response = new SettlementResponseDTO();
        response.setId(settlement.getId());
        response.setLeaseId(settlement.getLeaseId());
        response.setDepositAmount(settlement.getDepositAmount());
        response.setTotalDeductions(settlement.getTotalDeductions());
        response.setTotalAdditions(settlement.getTotalAdditions());
        response.setRefundAmount(settlement.getRefundAmount());
        response.setNotes(settlement.getNotes());
        response.setStatus(settlement.getStatus().name());
        response.setSettledBy(settlement.getSettledBy());
        response.setSettledAt(settlement.getSettledAt());
        response.setCreatedAt(settlement.getCreatedAt());

        List<SettlementResponseDTO.DeductionDTO> deductionDTOs = deductions.stream().map(d -> {
            SettlementResponseDTO.DeductionDTO dto = new SettlementResponseDTO.DeductionDTO();
            dto.setId(d.getId());
            dto.setCategory(d.getCategory() != null ? d.getCategory().name() : null);
            dto.setDescription(d.getDescription());
            dto.setAmount(d.getAmount());
            dto.setAutoCalculated(d.isAutoCalculated());
            dto.setType(d.getType().name());
            dto.setAdditionCategory(d.getAdditionCategory() != null ? d.getAdditionCategory().name() : null);
            dto.setAttachments(deductionAttachmentService.getAttachments(d.getId()));
            return dto;
        }).collect(Collectors.toList());

        response.setDeductions(deductionDTOs);
        return response;
    }

    private Lease findLeaseWithTenantCheck(UUID leaseId) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        UUID currentTenantId = TenantContextHolder.getTenantId();
        if (currentTenantId != null && !currentTenantId.equals(lease.getTenantId())) {
            throw new NotFoundException("Lease not found");
        }
        return lease;
    }
}
