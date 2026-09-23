package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.LeaseInteraction;
import com.datagami.rentaxis.domain.entity.LeaseReminder;
import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.entity.enums.InteractionType;
import com.datagami.rentaxis.domain.entity.enums.ReminderChannel;
import com.datagami.rentaxis.domain.entity.enums.ReminderStatus;
import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import com.datagami.rentaxis.domain.entity.enums.RenewalStage;
import com.datagami.rentaxis.domain.repository.*;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.RenewalTestFixtures;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RenewalIntentServiceTest extends AbstractPostgresIT {

    @Autowired RenewalIntentService service;
    @Autowired LeaseRepository leaseRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired RenewalOpportunityRepository oppRepo;
    @Autowired LeaseInteractionRepository interactionRepo;
    @Autowired LeaseReminderRepository reminderRepo;

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

    private RenewalOpportunity openOpportunity() {
        LocalDate today = LocalDate.of(2026, 6, 1);
        Lease lease = RenewalTestFixtures.createActiveLease(orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo,
                tenantId, today.minusYears(1), today.plusDays(45));
        RenewalOpportunity o = new RenewalOpportunity();
        o.setLease(lease);
        o.setTenantId(tenantId);
        o.setStage(RenewalStage.OPEN);
        o.setOpenedAt(Instant.now());
        return oppRepo.save(o);
    }

    private LeaseReminder pendingReminder(RenewalOpportunity o, short slot, ReminderChannel ch) {
        LeaseReminder r = new LeaseReminder();
        r.setTenantId(tenantId);
        r.setOpportunity(o);
        r.setSlot(slot);
        r.setChannel(ch);
        r.setStatus(ReminderStatus.PENDING);
        return reminderRepo.save(r);
    }

    @Test
    void capture_records_interaction_and_skips_pending_RENEW_or_MOVE_OUT() {
        RenewalOpportunity o = openOpportunity();
        pendingReminder(o, (short) 1, ReminderChannel.EMAIL);
        pendingReminder(o, (short) 2, ReminderChannel.IN_APP);

        RenewalOpportunity updated = service.captureIntentFromToken(o.getId(), RenewalIntent.RENEW);

        assertThat(updated.getStage()).isEqualTo(RenewalStage.INTENT_CAPTURED);
        assertThat(updated.getIntent()).isEqualTo(RenewalIntent.RENEW);
        assertThat(updated.getIntentCapturedAt()).isNotNull();

        List<LeaseInteraction> interactions = interactionRepo
                .findActiveByLeaseId(o.getLease().getId(), PageRequest.of(0, 10)).getContent();
        assertThat(interactions).hasSize(1);
        assertThat(interactions.get(0).getType()).isEqualTo(InteractionType.SYSTEM_INTENT);
        assertThat(interactions.get(0).getSummary()).contains("RENEW");

        List<LeaseReminder> reminders = reminderRepo.findByOpportunityId(o.getId());
        assertThat(reminders).hasSize(2);
        assertThat(reminders).allMatch(r -> r.getStatus() == ReminderStatus.SKIPPED);
        assertThat(reminders).allMatch(r -> "intent captured: RENEW".equals(r.getLastError()));
    }

    @Test
    void capture_DISCUSS_does_NOT_skip_pending() {
        RenewalOpportunity o = openOpportunity();
        pendingReminder(o, (short) 1, ReminderChannel.EMAIL);
        pendingReminder(o, (short) 2, ReminderChannel.IN_APP);

        service.captureIntentFromToken(o.getId(), RenewalIntent.DISCUSS);

        List<LeaseReminder> reminders = reminderRepo.findByOpportunityId(o.getId());
        assertThat(reminders).hasSize(2);
        assertThat(reminders).allMatch(r -> r.getStatus() == ReminderStatus.PENDING);
    }

    @Test
    void a_link_is_spent_once_a_choice_is_recorded_but_the_renter_can_still_change_it_signed_in() {
        RenewalOpportunity o = openOpportunity();

        service.captureIntentFromToken(o.getId(), RenewalIntent.RENEW);
        // Round 5 (audit B-F5): the emailed links are single-use.
        assertThatThrownBy(() -> service.captureIntentFromToken(o.getId(), RenewalIntent.DISCUSS))
                .isInstanceOf(RenewalIntentService.IntentAlreadyRecordedException.class);

        RenewalOpportunity updated = service.captureIntentFromRenter(o.getId(), RenewalIntent.DISCUSS,
                o.getLease().getRenter().getUserId());

        assertThat(updated.getIntent()).isEqualTo(RenewalIntent.DISCUSS);

        List<LeaseInteraction> interactions = interactionRepo
                .findActiveByLeaseId(o.getLease().getId(), PageRequest.of(0, 10)).getContent();
        assertThat(interactions).hasSize(2);
        assertThat(interactions).anyMatch(i -> i.getSummary().contains("RENEW -> DISCUSS"));
    }

    @Test
    void closed_opportunity_rejects() {
        RenewalOpportunity o = openOpportunity();
        o.setStage(RenewalStage.CLOSED_WON);
        oppRepo.save(o);

        assertThatThrownBy(() -> service.captureIntentFromToken(o.getId(), RenewalIntent.RENEW))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void wrong_renter_returns_NotFound() {
        RenewalOpportunity o = openOpportunity();
        UUID someoneElse = UUID.randomUUID();

        assertThatThrownBy(() -> service.captureIntentFromRenter(o.getId(), RenewalIntent.RENEW, someoneElse))
                .isInstanceOf(NotFoundException.class);
    }
}
