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
@org.springframework.test.context.event.RecordApplicationEvents
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

    @Autowired org.springframework.test.context.event.ApplicationEvents appEvents;

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
        // Today, not a fixed date: an answer on a contract already past its end
        // date is refused (break-it R3 portal3 F8).
        LocalDate today = LocalDate.now();
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
    // ---- break-it R3 portal3 F3: one staff e-mail per answer, a change of mind included ----

    private List<com.datagami.rentaxis.core.email.event.EmailEvent> intentEmails() {
        return appEvents.stream(com.datagami.rentaxis.core.email.event.EmailEvent.class)
                .filter(e -> e.getType() == com.datagami.rentaxis.core.email.EmailEventType.RENEWAL_INTENT_CAPTURED)
                .toList();
    }

    @Test
    void a_changed_answer_is_emailed_again_with_the_new_choice() {
        RenewalOpportunity o = openOpportunity();
        UUID renter = o.getLease().getRenter().getUserId();

        service.captureIntentFromRenter(o.getId(), RenewalIntent.RENEW, renter);
        service.captureIntentFromRenter(o.getId(), RenewalIntent.MOVE_OUT, renter);

        List<com.datagami.rentaxis.core.email.event.EmailEvent> emails = intentEmails();
        assertThat(emails).hasSize(2);
        assertThat(emails).extracting(com.datagami.rentaxis.core.email.event.EmailEvent::getDedupKey)
                .doesNotHaveDuplicates();
        assertThat(emails.get(1).getDedupKey()).contains("MOVE_OUT");
        assertThat(((com.datagami.rentaxis.core.email.event.payload.RenewalIntentCapturedPayload)
                emails.get(1).getPayload()).intent()).isEqualTo("MOVE_OUT");
    }

    @Test
    void the_same_answer_twice_records_and_notifies_once() {
        RenewalOpportunity o = openOpportunity();
        UUID renter = o.getLease().getRenter().getUserId();

        service.captureIntentFromRenter(o.getId(), RenewalIntent.RENEW, renter);
        RenewalOpportunity again = service.captureIntentFromRenter(o.getId(), RenewalIntent.RENEW, renter);

        assertThat(again.getIntent()).isEqualTo(RenewalIntent.RENEW);
        assertThat(intentEmails()).hasSize(1);
        assertThat(appEvents.stream(com.datagami.rentaxis.core.email.event.RenewalIntentCapturedEvent.class))
                .hasSize(1);
        assertThat(interactionRepo.findActiveByLeaseId(o.getLease().getId(), PageRequest.of(0, 10)).getContent())
                .hasSize(1);
    }

    // ---- break-it R3 portal3 F8: no renewal answer on a contract that is over ----

    @Test
    void an_answer_on_a_contract_past_its_end_date_is_refused() {
        RenewalOpportunity o = openOpportunity();
        Lease lease = o.getLease();
        lease.setEndDate(LocalDate.now().minusDays(40));
        leaseRepo.save(lease);
        UUID renter = lease.getRenter().getUserId();

        assertThatThrownBy(() -> service.captureIntentFromRenter(o.getId(), RenewalIntent.RENEW, renter))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage(RenewalIntentService.CONTRACT_ENDED)
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("renewal.contractEnded");
        assertThatThrownBy(() -> service.captureIntentFromToken(o.getId(), RenewalIntent.RENEW))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage(RenewalIntentService.CONTRACT_ENDED);
        assertThat(oppRepo.findById(o.getId()).orElseThrow().getIntent()).isNull();
        assertThat(intentEmails()).isEmpty();
    }

    @Test
    void an_answer_on_a_terminated_contract_is_refused() {
        RenewalOpportunity o = openOpportunity();
        Lease lease = o.getLease();
        lease.setStatus(com.datagami.rentaxis.domain.entity.enums.LeaseStatus.TERMINATED);
        lease.setTerminatedOn(LocalDate.now());
        leaseRepo.save(lease);

        assertThatThrownBy(() -> service.captureIntentFromRenter(o.getId(), RenewalIntent.RENEW,
                lease.getRenter().getUserId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage(RenewalIntentService.CONTRACT_ENDED);
    }
}
