package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.core.notification.NotificationMessage;
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.RenewalIntentCapturedEvent;
import com.datagami.rentaxis.core.email.event.payload.RenewalIntentCapturedPayload;
import com.datagami.rentaxis.core.service.NotificationService;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class RenewalIntentCapturedListener {

    private final LeaseRepository leaseRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher events;

    @EventListener
    public void onIntentCaptured(RenewalIntentCapturedEvent ev) {
        var lease = leaseRepository.findById(ev.leaseId()).orElse(null);
        if (lease == null) {
            log.warn("Lease {} not found for intent-captured event", ev.leaseId());
            return;
        }
        String renterName = lease.getRenter().getNameEn();
        String unitNumber = lease.getUnit().getUnitNumber();
        String propertyName = lease.getUnit().getProperty().getNameEn();

        List<User> admins = userRepository.findByTenantIdAndRole(ev.tenantId(), UserRole.TENANT_ADMIN);
        for (User admin : admins) {
            try {
                notificationService.notifyInAppInNewTx(
                        ev.tenantId(), admin.getId(),
                        "RENEWAL_INTENT",
                        "Renter responded to renewal reminder",
                        renterName + " selected " + ev.intent() + " for lease " + unitNumber,
                        "LEASE", ev.leaseId(),
                        NotificationMessage.of("RENEWAL_INTENT", "renterName", renterName,
                                "intent", ev.intent(), "unit", unitNumber, "property", propertyName));
            } catch (Exception e) {
                log.warn("Failed to notify admin {} of intent capture: {}", admin.getId(), e.getMessage());
            }
        }

        events.publishEvent(new EmailEvent(this,
                EmailEventType.RENEWAL_INTENT_CAPTURED,
                ev.tenantId(),
                new RenewalIntentCapturedPayload(
                        ev.opportunityId(), ev.leaseId(),
                        ev.tenantId(), null,
                        renterName, unitNumber, propertyName,
                        ev.intent().name()),
                "RENEWAL_INTENT_CAPTURED:" + ev.opportunityId()));
    }
}
