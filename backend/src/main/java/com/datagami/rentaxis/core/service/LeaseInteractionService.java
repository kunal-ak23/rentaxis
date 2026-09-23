package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.*;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.domain.entity.Lease;
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
    private final LeaseAccessPolicy leaseAccessPolicy;

    @Transactional(readOnly = true)
    public Page<InteractionDTO> list(UUID leaseId, Pageable pageable) {
        leaseAccessPolicy.requireReadable(lease(leaseId));
        return repo.findActiveByLeaseId(leaseId, pageable).map(this::toDTO);
    }

    @Transactional
    public InteractionDTO create(UUID leaseId, CreateInteractionRequest req, UUID userId) {
        Lease lease = lease(leaseId);
        leaseAccessPolicy.requireManageable(lease);
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
    public InteractionDTO update(UUID leaseId, UUID interactionId, UpdateInteractionRequest req) {
        LeaseInteraction i = scoped(leaseId, interactionId);
        if (i.getType() == InteractionType.SYSTEM_INTENT) {
            throw new BusinessRuleViolationException("SYSTEM_INTENT entries are read-only");
        }
        if (req.summary() != null) i.setSummary(req.summary());
        if (req.outcome() != null) i.setOutcome(req.outcome());
        if (req.followUpDate() != null) i.setFollowUpDate(req.followUpDate());
        return toDTO(repo.save(i));
    }

    @Transactional
    public void softDelete(UUID leaseId, UUID interactionId) {
        LeaseInteraction i = scoped(leaseId, interactionId);
        if (i.getType() == InteractionType.SYSTEM_INTENT) {
            throw new BusinessRuleViolationException("SYSTEM_INTENT entries cannot be deleted");
        }
        i.setDeletedAt(Instant.now());
        repo.save(i);
    }

    /**
     * Pending renewal follow-ups due on or before {@code cutoff}, narrowed to the
     * caller's buildings. This list used to be tenant-wide for a property manager
     * (audit P1-3), which also made it the lease-id oracle for every other gap.
     */
    @Transactional(readOnly = true)
    public List<InteractionDTO> pendingFollowUps(UUID tenantId, java.time.LocalDate cutoff) {
        List<UUID> visible = leaseAccessPolicy.visiblePropertyIds(); // null = tenant-wide
        return repo.findPendingFollowUps(tenantId, cutoff).stream()
                .filter(i -> visible == null || visible.contains(propertyOf(i.getLease())))
                .map(this::toDTO)
                .toList();
    }

    private static UUID propertyOf(Lease lease) {
        return lease != null && lease.getUnit() != null && lease.getUnit().getProperty() != null
                ? lease.getUnit().getProperty().getId() : null;
    }

    private Lease lease(UUID leaseId) {
        return leaseRepo.findById(leaseId).orElseThrow(() -> new NotFoundException("Lease not found"));
    }

    /**
     * The interaction named by the path, on the lease named by the path, on a lease
     * the caller may manage. The path's leaseId used to be ignored, so any
     * interaction id in the tenant could be rewritten under any lease URL.
     */
    private LeaseInteraction scoped(UUID leaseId, UUID interactionId) {
        LeaseInteraction i = repo.findById(interactionId)
                .orElseThrow(() -> new NotFoundException("Interaction not found"));
        if (i.getLease() == null || !i.getLease().getId().equals(leaseId)
                || !leaseAccessPolicy.canManage(i.getLease())) {
            throw new NotFoundException("Interaction not found");
        }
        return i;
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
