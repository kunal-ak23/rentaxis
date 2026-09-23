package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.*;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.LeaseInteraction;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LeaseInteractionService {

    private final LeaseInteractionRepository repo;
    private final LeaseRepository leaseRepo;
    private final RenewalOpportunityRepository oppRepo;
    private final UserRepository userRepo;

    @Transactional(readOnly = true)
    public Page<InteractionDTO> list(UUID leaseId, Pageable pageable) {
        return repo.findActiveByLeaseId(leaseId, pageable).map(this::toDTO);
    }

    @Transactional
    public InteractionDTO create(UUID leaseId, CreateInteractionRequest req, UUID userId) {
        var lease = leaseRepo.findById(leaseId).orElseThrow(() -> new NotFoundException("Lease not found"));
        LeaseInteraction i = new LeaseInteraction();
        i.setTenantId(lease.getTenantId());
        i.setLease(lease);
        i.setOpportunity(oppRepo.findByLeaseIdAndStageIn(leaseId, List.of(RenewalStage.OPEN, RenewalStage.INTENT_CAPTURED)).orElse(null));
        i.setType(req.type());
        i.setDirection(req.direction());
        i.setOccurredAt(req.occurredAt());
        i.setSummary(req.summary());
        i.setOutcome(req.outcome());
        i.setFollowUpDate(req.followUpDate());
        i.setCreatedBy(userId);
        return toDTO(repo.save(i));
    }

    @Transactional
    public InteractionDTO update(UUID interactionId, UpdateInteractionRequest req) {
        LeaseInteraction i = repo.findById(interactionId).orElseThrow(() -> new NotFoundException("Interaction not found"));
        if (i.getType() == InteractionType.SYSTEM_INTENT) {
            throw new BusinessRuleViolationException("SYSTEM_INTENT entries are read-only");
        }
        if (req.summary() != null) i.setSummary(req.summary());
        if (req.outcome() != null) i.setOutcome(req.outcome());
        if (req.followUpDate() != null) i.setFollowUpDate(req.followUpDate());
        return toDTO(repo.save(i));
    }

    @Transactional
    public void softDelete(UUID interactionId) {
        LeaseInteraction i = repo.findById(interactionId).orElseThrow(() -> new NotFoundException("Interaction not found"));
        if (i.getType() == InteractionType.SYSTEM_INTENT) {
            throw new BusinessRuleViolationException("SYSTEM_INTENT entries cannot be deleted");
        }
        i.setDeletedAt(Instant.now());
        repo.save(i);
    }

    private InteractionDTO toDTO(LeaseInteraction i) {
        // findDisplayNameById, not the tenant-filtered findById: a SUPER_ADMIN
        // acting inside a pivoted tenant has tenant_id = NULL, so the filtered
        // lookup can't see them and the name comes back blank.
        String createdByName = userRepo.findDisplayNameById(i.getCreatedBy()).orElse(null);
        return new InteractionDTO(
                i.getId(), i.getLease().getId(),
                i.getOpportunity() != null ? i.getOpportunity().getId() : null,
                i.getType(), i.getDirection(),
                i.getOccurredAt(), i.getSummary(),
                i.getOutcome(), i.getFollowUpDate(),
                i.getCreatedBy(), createdByName, i.getCreatedAt());
    }
}
