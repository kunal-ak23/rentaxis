package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.SaveSettlementDTO;
import com.datagami.rentaxis.api.dto.SettlementPreviewDTO;
import com.datagami.rentaxis.api.dto.SettlementResponseDTO;
import com.datagami.rentaxis.api.dto.TerminateWithSettlementDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.enums.AdditionCategory;
import com.datagami.rentaxis.domain.entity.enums.LineItemType;
import com.datagami.rentaxis.domain.entity.enums.SettlementStatus;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseSettlement;
import com.datagami.rentaxis.domain.entity.LeaseSettlementDeduction;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.LeaseSettlementDeductionRepository;
import com.datagami.rentaxis.domain.repository.LeaseSettlementRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final PaymentScheduleRepository paymentScheduleRepository;
    private final PenaltyService penaltyService;
    private final DeductionAttachmentService deductionAttachmentService;

    @Transactional(readOnly = true)
    public SettlementPreviewDTO getSettlementPreview(UUID leaseId) {
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

        return savedSettlement;
    }

    @Transactional
    public LeaseSettlement saveDraft(UUID leaseId, SaveSettlementDTO dto, UUID userId) {
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
        findLeaseWithTenantCheck(leaseId);
        LeaseSettlement settlement = leaseSettlementRepository.findByLeaseId(leaseId)
                .orElseThrow(() -> new NotFoundException("No settlement found for this lease"));

        if (settlement.getStatus() == SettlementStatus.FINALIZED) {
            throw new IllegalStateException("Settlement is already finalized");
        }

        settlement.setStatus(SettlementStatus.FINALIZED);
        settlement.setSettledBy(settledBy);
        settlement.setSettledAt(LocalDateTime.now());

        return leaseSettlementRepository.save(settlement);
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
