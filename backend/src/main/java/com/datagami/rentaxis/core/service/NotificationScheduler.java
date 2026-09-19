package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.service.cheque.ChequeDueRules;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
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

/**
 * The daily nudges: what is about to fall due, what is late, and whose lease is
 * running out.
 *
 * <p><b>Tenants.</b> A scheduled run has no authenticated caller and no tenant
 * context, so {@code TenantAspect} leaves the Hibernate tenant filter off and one
 * pass covers every organisation. Each notification is addressed with the tenant
 * id carried on the row it came from, which is what keeps a cross-tenant sweep
 * from cross-addressing anything.</p>
 *
 * <p><b>Read-only.</b> The job reads the register and sends; it never moves a row
 * through it. Marking a cheque overdue used to be a write this job did, and a
 * status the reminder job invented is exactly how the register and the ledger
 * came to disagree — lateness is derived from the date and the lease's grace by
 * {@link ChequeDueRules}, every time it is asked.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationScheduler {

    private final ChequeRepository chequeRepository;
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

        // One bounded query per offset. The previous version loaded every schedule
        // in the database and filtered in Java, once per configured offset.
        for (int daysBefore : reminderDays) {
            LocalDate targetDate = today.plusDays(daysBefore);
            List<Cheque> maturing = chequeRepository.findRegisteredMaturingOn(targetDate);

            for (Cheque cheque : maturing) {
                try {
                    java.util.UUID renterUserId = renterUserId(cheque);
                    if (renterUserId != null) {
                        notificationService.notify(
                                cheque.getTenantId(),
                                renterUserId,
                                "PAYMENT_DUE",
                                "Payment Due Reminder",
                                "Installment #" + cheque.getSeqNo() + " of "
                                        + cheque.getAmount() + " is due in " + daysBefore + " day(s).",
                                "CHEQUE",
                                cheque.getId());
                    }
                } catch (Exception e) {
                    log.warn("Failed to send payment reminder for cheque {}", cheque.getId(), e);
                }
            }

            log.info("Sent {} payment due reminders for {} days before due date", maturing.size(), daysBefore);
        }
    }

    /**
     * Should a renter hear about this instalment today, given how late it is?
     *
     * <p>The job runs daily over every due row, so without a cadence a renter with
     * one unpaid cheque gets the same message every morning for a year: the first
     * week is a reminder, the second is nagging, and by the third the whole estate
     * has filtered the sender. So: a nudge on days 1, 3, 7 and 14, then monthly,
     * and nothing at all past {@link #MAX_OVERDUE_REMINDER_DAYS} — a debt that old
     * is a collections matter somebody is handling by hand, not something a cron
     * job should keep announcing.</p>
     *
     * <p>Day zero sends nothing. That is the day grace ran out, and the "due today"
     * reminder has already gone.</p>
     *
     * <p>Package-private and pure so the cadence can be tested as a table rather
     * than by running a scheduler against a database.</p>
     */
    static boolean shouldRemind(int daysOverdue) {
        if (daysOverdue <= 0 || daysOverdue > MAX_OVERDUE_REMINDER_DAYS) {
            return false;
        }
        return switch (daysOverdue) {
            case 1, 3, 7, 14 -> true;
            default -> daysOverdue % 30 == 0;
        };
    }

    /** Past this, the reminder job stops talking and a human takes over. */
    static final int MAX_OVERDUE_REMINDER_DAYS = 180;

    /**
     * The chasing list.
     *
     * <p>Only the rows that are past the lease's <em>grace</em> period, not merely
     * past their date: a renter whose contract gives them five days is not late on
     * day one, and telling them they are is how a reminder becomes noise the whole
     * estate ignores. {@link #shouldRemind} then decides whether today is one of the
     * days this particular debt is worth mentioning.</p>
     */
    private void checkOverduePayments() {
        log.info("Checking overdue payments...");
        LocalDate today = LocalDate.now();

        int sent = 0;
        for (Cheque cheque : chequeRepository.findAllDue(today)) {
            Lease lease = cheque.getLease();
            int graceDays = lease == null ? 0 : lease.getGracePeriodDays();
            if (!ChequeDueRules.overdue(cheque, graceDays, today)) {
                continue;
            }
            int daysOverdue = ChequeDueRules.daysOverdue(cheque, graceDays, today);
            if (!shouldRemind(daysOverdue)) {
                continue;
            }
            try {
                java.util.UUID renterUserId = renterUserId(cheque);
                if (renterUserId != null) {
                    notificationService.notify(
                            cheque.getTenantId(),
                            renterUserId,
                            "PAYMENT_OVERDUE",
                            "Payment Overdue",
                            "Installment #" + cheque.getSeqNo() + " of "
                                    + cheque.getAmount() + " is " + daysOverdue + " day(s) overdue.",
                            "CHEQUE",
                            cheque.getId());
                    sent++;
                }
            } catch (Exception e) {
                log.warn("Failed to send overdue notification for cheque {}", cheque.getId(), e);
            }
        }

        log.info("Sent {} overdue payment notifications", sent);
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

    /**
     * Off the cheque's own renter, falling back to the lease's.
     *
     * <p>The row carries its renter so a bounce stays attributable after a renewal
     * moves the lease; the fallback covers rows written before that denormalisation
     * existed.</p>
     */
    private static java.util.UUID renterUserId(Cheque cheque) {
        if (cheque.getRenter() != null && cheque.getRenter().getUserId() != null) {
            return cheque.getRenter().getUserId();
        }
        Lease lease = cheque.getLease();
        return lease != null && lease.getRenter() != null ? lease.getRenter().getUserId() : null;
    }
}
