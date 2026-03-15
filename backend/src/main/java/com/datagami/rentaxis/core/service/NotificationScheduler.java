package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationScheduler {

    private final PaymentScheduleRepository paymentScheduleRepository;
    private final LeaseRepository leaseRepository;
    private final NotificationService notificationService;
    private final RentCollectionSettingsRepository rentSettingsRepository;

    @Scheduled(cron = "0 0 8 * * *") // 8 AM daily
    @Transactional(readOnly = true)
    public void sendDailyNotifications() {
        log.info("Running daily notification check...");
        checkPaymentDueReminders();
        checkOverduePayments();
        checkExpiringLeases();
        log.info("Daily notification check complete.");
    }

    private void checkPaymentDueReminders() {
        log.info("Checking payment due reminders...");
        LocalDate today = LocalDate.now();

        // Get all rent collection settings to find reminder day configurations
        List<RentCollectionSettings> allSettings = rentSettingsRepository.findAll();

        // Collect all unique reminder day offsets across all properties
        Set<Integer> reminderDays = new java.util.HashSet<>();
        for (RentCollectionSettings settings : allSettings) {
            String days = settings.getPaymentReminderDays();
            if (days != null && !days.isBlank()) {
                Arrays.stream(days.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .forEach(s -> {
                            try {
                                reminderDays.add(Integer.parseInt(s));
                            } catch (NumberFormatException e) {
                                // skip invalid entries
                            }
                        });
            }
        }

        if (reminderDays.isEmpty()) {
            reminderDays.addAll(List.of(7, 3, 1)); // defaults
        }

        // For each reminder day offset, check pending payments due on that date
        for (int daysBefore : reminderDays) {
            LocalDate targetDate = today.plusDays(daysBefore);
            List<PaymentSchedule> payments = paymentScheduleRepository.findAll().stream()
                    .filter(ps -> ps.getStatus() == PaymentStatus.PENDING)
                    .filter(ps -> ps.getDueDate().equals(targetDate))
                    .toList();

            for (PaymentSchedule payment : payments) {
                try {
                    if (payment.getLease() != null && payment.getLease().getRenter() != null
                            && payment.getLease().getRenter().getUserId() != null) {
                        notificationService.notify(
                                payment.getTenantId(),
                                payment.getLease().getRenter().getUserId(),
                                "PAYMENT_DUE",
                                "Payment Due Reminder",
                                "Installment #" + payment.getInstallmentNumber() + " of "
                                        + payment.getAmount() + " is due in " + daysBefore + " day(s).",
                                "PAYMENT",
                                payment.getId());
                    }
                } catch (Exception e) {
                    log.warn("Failed to send payment reminder for payment {}", payment.getId(), e);
                }
            }

            log.info("Sent {} payment due reminders for {} days before due date", payments.size(), daysBefore);
        }
    }

    private void checkOverduePayments() {
        log.info("Checking overdue payments...");
        LocalDate today = LocalDate.now();

        List<PaymentSchedule> overduePayments = paymentScheduleRepository.findOverdue(today);

        for (PaymentSchedule payment : overduePayments) {
            try {
                if (payment.getLease() != null && payment.getLease().getRenter() != null
                        && payment.getLease().getRenter().getUserId() != null) {
                    long daysOverdue = java.time.temporal.ChronoUnit.DAYS.between(payment.getDueDate(), today);
                    notificationService.notify(
                            payment.getTenantId(),
                            payment.getLease().getRenter().getUserId(),
                            "PAYMENT_OVERDUE",
                            "Payment Overdue",
                            "Installment #" + payment.getInstallmentNumber() + " of "
                                    + payment.getAmount() + " is " + daysOverdue + " day(s) overdue.",
                            "PAYMENT",
                            payment.getId());
                }
            } catch (Exception e) {
                log.warn("Failed to send overdue notification for payment {}", payment.getId(), e);
            }
        }

        log.info("Sent {} overdue payment notifications", overduePayments.size());
    }

    private void checkExpiringLeases() {
        log.info("Checking expiring leases...");
        LocalDate today = LocalDate.now();
        int[] daysBeforeExpiry = {90, 60, 30};

        for (int daysBefore : daysBeforeExpiry) {
            LocalDate expiryDate = today.plusDays(daysBefore);

            // Find active leases ending on the target date
            List<Lease> expiringLeases = leaseRepository.findAll().stream()
                    .filter(l -> l.getStatus() == LeaseStatus.ACTIVE || l.getStatus() == LeaseStatus.NOTICE_GIVEN)
                    .filter(l -> l.getEndDate().equals(expiryDate))
                    .toList();

            for (Lease lease : expiringLeases) {
                try {
                    // Notify the renter
                    if (lease.getRenter() != null && lease.getRenter().getUserId() != null) {
                        notificationService.notify(
                                lease.getTenantId(),
                                lease.getRenter().getUserId(),
                                "LEASE_EXPIRING",
                                "Lease Expiring Soon",
                                "Your lease expires in " + daysBefore + " days on " + lease.getEndDate() + ".",
                                "LEASE",
                                lease.getId());
                    }
                } catch (Exception e) {
                    log.warn("Failed to send lease expiry notification for lease {}", lease.getId(), e);
                }
            }

            log.info("Sent {} lease expiry notifications for {} days before expiry", expiringLeases.size(), daysBefore);
        }
    }
}
