package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.RenewalTestFixtures;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RenewalReminderServiceTest extends AbstractPostgresIT {

    @Autowired RenewalReminderService service;
    @Autowired com.datagami.rentaxis.core.service.TenantFeatureService tenantFeatureService;
    @Autowired RenewalOpportunityRepository oppRepo;
    @Autowired LeaseReminderRepository reminderRepo;
    @Autowired NotificationRepository notificationRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;

    UUID tenantId;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Org-" + UUID.randomUUID());
        org = orgRepo.save(org);
        tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() { TenantContextHolder.clear(); }

    private RenewalOpportunity openOppForLeaseDaysOut(int daysOut) {
        LocalDate today = LocalDate.of(2026, 6, 1);
        Lease lease = RenewalTestFixtures.createActiveLease(orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo, tenantId, today.minusYears(1), today.plusDays(daysOut));
        RenewalOpportunity o = new RenewalOpportunity();
        o.setLease(lease);
        o.setTenantId(tenantId);
        o.setStage(RenewalStage.OPEN);
        o.setOpenedAt(Instant.now());
        return oppRepo.save(o);
    }

    @Test
    void fires_90_day_reminder_when_in_window() {
        // EMAIL_NOTIFICATIONS defaults off, and EmailDispatcher drops every
        // event while it is. Only the in-app reminder is genuinely delivered, so
        // only it may be marked SENT — this assertion previously expected 2 and
        // was encoding the bug: an email recorded as sent that never left.
        RenewalOpportunity o = openOppForLeaseDaysOut(85);
        service.fireRemindersForOpportunity(o.getId(), LocalDate.of(2026, 6, 1));

        List<LeaseReminder> reminders = reminderRepo.findByOpportunityId(o.getId());
        long sent90 = reminders.stream().filter(r -> r.getSlot() == 90 && r.getStatus() == ReminderStatus.SENT).count();
        assertThat(sent90).isEqualTo(1);

        LeaseReminder email90 = reminders.stream()
                .filter(r -> r.getSlot() == 90 && r.getChannel() == ReminderChannel.EMAIL)
                .findFirst().orElseThrow();
        // Left PENDING so the daily run retries it: enabling email later
        // delivers reminders still inside their window rather than stranding them.
        assertThat(email90.getStatus()).isEqualTo(ReminderStatus.PENDING);
        assertThat(email90.getLastError()).contains("disabled");
    }

    @Test
    void emailReminderIsSentOnceTheTenantHasEmailEnabled() {
        tenantFeatureService.setEnabled(tenantId,
                com.datagami.rentaxis.domain.entity.enums.TenantFeature.EMAIL_NOTIFICATIONS, true);

        RenewalOpportunity o = openOppForLeaseDaysOut(85);
        service.fireRemindersForOpportunity(o.getId(), LocalDate.of(2026, 6, 1));

        List<LeaseReminder> reminders = reminderRepo.findByOpportunityId(o.getId());
        long sent90 = reminders.stream().filter(r -> r.getSlot() == 90 && r.getStatus() == ReminderStatus.SENT).count();
        // Both channels now, because the dispatcher will actually deliver.
        assertThat(sent90).isEqualTo(2);
    }

    @Test
    void skips_passed_window_at_open() {
        RenewalOpportunity o = openOppForLeaseDaysOut(45);
        service.fireRemindersForOpportunity(o.getId(), LocalDate.of(2026, 6, 1));

        List<LeaseReminder> reminders = reminderRepo.findByOpportunityId(o.getId());
        long skipped90 = reminders.stream().filter(r -> r.getSlot() == 90 && r.getStatus() == ReminderStatus.SKIPPED).count();
        assertThat(skipped90).isEqualTo(2);
        // Only the in-app channel is delivered while EMAIL_NOTIFICATIONS is off.
        long sent60 = reminders.stream().filter(r -> r.getSlot() == 60 && r.getStatus() == ReminderStatus.SENT).count();
        assertThat(sent60).isEqualTo(1);
    }

    @Test
    void idempotent_second_call_does_not_duplicate() {
        RenewalOpportunity o = openOppForLeaseDaysOut(85);
        service.fireRemindersForOpportunity(o.getId(), LocalDate.of(2026, 6, 1));
        int firstCount = reminderRepo.findByOpportunityId(o.getId()).size();

        service.fireRemindersForOpportunity(o.getId(), LocalDate.of(2026, 6, 1));
        int secondCount = reminderRepo.findByOpportunityId(o.getId()).size();

        assertThat(secondCount).isEqualTo(firstCount);
    }

    @Test
    void skips_remaining_when_intent_is_RENEW() {
        RenewalOpportunity o = openOppForLeaseDaysOut(85);
        service.fireRemindersForOpportunity(o.getId(), LocalDate.of(2026, 6, 1));
        o.setIntent(RenewalIntent.RENEW);
        o.setStage(RenewalStage.INTENT_CAPTURED);
        oppRepo.save(o);
        service.fireRemindersForOpportunity(o.getId(), LocalDate.of(2026, 7, 1));

        List<LeaseReminder> reminders = reminderRepo.findByOpportunityId(o.getId());
        long skipped = reminders.stream().filter(r -> r.getStatus() == ReminderStatus.SKIPPED && "intent captured: RENEW".equals(r.getLastError())).count();
        assertThat(skipped).isGreaterThanOrEqualTo(2);
    }

    @Test
    void in_app_reminder_copy_directs_to_web_portal_not_a_tap_action() {
        RenewalOpportunity o = openOppForLeaseDaysOut(85);
        service.fireRemindersForOpportunity(o.getId(), LocalDate.of(2026, 6, 1));

        UUID renterUserId = o.getLease().getRenter().getUserId();
        List<Notification> notifications = notificationRepo.findByUserIdOrderByCreatedAtDesc(renterUserId);
        assertThat(notifications)
                .filteredOn(n -> "LEASE_RENEWAL_REMINDER".equals(n.getType()))
                .isNotEmpty()
                .allSatisfy(n -> {
                    // The renter mobile app has no renewal screen, so the copy must not
                    // instruct a tap action; it points at the renter web portal instead.
                    assertThat(n.getMessage()).doesNotContain("Tap to");
                    assertThat(n.getMessage()).contains("renter web portal");
                });
    }

    @Test
    void DISCUSS_intent_does_NOT_skip_remaining() {
        RenewalOpportunity o = openOppForLeaseDaysOut(85);
        service.fireRemindersForOpportunity(o.getId(), LocalDate.of(2026, 6, 1));
        o.setIntent(RenewalIntent.DISCUSS);
        o.setStage(RenewalStage.INTENT_CAPTURED);
        oppRepo.save(o);
        service.fireRemindersForOpportunity(o.getId(), LocalDate.of(2026, 7, 1));

        // In-app only: EMAIL_NOTIFICATIONS is off for this tenant.
        long sent60 = reminderRepo.findByOpportunityId(o.getId()).stream().filter(r -> r.getSlot() == 60 && r.getStatus() == ReminderStatus.SENT).count();
        assertThat(sent60).isEqualTo(1);
    }
}
