package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.NotificationDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.LegacyNotificationPayload;
import com.datagami.rentaxis.core.notification.PushNotificationEvent;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.DeviceToken;
import com.datagami.rentaxis.domain.entity.Notification;
import com.datagami.rentaxis.domain.entity.PenaltyAssessment;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.repository.DeviceTokenRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final LeaseRepository leaseRepository;
    private final ApplicationEventPublisher events;

    public NotificationService(NotificationRepository notificationRepository,
                                DeviceTokenRepository deviceTokenRepository,
                                LeaseRepository leaseRepository,
                                ApplicationEventPublisher events) {
        this.notificationRepository = notificationRepository;
        this.deviceTokenRepository = deviceTokenRepository;
        this.leaseRepository = leaseRepository;
        this.events = events;
    }

    /**
     * Persist an in-app {@link Notification} row. Shared by {@link #notify} and
     * {@link #notifyInApp} to avoid duplication.
     */
    private void saveNotificationRow(UUID tenantId, UUID userId, String type, String title,
                                     String message, String referenceType, UUID referenceId) {
        Notification n = new Notification();
        n.setTenantId(tenantId);
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setMessage(message);
        n.setReferenceType(referenceType);
        n.setReferenceId(referenceId);
        n.setChannel("IN_APP");
        n.setIsRead(false);
        notificationRepository.save(n);
        log.info("Notification created: {} for user {}", type, userId);
        events.publishEvent(new PushNotificationEvent(
                userId, type, title, message, referenceType, referenceId));
    }

    /**
     * Create in-app notification and publish an EmailEvent for the new
     * outbox-driven email pipeline (replaces the legacy Azure ACS inline
     * send). If the legacy notification {@code type} maps to a known
     * {@link EmailEventType}, an event is published; otherwise the in-app
     * row is the only side effect.
     */
    @Transactional
    public void notify(UUID tenantId, UUID userId, String type, String title, String message,
                       String referenceType, UUID referenceId) {
        saveNotificationRow(tenantId, userId, type, title, message, referenceType, referenceId);

        EmailEventType mapped = mapLegacyType(type);
        if (mapped != null) {
            String dedup = mapped.name() + ":legacy:" + (referenceId != null ? referenceId : userId);
            events.publishEvent(new EmailEvent(
                    this,
                    mapped,
                    tenantId,
                    new LegacyNotificationPayload(userId, title, message, referenceType, referenceId),
                    dedup));
        }
    }

    /**
     * Create an in-app {@link Notification} row WITHOUT publishing an {@link EmailEvent}.
     * Use this at code sites that already publish a structured {@link EmailEvent} directly
     * — calling the full {@link #notify} at those sites would result in a second email
     * (via the legacy mapping) for the same action.
     */
    @Transactional
    public void notifyInApp(UUID tenantId, UUID userId, String type, String title, String message,
                            String referenceType, UUID referenceId) {
        saveNotificationRow(tenantId, userId, type, title, message, referenceType, referenceId);
    }

    /**
     * Variant of {@link #notifyInApp} that runs in its own transaction so a
     * notification-write failure cannot poison the caller's outer transaction
     * (caller catches the exception, but Hibernate has already marked the
     * outer tx rollback-only under default REQUIRED propagation, causing a
     * confusing TransactionSystemException at commit time).
     *
     * Use this from batch sites where one failing notification must not
     * roll back the whole batch — e.g. {@code bulkAttachCheques}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyInAppInNewTx(UUID tenantId, UUID userId, String type, String title, String message,
                                   String referenceType, UUID referenceId) {
        saveNotificationRow(tenantId, userId, type, title, message, referenceType, referenceId);
    }

    private EmailEventType mapLegacyType(String legacy) {
        if (legacy == null) return null;
        return switch (legacy) {
            case "PAYMENT_CLEARED"    -> EmailEventType.CHEQUE_CLEARED;
            case "PAYMENT_BOUNCED"    -> EmailEventType.CHEQUE_BOUNCED;
            case "PAYMENT_FAILED"     -> EmailEventType.ONLINE_PAYMENT_FAILED;
            case "PAYMENT_DUE"        -> EmailEventType.PAYMENT_DUE_REMINDER;
            case "PAYMENT_OVERDUE"    -> EmailEventType.PAYMENT_OVERDUE;
            case "PAYMENT_COLLECTED"  -> EmailEventType.CHEQUE_RECEIVED;
            case "TICKET_ASSIGNED"    -> EmailEventType.TICKET_ASSIGNED;
            case "TICKET_REPLY"       -> EmailEventType.TICKET_REPLY;
            case "TICKET_RESOLVED"    -> EmailEventType.TICKET_RESOLVED;
            case "LEASE_EXPIRING"     -> EmailEventType.LEASE_EXPIRING;
            case "PENALTY_INCURRED"   -> EmailEventType.PENALTY_INCURRED;
            case "PENALTY_CLEARED"    -> EmailEventType.PENALTY_CLEARED;
            case "PENALTY_WAIVED"     -> EmailEventType.PENALTY_WAIVED;
            case "MEETING_REQUESTED"  -> EmailEventType.MEETING_REQUESTED;
            case "MEETING_APPROVED"   -> EmailEventType.MEETING_APPROVED;
            case "MEETING_CANCELLED"  -> EmailEventType.MEETING_CANCELLED;
            case "MEETING_COMPLETED"  -> EmailEventType.MEETING_COMPLETED;
            case "MEETING_NO_SHOW"    -> EmailEventType.MEETING_NO_SHOW;
            case "TENANT_PROVISIONED" -> EmailEventType.TENANT_PROVISIONED;
            default -> null;
        };
    }

    /**
     * A penalty has actually been charged (spec §7.3).
     *
     * <p><b>Fired from {@code PenaltyAssessmentService.approve} and nowhere else.</b>
     * A {@code PROPOSED} assessment is finance deliberating about whether to fine
     * this renter, and some of those end up waived; telling the renter about one
     * would turn "we are thinking about it" into "you owe this". Only the approval
     * is a fact about their balance.</p>
     *
     * <p><b>Its own transaction, and no catch inside it.</b> A failed notification
     * row must not take a posted penalty down with it, and the obvious shape — call
     * {@code notify} and swallow what it throws — does the opposite: {@code notify}
     * is a self-invocation, so it bypasses the proxy and joins the approval's
     * transaction, a failed insert marks that transaction rollback-only, and the
     * approval then dies at commit with an {@code UnexpectedRollbackException}.
     *
     * <p>So the propagation lives here, on a method {@code PenaltyAssessmentService}
     * calls across the bean boundary where the proxy actually applies, and the
     * failure is allowed to escape: the inner transaction rolls back cleanly and
     * <em>the caller</em> catches. This is the same mechanism
     * {@code ChequeService.notifyRenter} reaches through {@code notifyInAppInNewTx},
     * arranged so the catch sits outside the new transaction rather than inside
     * it.</p>
     *
     * <p>The in-app row only. The structured {@code PENALTY_INCURRED} email is
     * published by the outbox pipeline, and going through {@code notify}'s legacy
     * mapping as well would send the renter a second copy.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendPenaltyIncurred(PenaltyAssessment assessment) {
        if (assessment == null) {
            return;
        }
        Renter renter = assessment.getRenter();
        UUID renterUserId = renter != null ? renter.getUserId() : null;
        if (renterUserId == null) {
            log.warn("PENALTY_INCURRED notification skipped — no renter user id for penalty {}",
                    assessment.getId());
            return;
        }
        UUID tenantId = assessment.getTenantId() != null
                ? assessment.getTenantId() : TenantContextHolder.getTenantId();
        Cheque cheque = assessment.getCheque();
        String about = cheque != null
                ? " for instalment #" + cheque.getSeqNo()
                : "";
        String body = "A " + assessment.getAmount() + " AED penalty has been added" + about
                + " (" + assessment.getReason().label()
                + "). Please clear it via bank transfer, cheque, or cash.";
        saveNotificationRow(tenantId, renterUserId, "PENALTY_INCURRED", "Penalty Incurred",
                body, "PENALTY", assessment.getId());
    }

    // sendPenaltyCleared / sendPenaltyWaived went with the v1 penalty tables
    // (changeset 84). Neither has a v2 counterpart yet: a waiver is an internal
    // decision the renter was never told about anyway, and a fine is now collected
    // through its register row, so "your penalty cleared" is the same event as the
    // receipt for that row rather than a message of its own.

    @Transactional(readOnly = true)
    public List<NotificationDTO> getNotifications(UUID userId, int page, int size, boolean unreadOnly) {
        return notificationRepository.findAllByUserIdUnfiltered(userId)
                .stream()
                .filter(n -> !unreadOnly || !Boolean.TRUE.equals(n.getIsRead()))
                .skip((long) page * size)
                .limit(size)
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(UUID userId) {
        return notificationRepository.countUnreadByUserIdUnfiltered(userId);
    }

    @Transactional
    public void markAsRead(UUID notificationId, UUID userId) {
        // Scope the lookup to the authenticated user. Notification ids are UUIDs,
        // but they are still object identifiers and must not be treated as authority.
        // Use the unfiltered query because SUPER_ADMIN notifications can have a null
        // tenant_id and therefore sit outside Hibernate's tenant filter.
        Notification notification = notificationRepository
                .findByIdAndUserIdUnfiltered(notificationId, userId)
                .orElseThrow(() -> new NotFoundException("Notification not found"));
        notification.setIsRead(true);
        notificationRepository.save(notification);
    }

    @Transactional
    public void markAllAsRead(UUID userId) {
        notificationRepository.findUnreadByUserIdUnfiltered(userId).forEach(n -> {
            n.setIsRead(true);
            notificationRepository.save(n);
        });
    }

    @Transactional
    public void registerDevice(UUID userId, String token, String platform) {
        deviceTokenRepository.deleteByUserIdAndToken(userId, token);
        DeviceToken dt = new DeviceToken();
        dt.setUserId(userId);
        dt.setToken(token);
        dt.setPlatform(platform);
        deviceTokenRepository.save(dt);
    }

    private NotificationDTO mapToDTO(Notification n) {
        NotificationDTO dto = new NotificationDTO();
        dto.setId(n.getId());
        dto.setTenantId(n.getTenantId());
        dto.setUserId(n.getUserId());
        dto.setType(n.getType());
        dto.setTitle(n.getTitle());
        dto.setMessage(n.getMessage());
        dto.setReferenceType(n.getReferenceType());
        dto.setReferenceId(n.getReferenceId());
        dto.setChannel(n.getChannel());
        dto.setIsRead(n.getIsRead());
        dto.setSentAt(n.getSentAt());
        dto.setCreatedAt(n.getCreatedAt());
        return dto;
    }
}
