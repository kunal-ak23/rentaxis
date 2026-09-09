package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.LeaseRenewalReminderPayload;
import com.datagami.rentaxis.core.service.NotificationService;
import com.datagami.rentaxis.domain.entity.LeaseReminder;
import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.entity.enums.ReminderChannel;
import com.datagami.rentaxis.domain.entity.enums.ReminderStatus;
import com.datagami.rentaxis.domain.repository.LeaseReminderRepository;
import com.datagami.rentaxis.domain.repository.RenewalOpportunityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RenewalReminderService {

    public static final List<Short> SLOTS = List.of((short) 90, (short) 60, (short) 30);
    public static final int MAX_ATTEMPTS = 3;

    private final RenewalOpportunityRepository opportunityRepository;
    private final LeaseReminderRepository reminderRepository;
    private final ApplicationEventPublisher events;
    private final NotificationService notificationService;
    private final RenewalTokenService tokenService;
    private final com.datagami.rentaxis.core.service.TenantFeatureService tenantFeatureService;

    @Value("${app.renewal.portal-base-url:https://app.rentaxis.ae}")
    private String portalBaseUrl;

    @Transactional
    public void fireRemindersForOpportunity(UUID opportunityId, LocalDate today) {
        RenewalOpportunity o = opportunityRepository.findById(opportunityId).orElse(null);
        if (o == null) return;

        if (o.getIntent() == RenewalIntent.RENEW || o.getIntent() == RenewalIntent.MOVE_OUT) {
            reminderRepository.bulkSkipPendingForOpportunity(
                    o.getId(), ReminderStatus.SKIPPED, "intent captured: " + o.getIntent());
            return;
        }

        long daysRemaining = ChronoUnit.DAYS.between(today, o.getLease().getEndDate());

        for (short slot : SLOTS) {
            for (ReminderChannel ch : ReminderChannel.values()) {
                processSlot(o, slot, ch, daysRemaining);
            }
        }
    }

    private void processSlot(RenewalOpportunity o, short slot, ReminderChannel ch, long daysRemaining) {
        // Check first to avoid relying on the DataIntegrityViolationException path,
        // which would otherwise mark the outer @Transactional as rollback-only.
        LeaseReminder r = reminderRepository.findByOpportunityId(o.getId()).stream()
                .filter(x -> x.getSlot() == slot && x.getChannel() == ch)
                .findFirst()
                .orElse(null);

        if (r == null) {
            try {
                r = new LeaseReminder();
                r.setOpportunity(o);
                r.setTenantId(o.getTenantId());
                r.setSlot(slot);
                r.setChannel(ch);
                r.setStatus(ReminderStatus.PENDING);
                r = reminderRepository.save(r);
            } catch (DataIntegrityViolationException dup) {
                r = reminderRepository.findByOpportunityId(o.getId()).stream()
                        .filter(x -> x.getSlot() == slot && x.getChannel() == ch)
                        .findFirst().orElseThrow();
            }
        }

        if (r.getStatus() != ReminderStatus.PENDING) return;

        long lowerExclusive = slot == 30 ? 0 : (slot == 60 ? 30 : 60);
        long upperInclusive = slot;

        if (daysRemaining <= lowerExclusive) {
            r.setStatus(ReminderStatus.SKIPPED);
            r.setLastError("window passed at open");
            reminderRepository.save(r);
            return;
        }
        if (daysRemaining > upperInclusive) {
            return;
        }

        // Do not claim a reminder was sent that the dispatcher is going to drop.
        //
        // publishEvent hands off to EmailDispatcher, which is an AFTER_COMMIT
        // listener in a REQUIRES_NEW transaction — it cannot report anything
        // back, so the SENT below is written unconditionally. Its first act is
        // to return early when the tenant's EMAIL_NOTIFICATIONS flag is off, and
        // that flag defaults to false independently of LEASE_RENEWALS. A tenant
        // with renewals enabled and email not yet enabled — the default state
        // after turning renewals on — therefore had all three reminders
        // (90/60/30) recorded as SENT with a timestamp and never delivered.
        //
        // Because the row left PENDING, the daily scheduler never retried it and
        // the reminder was permanently lost, while the renter portal and the PM
        // both displayed "SENT" with a date. Leaving the row PENDING here means
        // the next daily run picks it up, so enabling email later delivers the
        // reminders that are still inside their window instead of stranding them.
        if (ch == ReminderChannel.EMAIL
                && !tenantFeatureService.isEnabled(o.getTenantId(), TenantFeature.EMAIL_NOTIFICATIONS)) {
            r.setLastError("Email notifications are disabled for this organisation; reminder not sent.");
            reminderRepository.save(r);
            log.info("renewal.reminder.deferred reason=email_feature_disabled tenant_id={} opp={} slot={}",
                    o.getTenantId(), o.getId(), slot);
            return;
        }

        try {
            if (ch == ReminderChannel.EMAIL) {
                String renew = tokenService.sign(o.getId(), RenewalIntent.RENEW, o.getLease().getEndDate());
                String moveOut = tokenService.sign(o.getId(), RenewalIntent.MOVE_OUT, o.getLease().getEndDate());
                String discuss = tokenService.sign(o.getId(), RenewalIntent.DISCUSS, o.getLease().getEndDate());

                String unitNumber = o.getLease().getUnit() != null
                        ? o.getLease().getUnit().getUnitNumber() : "";
                String propertyNameEn = (o.getLease().getUnit() != null && o.getLease().getUnit().getProperty() != null)
                        ? o.getLease().getUnit().getProperty().getNameEn() : "";

                events.publishEvent(new EmailEvent(this,
                        EmailEventType.LEASE_RENEWAL_REMINDER,
                        o.getTenantId(),
                        new LeaseRenewalReminderPayload(
                                o.getId(),
                                o.getLease().getId(),
                                o.getLease().getRenter().getUserId(),
                                o.getLease().getEndDate().toString(),
                                (int) slot,
                                renew, moveOut, discuss,
                                portalBaseUrl,
                                unitNumber,
                                propertyNameEn),
                        "LEASE_RENEWAL_REMINDER:" + o.getId() + ":" + slot));
            } else {
                UUID renterUserId = o.getLease().getRenter().getUserId();
                if (renterUserId != null) {
                    // Copy must not instruct a tap action: the renter mobile app has no
                    // renewal screen, so we point users at the renter web portal instead
                    // (same destination as the email reminder links).
                    notificationService.notifyInAppInNewTx(
                            o.getTenantId(), renterUserId,
                            "LEASE_RENEWAL_REMINDER",
                            "Your lease ends in " + slot + " days",
                            "Lease ends " + o.getLease().getEndDate()
                                    + ". Choose your renewal option in the renter web portal.",
                            "LEASE", o.getLease().getId());
                }
            }
            r.setStatus(ReminderStatus.SENT);
            r.setSentAt(Instant.now());
            reminderRepository.save(r);
        } catch (Exception e) {
            r.setAttemptCount(r.getAttemptCount() + 1);
            r.setLastError(e.getMessage());
            if (r.getAttemptCount() >= MAX_ATTEMPTS) {
                r.setStatus(ReminderStatus.FAILED);
            }
            reminderRepository.save(r);
            log.warn("Reminder send failed for opp {} slot {} channel {}: {} (attempt {})", o.getId(), slot, ch, e.getMessage(), r.getAttemptCount());
        }
    }
}
