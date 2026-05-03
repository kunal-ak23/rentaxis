package com.datagami.rentaxis.core.service;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.communication.email.models.EmailMessage;
import com.datagami.rentaxis.api.dto.NotificationDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.DeviceToken;
import com.datagami.rentaxis.domain.entity.Notification;
import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.PenaltyPayment;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.repository.DeviceTokenRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.NotificationRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;
    private final LeaseRepository leaseRepository;

    @Value("${AZURE_COMMUNICATION_CONNECTION_STRING:}")
    private String azureCommConnectionString;

    @Value("${AZURE_EMAIL_SENDER:}")
    private String emailSender;

    @Value("${NEXT_PUBLIC_API_URL:https://rentaxis.uaenorth.cloudapp.azure.com}")
    private String portalBaseUrl;

    public NotificationService(NotificationRepository notificationRepository,
                                DeviceTokenRepository deviceTokenRepository,
                                UserRepository userRepository,
                                LeaseRepository leaseRepository) {
        this.notificationRepository = notificationRepository;
        this.deviceTokenRepository = deviceTokenRepository;
        this.userRepository = userRepository;
        this.leaseRepository = leaseRepository;
    }

    /**
     * Create in-app notification and send email if configured.
     */
    @Transactional
    public void notify(UUID tenantId, UUID userId, String type, String title, String message,
                       String referenceType, UUID referenceId) {
        // 1. Save in-app notification
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

        // 2. Send email (async, non-blocking)
        sendEmailAsync(userId, type, title, message, referenceType, referenceId);
    }

    /**
     * Send email via Azure Communication Services with HTML template.
     */
    private void sendEmailAsync(UUID userId, String type, String title, String messageText,
                                 String referenceType, UUID referenceId) {
        if (azureCommConnectionString == null || azureCommConnectionString.isBlank()) {
            return;
        }
        if (emailSender == null || emailSender.isBlank()) {
            return;
        }

        try {
            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty() || userOpt.get().getEmail() == null) {
                return;
            }
            String recipientEmail = userOpt.get().getEmail();
            String recipientName = userOpt.get().getName();

            // Build HTML email body
            String htmlBody = buildEmailHtml(type, title, messageText, recipientName, referenceType, referenceId);

            log.info("Sending email to {} | subject: '{}' | type: {}", recipientEmail, title, type);

            EmailClient emailClient = new EmailClientBuilder()
                    .connectionString(azureCommConnectionString)
                    .buildClient();

            EmailMessage emailMessage = new EmailMessage()
                    .setSenderAddress(emailSender)
                    .setToRecipients(recipientEmail)
                    .setSubject("RentAxis: " + title)
                    .setBodyHtml(htmlBody)
                    .setBodyPlainText(messageText);

            var poller = emailClient.beginSend(emailMessage);
            log.info("Email sent to {} — status: {}", recipientEmail, poller.poll().getStatus());
        } catch (Exception e) {
            log.error("Failed to send email to user {} — {}: {}", userId, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private String buildEmailHtml(String type, String title, String message, String recipientName,
                                   String referenceType, UUID referenceId) {
        try {
            org.springframework.core.io.ClassPathResource resource =
                    new org.springframework.core.io.ClassPathResource("templates/email-template.html");
            String template = new String(resource.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

            // Greeting
            String greeting = recipientName != null ? "Hi " + recipientName + "," : "Hello,";

            // Action button based on type
            String actionButton = "";
            String portalUrl = portalBaseUrl;
            if (referenceType != null && referenceId != null) {
                String link = switch (referenceType) {
                    case "TICKET" -> portalUrl + "/en/dashboard/tickets/" + referenceId;
                    case "LEASE" -> portalUrl + "/en/dashboard/leases/" + referenceId;
                    case "PAYMENT" -> portalUrl + "/en/dashboard/finance/payments";
                    case "MEETING" -> portalUrl + "/en/dashboard/meetings/" + referenceId;
                    default -> portalUrl + "/en/dashboard";
                };
                String buttonLabel = switch (type) {
                    case "TICKET_ASSIGNED" -> "View Ticket";
                    case "TICKET_REPLY" -> "View Conversation";
                    case "TICKET_RESOLVED" -> "View Ticket & Share OTP";
                    case "PAYMENT_CLEARED", "PAYMENT_COLLECTED" -> "View Payment";
                    case "PAYMENT_DUE", "PAYMENT_OVERDUE", "PAYMENT_FAILED", "PAYMENT_BOUNCED" -> "Make Payment";
                    case "LEASE_EXPIRING" -> "View Lease";
                    case "MEETING_REQUESTED" -> "View Meeting Request";
                    case "MEETING_APPROVED", "MEETING_CANCELLED", "MEETING_COMPLETED", "MEETING_NO_SHOW" -> "View Meeting";
                    default -> "Open RentAxis";
                };
                actionButton = "<table cellpadding=\"0\" cellspacing=\"0\" style=\"margin:16px 0;\"><tr><td>"
                        + "<a href=\"" + link + "\" style=\"display:inline-block;background-color:#0F766E;color:#ffffff;"
                        + "padding:12px 24px;border-radius:8px;text-decoration:none;font-size:13px;font-weight:600;\">"
                        + buttonLabel + "</a></td></tr></table>";
            }

            // Details section based on type
            String details = switch (type) {
                case "TICKET_ASSIGNED" -> "<p style=\"margin:0;color:#475569;font-size:12px;\">A maintenance ticket has been assigned to you. Please review and take action.</p>";
                case "TICKET_REPLY" -> "<p style=\"margin:0;color:#475569;font-size:12px;\">Someone replied to a ticket you're involved in. Check the conversation for updates.</p>";
                case "TICKET_RESOLVED" -> "<p style=\"margin:0;color:#475569;font-size:12px;\">Your ticket has been resolved. If you're satisfied, please share the OTP with your property manager to close it.</p>";
                case "PAYMENT_CLEARED" -> "<p style=\"margin:0;color:#475569;font-size:12px;\">Your payment has been cleared and a receipt is now available for download.</p>";
                case "PAYMENT_COLLECTED" -> "<p style=\"margin:0;color:#475569;font-size:12px;\">Your cheque has been collected and is being processed. You will be notified once it clears.</p>";
                case "PAYMENT_FAILED" -> "<p style=\"margin:0;color:#DC2626;font-size:12px;font-weight:600;\">Your online payment could not be verified. Please try again or contact support.</p>";
                case "PAYMENT_BOUNCED" -> "<p style=\"margin:0;color:#DC2626;font-size:12px;font-weight:600;\">Your cheque has bounced. Please arrange a replacement cheque immediately to avoid penalties.</p>";
                case "PAYMENT_DUE" -> "<p style=\"margin:0;color:#D97706;font-size:12px;font-weight:600;\">Your rent payment is due soon. Please ensure timely payment to avoid late fees.</p>";
                case "PAYMENT_OVERDUE" -> "<p style=\"margin:0;color:#DC2626;font-size:12px;font-weight:600;\">Your rent payment is overdue. Please make the payment immediately.</p>";
                case "LEASE_EXPIRING" -> "<p style=\"margin:0;color:#D97706;font-size:12px;\">A lease in your portfolio is expiring soon. Review and take action if renewal is needed.</p>";
                case "TENANT_PROVISIONED" -> "<p style=\"margin:0;color:#475569;font-size:12px;\">A new organization has been created on the platform.</p>";
                case "MEETING_REQUESTED" -> "<p style=\"margin:0;color:#475569;font-size:12px;\">A new meeting has been requested. Please review and approve or decline.</p>";
                case "MEETING_APPROVED" -> "<p style=\"margin:0;color:#475569;font-size:12px;\">Your meeting request has been approved. See you there!</p>";
                case "MEETING_CANCELLED" -> "<p style=\"margin:0;color:#D97706;font-size:12px;\">A meeting has been cancelled. Check the details for more information.</p>";
                case "MEETING_COMPLETED" -> "<p style=\"margin:0;color:#475569;font-size:12px;\">Your meeting has been completed successfully.</p>";
                case "MEETING_NO_SHOW" -> "<p style=\"margin:0;color:#DC2626;font-size:12px;\">You were marked as a no-show for a scheduled meeting.</p>";
                default -> "<p style=\"margin:0;color:#475569;font-size:12px;\">Please log in to RentAxis for more details.</p>";
            };

            return template
                    .replace("{{TITLE}}", greeting + "<br><br>" + safe(title))
                    .replace("{{MESSAGE}}", safe(message))
                    .replace("{{ACTION_BUTTON}}", actionButton)
                    .replace("{{DETAILS}}", details);
        } catch (Exception e) {
            log.warn("Failed to build HTML email, falling back to plain text: {}", e.getMessage());
            return "<html><body><h2>" + safe(title) + "</h2><p>" + safe(message) + "</p></body></html>";
        }
    }

    private String safe(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
