package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseReminder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.enums.ReminderChannel;
import com.datagami.rentaxis.domain.entity.enums.ReminderStatus;
import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import com.datagami.rentaxis.domain.entity.enums.RenewalStage;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseInteractionRepository;
import com.datagami.rentaxis.domain.repository.LeaseReminderRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenewalOpportunityRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.RenewalTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RenterRenewalControllerTest extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired RenewalOpportunityRepository oppRepo;
    @Autowired LeaseReminderRepository reminderRepo;
    @Autowired LeaseInteractionRepository interactionRepo;

    private UUID tenantId;
    private Lease leaseA;
    private Renter renterA;
    private UUID renterAUserId;
    private RenewalOpportunity oppA;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("TestOrg-" + UUID.randomUUID());
        org = orgRepo.save(org);
        tenantId = org.getId();

        TenantContextHolder.setTenantId(tenantId);
        leaseA = RenewalTestFixtures.createActiveLease(
                orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo,
                tenantId,
                LocalDate.now().minusDays(280),
                LocalDate.now().plusDays(85));
        renterA = leaseA.getRenter();
        renterAUserId = renterA.getUserId();

        oppA = new RenewalOpportunity();
        oppA.setLease(leaseA);
        oppA.setTenantId(tenantId);
        oppA.setStage(RenewalStage.OPEN);
        oppA.setOpenedAt(Instant.now());
        oppA = oppRepo.save(oppA);

        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private RestClient renterClient(UUID userId, UUID tid) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id", userId.toString())
                .defaultHeader("X-User-Role", "RENTER")
                .defaultHeader("X-Tenant-Id", tid.toString())
                .defaultHeader("X-User-Tenant-Id", tid.toString())
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {})
                .build();
    }

    @Test
    void summary_returns_own_leases_only() {
        // Set up renter B + their own lease + opportunity, same tenant
        TenantContextHolder.setTenantId(tenantId);
        Lease leaseB = RenewalTestFixtures.createActiveLease(
                orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo,
                tenantId,
                LocalDate.now().minusDays(280),
                LocalDate.now().plusDays(85));
        RenewalOpportunity oppB = new RenewalOpportunity();
        oppB.setLease(leaseB);
        oppB.setTenantId(tenantId);
        oppB.setStage(RenewalStage.OPEN);
        oppB.setOpenedAt(Instant.now());
        oppRepo.save(oppB);
        TenantContextHolder.clear();

        Map<String, Object> body = renterClient(renterAUserId, tenantId)
                .get()
                .uri("/api/v1/me/renewals")
                .retrieve()
                .body(Map.class);

        assertThat(body).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> leases = (List<Map<String, Object>>) body.get("leases");
        assertThat(leases).hasSize(1);
        Map<String, Object> view = leases.get(0);
        assertThat(view.get("leaseId")).isEqualTo(leaseA.getId().toString());
        assertThat(view.get("opportunityId")).isEqualTo(oppA.getId().toString());
        assertThat(view.get("stage")).isEqualTo("OPEN");
        Number daysRemaining = (Number) view.get("daysRemaining");
        assertThat(daysRemaining.longValue()).isBetween(84L, 86L);
    }

    /**
     * A reminder sent at 21:00 UTC went out at 01:00 in Dubai the next day, and
     * that is the date the renter should see — not the UTC one.
     */
    @Test
    void summary_dates_a_reminder_in_the_app_zone_not_utc() {
        TenantContextHolder.setTenantId(tenantId);
        LeaseReminder r = new LeaseReminder();
        r.setTenantId(tenantId);
        r.setOpportunity(oppA);
        r.setSlot((short) 1);
        r.setChannel(ReminderChannel.EMAIL);
        r.setStatus(ReminderStatus.SENT);
        r.setSentAt(Instant.parse("2026-09-23T21:00:00Z"));
        reminderRepo.save(r);
        TenantContextHolder.clear();

        Map<String, Object> body = renterClient(renterAUserId, tenantId)
                .get()
                .uri("/api/v1/me/renewals")
                .retrieve()
                .body(Map.class);

        assertThat(body).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> leases = (List<Map<String, Object>>) body.get("leases");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reminders = (List<Map<String, Object>>) leases.get(0).get("reminders");
        assertThat(reminders).singleElement()
                .satisfies(rem -> assertThat(rem.get("sentAt")).isEqualTo("2026-09-24"));
    }

    @Test
    void intent_sets_on_own_opportunity() {
        Map<String, Object> resp = renterClient(renterAUserId, tenantId)
                .post()
                .uri("/api/v1/me/renewals/" + oppA.getId() + "/intent")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("intent", "RENEW"))
                .retrieve()
                .body(Map.class);

        assertThat(resp).isNotNull();
        assertThat(resp.get("intent")).isEqualTo("RENEW");
        assertThat(resp.get("stage")).isEqualTo("INTENT_CAPTURED");

        TenantContextHolder.setTenantId(tenantId);
        RenewalOpportunity reloaded = oppRepo.findById(oppA.getId()).orElseThrow();
        assertThat(reloaded.getIntent()).isEqualTo(RenewalIntent.RENEW);
        assertThat(reloaded.getStage()).isEqualTo(RenewalStage.INTENT_CAPTURED);
        TenantContextHolder.clear();
    }

    @Test
    void intent_on_other_renter_opportunity_returns_404() {
        // Build renter B with their own opportunity, in same tenant
        TenantContextHolder.setTenantId(tenantId);
        Lease leaseB = RenewalTestFixtures.createActiveLease(
                orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo,
                tenantId,
                LocalDate.now().minusDays(280),
                LocalDate.now().plusDays(85));
        RenewalOpportunity oppB = new RenewalOpportunity();
        oppB.setLease(leaseB);
        oppB.setTenantId(tenantId);
        oppB.setStage(RenewalStage.OPEN);
        oppB.setOpenedAt(Instant.now());
        oppB = oppRepo.save(oppB);
        TenantContextHolder.clear();

        var response = renterClient(renterAUserId, tenantId)
                .post()
                .uri("/api/v1/me/renewals/" + oppB.getId() + "/intent")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("intent", "RENEW"))
                .retrieve()
                .toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(404);

        // Verify oppB unchanged
        TenantContextHolder.setTenantId(tenantId);
        RenewalOpportunity reloaded = oppRepo.findById(oppB.getId()).orElseThrow();
        assertThat(reloaded.getIntent()).isNull();
        assertThat(reloaded.getStage()).isEqualTo(RenewalStage.OPEN);
        TenantContextHolder.clear();
    }

    @Test
    void intent_on_closed_opportunity_returns_400() {
        TenantContextHolder.setTenantId(tenantId);
        oppA.setStage(RenewalStage.CLOSED_WON);
        oppA.setClosedAt(Instant.now());
        oppRepo.save(oppA);
        TenantContextHolder.clear();

        var response = renterClient(renterAUserId, tenantId)
                .post()
                .uri("/api/v1/me/renewals/" + oppA.getId() + "/intent")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("intent", "RENEW"))
                .retrieve()
                .toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
