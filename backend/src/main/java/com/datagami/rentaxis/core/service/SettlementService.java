package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.SaveSettlementDTO;
import com.datagami.rentaxis.api.dto.SettlementPreviewDTO;
import com.datagami.rentaxis.api.dto.SettlementResponseDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.enums.AdditionCategory;
import com.datagami.rentaxis.domain.entity.enums.LineItemType;
import com.datagami.rentaxis.domain.entity.enums.SettlementStatus;
import com.datagami.rentaxis.core.service.lease.LeaseDepositLedger;
import com.datagami.rentaxis.core.service.penalty.PenaltyAssessmentService;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseSettlement;
import com.datagami.rentaxis.domain.entity.LeaseSettlementDeduction;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.LeaseSettlementDeductionRepository;
import com.datagami.rentaxis.domain.repository.LeaseSettlementRepository;
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
    private final ChequeRepository chequeRepository;
    private final LeaseDepositLedger depositLedger;
    private final PenaltyAssessmentService penaltyAssessmentService;
    private final DeductionAttachmentService deductionAttachmentService;

    /**
     * What the landlord is actually holding for this lease.
     *
     * <p><b>The ledger, not {@code lease.depositAmount}.</b> The contract column is
     * what was <em>charged</em>; by the time a lease is being settled the deposit
     * may have been partly refunded, partly forfeited against a repair, or carried
     * forward wholesale into a renewal. Offering the renter a refund computed from
     * the contract figure hands back money the landlord no longer has — and on a
     * RENEWED lease whose deposit went to the successor, it hands back the whole of
     * it twice. Same collaborator the carry-forward uses, so the two can never
     * disagree (spec §6.6).</p>
     */
    private BigDecimal depositHeld(Lease lease) {
        return depositLedger.depositHeld(lease);
    }

    @Transactional(readOnly = true)
    public SettlementPreviewDTO getSettlementPreview(UUID leaseId) {
        // Settlement carries deposit, deductions and final balances. The role
        // gate allows PROPERTY_MANAGER, so without this a manager could read
        // and write settlements for properties they were never assigned.
        leaseAccessPolicy.requireReadable(leaseRepository.findById(leaseId).orElse(null));
        Lease lease = findLeaseWithTenantCheck(leaseId);

        BigDecimal depositAmount = depositHeld(lease);

        // Unpaid rent is the register's DUE rows — the same predicate the register
        // screen, the reminder job and the aging report use (ChequeDueRules.due), so
        // the settlement cannot show an arrears figure the collections screen
        // disagrees with. DRAFT rows are excluded by the query: a proposal nobody
        // handed over is not a debt.
        //
        // Plan 3 replaces this with the receivable's ledger balance, which is the
        // real answer — the register knows what instruments are outstanding, not
        // what rent has been earned. Until then this is the closest operational
        // truth, and it is the number the old schedule-based preview meant.
        // Penalty collection rows are skipped here and only here. Approving a
        // penalty puts a CASH row on the register dated that day, so it is due the
        // moment it exists — leaving it in would charge the renter's deposit for
        // the same fine twice, once as "unpaid rent" and once as "penalties". The
        // filter is in the service rather than in the query so findDueForLease
        // stays a word-for-word mirror of the register's own due predicate.
        List<Cheque> due = chequeRepository.findDueForLease(leaseId, LocalDate.now());
        BigDecimal unpaidRentTotal = due.stream()
                .filter(c -> c.getPenaltyAssessmentId() == null)
                .map(Cheque::getAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // APPROVED assessments whose collection row has not cleared.
        BigDecimal penaltyTotal = penaltyAssessmentService.outstandingForLease(leaseId);

        BigDecimal suggestedRefund = depositAmount.subtract(unpaidRentTotal).subtract(penaltyTotal);

        SettlementPreviewDTO preview = new SettlementPreviewDTO();
        preview.setDepositAmount(depositAmount);
        preview.setUnpaidRentTotal(unpaidRentTotal);
        preview.setPenaltyTotal(penaltyTotal);
        preview.setSuggestedRefund(suggestedRefund);
        return preview;
    }

    // createSettlement is gone with LeaseService.terminateWithSettlement. It built a
    // FINALIZED settlement out of a free-text deduction list handed in with the
    // termination request, posted nothing, and left the refund as a number on a row.
    // Spec §9.2 makes the settlement a statement drawn from the ledger after the
    // termination, finalised by one STL journal; saveDraft/finalizeSettlement are the
    // path, and Task 6 rewrites them.

    @Transactional
    public LeaseSettlement saveDraft(UUID leaseId, SaveSettlementDTO dto, UUID userId) {
        // Settlement carries deposit, deductions and final balances. The role
        // gate allows PROPERTY_MANAGER, so without this a manager could read
        // and write settlements for properties they were never assigned.
        leaseAccessPolicy.requireReadable(leaseRepository.findById(leaseId).orElse(null));
        Lease lease = findLeaseWithTenantCheck(leaseId);
        // The same figure the preview showed. A draft built from the contract column
        // while the preview was built from the ledger would quietly change the
        // refund the moment the accountant pressed Save.
        BigDecimal depositAmount = depositHeld(lease);

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
        // Ledger posting moves to PostingService in accounting v2 plan 2/3 (see spec §7/§9).
        return finalized;
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

    /**
     * The lease, scoped to the caller's tenant.
     *
     * <p><b>A missing tenant is refused, not tolerated.</b> This used to skip the
     * comparison when the context was empty, which was survivable while every
     * figure came off the lease row itself. It is not now: the deposit is read from
     * {@code journal_lines} and the arrears from the register, both through JPQL
     * that relies on the Hibernate tenant filter — and {@code TenantAspect} only
     * enables that filter when a tenant is set. Without one, a preview would either
     * cross tenants or (via {@code LeaseDepositLedger}) fail deep inside the
     * arithmetic with an error about a deposit balance, which tells the caller
     * nothing about what is actually wrong. Refused here, once, in the caller's own
     * terms.</p>
     */
    private Lease findLeaseWithTenantCheck(UUID leaseId) {
        UUID currentTenantId = TenantContextHolder.getTenantId();
        if (currentTenantId == null) {
            throw new IllegalStateException(
                    "No tenant in context; a settlement cannot be read or written without one");
        }
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        if (!currentTenantId.equals(lease.getTenantId())) {
            throw new NotFoundException("Lease not found");
        }
        return lease;
    }
}
