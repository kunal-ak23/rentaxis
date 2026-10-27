package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.LegacyNotificationPayload;
import com.datagami.rentaxis.core.event.PromoAdReviewedEvent;
import com.datagami.rentaxis.core.event.PromoAdSubmittedEvent;
import com.datagami.rentaxis.core.notification.NotificationMessage;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Tells people about promoter ads (promoter plan, "Notifications"): every active
 * TENANT_ADMIN of the organisation when a promoter sends an ad for approval, and the
 * promoter when an admin approves or rejects it (with the reason).
 *
 * <p>The {@link BookingNotificationService} pattern: AFTER_COMMIT, so nothing is
 * said about a write that rolled back, and one {@code notifyInAppInNewTx} per
 * recipient, so one failing row cannot take the others down.
 *
 * <p>Email goes through the outbox like MEETING_*: an {@link EmailEvent} carrying a
 * {@link LegacyNotificationPayload} (rendered with the same English title and
 * text), per recipient. Not through {@code NotificationService.notify}: its dedup
 * key is the reference id alone, and an ad is submitted (and approved) more than
 * once in its life — the second submission's mail would be dropped as a duplicate
 * of the first. Here the key carries the submission or review instant. The
 * organisation's EMAIL_NOTIFICATIONS switch applies as for any notification mail.
 *
 * <p>The in-app rows carry a structured message ({@code PROMO_AD_SUBMITTED},
 * {@code PROMO_AD_APPROVED}, {@code PROMO_AD_REJECTED}) for the web's Arabic copy.
 */
@Service
@Slf4j
public class PromoterNotificationService {

    static final String REFERENCE_TYPE = "PROMO_AD";

    private final PromoAdRepository adRepository;
    private final PromoBusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher events;

    public PromoterNotificationService(PromoAdRepository adRepository,
                                       PromoBusinessRepository businessRepository,
                                       UserRepository userRepository,
                                       NotificationService notificationService,
                                       ApplicationEventPublisher events) {
        this.adRepository = adRepository;
        this.businessRepository = businessRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.events = events;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSubmitted(PromoAdSubmittedEvent event) {
        Optional<PromoAd> found = ad(event.tenantId(), event.adId());
        if (found.isEmpty()) {
            return;
        }
        PromoAd ad = found.get();
        String title = adTitle(ad);
        String business = businessName(event.tenantId(), ad);
        String promoterName = userRepository.findByTenantIdAndIdIn(event.tenantId(), List.of(event.promoterUserId()))
                .stream().findFirst().map(User::getName).orElse("A promoter");
        String heading = "Ad awaiting approval";
        String text = promoterName + " sent \"" + title + "\" (" + business + ") for approval.";
        NotificationMessage structured = NotificationMessage.of("PROMO_AD_SUBMITTED",
                "promoterName", promoterName, "adTitle", title, "businessName", business);
        Instant at = ad.getSubmittedAt() != null ? ad.getSubmittedAt() : Instant.now();

        List<User> admins = userRepository.findByTenantIdAndRole(event.tenantId(), UserRole.TENANT_ADMIN).stream()
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .toList();
        for (User admin : admins) {
            tell(event.tenantId(), admin.getId(), EmailEventType.PROMO_AD_SUBMITTED, heading, text, ad.getId(),
                    structured, at);
        }
        log.info("PromoAdSubmittedEvent: told {} admins about ad {}", admins.size(), ad.getId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReviewed(PromoAdReviewedEvent event) {
        Optional<PromoAd> found = ad(event.tenantId(), event.adId());
        if (found.isEmpty() || event.promoterUserId() == null) {
            return;
        }
        PromoAd ad = found.get();
        boolean approved = event.status() == PromoApprovalStatus.APPROVED;
        String title = adTitle(ad);
        String business = businessName(event.tenantId(), ad);
        String heading = approved ? "Ad approved" : "Ad rejected";
        String text = approved
                ? "Your ad \"" + title + "\" (" + business + ") was approved and runs from its start date."
                : "Your ad \"" + title + "\" (" + business + ") was rejected: " + event.reason();
        NotificationMessage structured = approved
                ? NotificationMessage.of("PROMO_AD_APPROVED", "adTitle", title, "businessName", business)
                : NotificationMessage.of("PROMO_AD_REJECTED", "adTitle", title, "businessName", business,
                        "reason", event.reason());
        Instant at = ad.getReviewedAt() != null ? ad.getReviewedAt() : Instant.now();
        tell(event.tenantId(), event.promoterUserId(),
                approved ? EmailEventType.PROMO_AD_APPROVED : EmailEventType.PROMO_AD_REJECTED,
                heading, text, ad.getId(), structured, at);
    }

    /** One recipient: the in-app row in its own transaction, then the email through the outbox. */
    private void tell(UUID tenantId, UUID userId, EmailEventType type, String heading, String text, UUID adId,
                      NotificationMessage structured, Instant at) {
        try {
            notificationService.notifyInAppInNewTx(tenantId, userId, type.name(), heading, text,
                    REFERENCE_TYPE, adId, structured);
        } catch (Exception ex) {
            log.error("Failed to notify user {} about promo ad {} - {}", userId, adId, ex.getMessage());
        }
        events.publishEvent(new EmailEvent(this, type, tenantId,
                new LegacyNotificationPayload(userId, heading, text, REFERENCE_TYPE, adId),
                type.name() + ":" + adId + ":" + at.toEpochMilli()));
    }

    private Optional<PromoAd> ad(UUID tenantId, UUID adId) {
        return adRepository.findById(adId).filter(a -> Objects.equals(a.getTenantId(), tenantId));
    }

    private static String adTitle(PromoAd ad) {
        return ad.getTitleEn() != null ? ad.getTitleEn() : ad.getTitleAr();
    }

    private String businessName(UUID tenantId, PromoAd ad) {
        return businessRepository.findByTenantIdAndIdIn(tenantId, List.of(ad.getBusinessId())).stream()
                .findFirst().map(PromoBusiness::getNameEn).orElse("");
    }
}
