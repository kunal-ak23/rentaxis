package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.email.event.RenewalIntentCapturedEvent;
import com.datagami.rentaxis.domain.entity.LeaseInteraction;
import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.entity.enums.InteractionDirection;
import com.datagami.rentaxis.domain.entity.enums.InteractionType;
import com.datagami.rentaxis.domain.entity.enums.ReminderStatus;
import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import com.datagami.rentaxis.domain.entity.enums.RenewalStage;
import com.datagami.rentaxis.domain.repository.LeaseInteractionRepository;
import com.datagami.rentaxis.domain.repository.LeaseReminderRepository;
import com.datagami.rentaxis.domain.repository.RenewalOpportunityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RenewalIntentService {

    private final RenewalOpportunityRepository opportunityRepository;
    private final LeaseInteractionRepository interactionRepository;
    private final LeaseReminderRepository reminderRepository;
    private final ApplicationEventPublisher events;

    /** Called from the public token endpoint AFTER token verify + tenant context set. */
    @Transactional
    public RenewalOpportunity captureIntentFromToken(UUID opportunityId, RenewalIntent intent) {
        RenewalOpportunity o = opportunityRepository.findById(opportunityId)
                .orElseThrow(() -> new NotFoundException("Opportunity not found"));
        if (o.getStage() == RenewalStage.CLOSED_WON || o.getStage() == RenewalStage.CLOSED_LOST) {
            throw new BusinessRuleViolationException("Renewal is already resolved");
        }
        return applyIntent(o, intent, o.getLease().getRenter().getUserId());
    }

    /** Called from the authenticated renter endpoint. */
    @Transactional
    public RenewalOpportunity captureIntentFromRenter(UUID opportunityId, RenewalIntent intent, UUID renterUserId) {
        RenewalOpportunity o = opportunityRepository.findById(opportunityId)
                .orElseThrow(() -> new NotFoundException("Opportunity not found"));
        if (!o.getLease().getRenter().getUserId().equals(renterUserId)) {
            throw new NotFoundException("Opportunity not found");
        }
        if (o.getStage() == RenewalStage.CLOSED_WON || o.getStage() == RenewalStage.CLOSED_LOST) {
            throw new BusinessRuleViolationException("Renewal is already resolved");
        }
        return applyIntent(o, intent, renterUserId);
    }

    private RenewalOpportunity applyIntent(RenewalOpportunity o, RenewalIntent intent, UUID createdBy) {
        RenewalIntent prev = o.getIntent();
        o.setIntent(intent);
        o.setStage(RenewalStage.INTENT_CAPTURED);
        o.setIntentCapturedAt(Instant.now());
        opportunityRepository.save(o);

        LeaseInteraction i = new LeaseInteraction();
        i.setTenantId(o.getTenantId());
        i.setOpportunity(o);
        i.setLease(o.getLease());
        i.setType(InteractionType.SYSTEM_INTENT);
        i.setDirection(InteractionDirection.INBOUND);
        i.setOccurredAt(Instant.now());
        String summary = prev == null
                ? "Renter selected: " + intent
                : "Renter changed intent: " + prev + " -> " + intent;
        i.setSummary(summary);
        i.setCreatedBy(createdBy);
        interactionRepository.save(i);

        if (intent == RenewalIntent.RENEW || intent == RenewalIntent.MOVE_OUT) {
            reminderRepository.bulkSkipPendingForOpportunity(
                    o.getId(), ReminderStatus.SKIPPED, "intent captured: " + intent);
        }

        events.publishEvent(new RenewalIntentCapturedEvent(
                o.getId(), o.getLease().getId(), o.getTenantId(), intent));
        return o;
    }
}
