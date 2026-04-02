package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.SettlementPreviewDTO;
import com.datagami.rentaxis.api.dto.TerminateWithSettlementDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementService {

    private final LeaseSettlementRepository leaseSettlementRepository;
    private final LeaseSettlementDeductionRepository leaseSettlementDeductionRepository;
    private final LeaseRepository leaseRepository;
    private final PaymentScheduleRepository paymentScheduleRepository;
    private final PenaltyService penaltyService;

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

        // Calculate total deductions from provided items
        BigDecimal totalDeductions = BigDecimal.ZERO;
        if (dto.getDeductions() != null) {
            totalDeductions = dto.getDeductions().stream()
                    .map(TerminateWithSettlementDTO.DeductionItemDTO::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        settlement.setTotalDeductions(totalDeductions);
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

    @Transactional(readOnly = true)
    public Optional<LeaseSettlement> getSettlement(UUID leaseId) {
        return leaseSettlementRepository.findByLeaseId(leaseId);
    }

    @Transactional(readOnly = true)
    public List<LeaseSettlementDeduction> getSettlementDeductions(UUID settlementId) {
        return leaseSettlementDeductionRepository.findBySettlementIdOrderByCreatedAtAsc(settlementId);
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
