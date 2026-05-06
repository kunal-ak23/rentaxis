package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.NotificationDTO;
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.LegacyNotificationPayload;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.DeviceToken;
import com.datagami.rentaxis.domain.entity.Notification;
import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.PenaltyPayment;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.repository.DeviceTokenRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
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
     * Cheque-failure penalty was just incurred — fired alongside PAYMENT_BOUNCED
     * by {@code PaymentScheduleService.markFailed}. Body restates the amount,
     * reason, and installment so the renter knows exactly what they owe and
     * why. Wrapped in a try/catch by the caller — best-effort.
     */
    public void sendPenaltyIncurred(PaymentSchedule schedule, ChequeFailureReason reason,
                                     BigDecimal fineAmount, UUID penaltyId) {
        UUID renterUserId = schedule.getLease() != null && schedule.getLease().getRenter() != null
                ? schedule.getLease().getRenter().getUserId()
                : null;
        if (renterUserId == null) {
            log.warn("PENALTY_INCURRED notification skipped — no renter user id for penalty {}", penaltyId);
            return;
        }
        UUID tenantId = TenantContextHolder.getTenantId();
        String body = "A " + fineAmount + " AED penalty has been added for installment #"
                + schedule.getInstallmentNumber() + " (" + reason
                + "). Please clear it via bank transfer, cheque, or cash.";
        try {
            notify(tenantId, renterUserId, "PENALTY_INCURRED", "Penalty Incurred",
                    body, "PENALTY", penaltyId);
        } catch (Exception e) {
            log.warn("Failed to send PENALTY_INCURRED notification for penalty {}: {}", penaltyId, e.getMessage());
        }
    }

    /**
     * Penalty has been fully cleared by a payment receipt (bank transfer, cheque,
     * or cash). The body confirms the receipt + amount so the renter has a clear
     * paper trail in their notification feed / email.
     */
    public void sendPenaltyCleared(PaymentPenalty penalty, PenaltyPayment receipt) {
        UUID tenantId = penalty.getTenantId() != null
                ? penalty.getTenantId()
                : TenantContextHolder.getTenantId();
        UUID renterUserId = leaseRepository.findById(penalty.getLeaseId())
                .map(l -> l.getRenter() != null ? l.getRenter().getUserId() : null)
                .orElse(null);
        if (renterUserId == null) {
            log.warn("PENALTY_CLEARED notification skipped — no renter user id for penalty {}", penalty.getId());
            return;
        }
        String body = "Your " + penalty.getPenaltyAmount() + " AED penalty has been cleared after receipt of "
                + receipt.getAmount() + " AED via " + receipt.getPaymentMethod() + ".";
        try {
            notify(tenantId, renterUserId, "PENALTY_CLEARED", "Penalty Cleared",
                    body, "PENALTY", penalty.getId());
        } catch (Exception e) {
            log.warn("Failed to send PENALTY_CLEARED notification for penalty {}: {}", penalty.getId(), e.getMessage());
        }
    }

    /**
     * Penalty has been waived by the property manager. Body explains the
     * goodwill / reason so the renter understands why the fine is gone.
     */
    public void sendPenaltyWaived(PaymentPenalty penalty, String reason) {
        UUID tenantId = penalty.getTenantId() != null
                ? penalty.getTenantId()
                : TenantContextHolder.getTenantId();
        UUID renterUserId = leaseRepository.findById(penalty.getLeaseId())
                .map(l -> l.getRenter() != null ? l.getRenter().getUserId() : null)
                .orElse(null);
        if (renterUserId == null) {
            log.warn("PENALTY_WAIVED notification skipped — no renter user id for penalty {}", penalty.getId());
            return;
        }
        String body = "Your " + penalty.getPenaltyAmount() + " AED penalty has been waived. Reason: " + reason + ".";
        try {
            notify(tenantId, renterUserId, "PENALTY_WAIVED", "Penalty Waived",
                    body, "PENALTY", penalty.getId());
        } catch (Exception e) {
            log.warn("Failed to send PENALTY_WAIVED notification for penalty {}: {}", penalty.getId(), e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationDTO> getNotifications(UUID userId, int page, int size) {
        return notificationRepository.findAllByUserIdUnfiltered(userId)
                .stream()
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
    public void markAsRead(UUID notificationId) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            n.setIsRead(true);
            notificationRepository.save(n);
        });
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
