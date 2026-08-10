package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import com.datagami.rentaxis.testsupport.RenewalTestFixtures;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class RenewalReminderServiceTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired RenewalReminderService service;
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
        RenewalOpportunity o = openOppForLeaseDaysOut(85);
        service.fireRemindersForOpportunity(o.getId(), LocalDate.of(2026, 6, 1));

        List<LeaseReminder> reminders = reminderRepo.findByOpportunityId(o.getId());
        long sent90 = reminders.stream().filter(r -> r.getSlot() == 90 && r.getStatus() == ReminderStatus.SENT).count();
        assertThat(sent90).isEqualTo(2);
    }

    @Test
    void skips_passed_window_at_open() {
        RenewalOpportunity o = openOppForLeaseDaysOut(45);
        service.fireRemindersForOpportunity(o.getId(), LocalDate.of(2026, 6, 1));

        List<LeaseReminder> reminders = reminderRepo.findByOpportunityId(o.getId());
        long skipped90 = reminders.stream().filter(r -> r.getSlot() == 90 && r.getStatus() == ReminderStatus.SKIPPED).count();
        assertThat(skipped90).isEqualTo(2);
        long sent60 = reminders.stream().filter(r -> r.getSlot() == 60 && r.getStatus() == ReminderStatus.SENT).count();
        assertThat(sent60).isEqualTo(2);
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

        long sent60 = reminderRepo.findByOpportunityId(o.getId()).stream().filter(r -> r.getSlot() == 60 && r.getStatus() == ReminderStatus.SENT).count();
        assertThat(sent60).isEqualTo(2);
    }
}
