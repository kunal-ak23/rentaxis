package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.renewal.RenewalTokenService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import com.datagami.rentaxis.domain.entity.enums.RenewalStage;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenewalOpportunityRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.RenewalTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PublicRenewalControllerTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort int port;

    @Autowired RenewalTokenService tokenService;
    @Autowired RenewalOpportunityRepository oppRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;

    @AfterEach
    void tearDown() { TenantContextHolder.clear(); }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private RenewalOpportunity createOpportunity(LocalDate endDate, RenewalStage stage) {
        LandlordOrg org = new LandlordOrg();
        org.setName("PubRenewalTest-" + UUID.randomUUID());
        org = orgRepo.save(org);
        UUID tenantId = org.getId();

        TenantContextHolder.setTenantId(tenantId);
        try {
            LocalDate startDate = endDate.minusYears(1);
            Lease lease = RenewalTestFixtures.createActiveLease(
                    orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo,
                    tenantId, startDate, endDate);

            RenewalOpportunity o = new RenewalOpportunity();
            o.setTenantId(tenantId);
            o.setLease(lease);
            o.setStage(stage);
            return oppRepo.save(o);
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void valid_token_200() {
        LocalDate endDate = LocalDate.now().plusDays(30);
        RenewalOpportunity o = createOpportunity(endDate, RenewalStage.OPEN);
        String token = tokenService.sign(o.getId(), RenewalIntent.RENEW, endDate);

        Map<?, ?> body = client().post().uri("/api/v1/public/renewal-intent")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("token", token))
                .retrieve().body(Map.class);

        assertThat(body.get("intent")).isEqualTo("RENEW");
        assertThat(body.get("leaseId")).isEqualTo(o.getLease().getId().toString());
        assertThat(body.get("redirectTo")).isEqualTo("/dashboard/renter-portal/renewals");

        TenantContextHolder.setTenantId(o.getTenantId());
        RenewalOpportunity persisted = oppRepo.findById(o.getId()).orElseThrow();
        assertThat(persisted.getIntent()).isEqualTo(RenewalIntent.RENEW);
        assertThat(persisted.getStage()).isEqualTo(RenewalStage.INTENT_CAPTURED);
        assertThat(persisted.getIntentCapturedAt()).isNotNull();
    }

    @Test
    void expired_token_410() {
        // endDate 30 days in past => exp = endDate + 7 days = 23 days in past.
        LocalDate endDate = LocalDate.now().minusDays(30);
        RenewalOpportunity o = createOpportunity(endDate, RenewalStage.OPEN);
        String token = tokenService.sign(o.getId(), RenewalIntent.RENEW, endDate);

        HttpStatusCodeException ex = null;
        try {
            client().post().uri("/api/v1/public/renewal-intent")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("token", token))
                    .retrieve().toBodilessEntity();
        } catch (HttpStatusCodeException e) { ex = e; }

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(410);
        assertThat(ex.getResponseBodyAsString()).contains("TOKEN_EXPIRED");
    }

    @Test
    void tampered_token_400() {
        LocalDate endDate = LocalDate.now().plusDays(30);
        RenewalOpportunity o = createOpportunity(endDate, RenewalStage.OPEN);
        String token = tokenService.sign(o.getId(), RenewalIntent.RENEW, endDate);
        // Mutate last 4 chars — flips signature.
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        HttpStatusCodeException ex = null;
        try {
            client().post().uri("/api/v1/public/renewal-intent")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("token", tampered))
                    .retrieve().toBodilessEntity();
        } catch (HttpStatusCodeException e) { ex = e; }

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(400);
        assertThat(ex.getResponseBodyAsString()).contains("TOKEN_INVALID");
    }

    @Test
    void closed_opportunity_409() {
        LocalDate endDate = LocalDate.now().plusDays(30);
        RenewalOpportunity o = createOpportunity(endDate, RenewalStage.CLOSED_WON);
        String token = tokenService.sign(o.getId(), RenewalIntent.RENEW, endDate);

        HttpStatusCodeException ex = null;
        try {
            client().post().uri("/api/v1/public/renewal-intent")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("token", token))
                    .retrieve().toBodilessEntity();
        } catch (HttpStatusCodeException e) { ex = e; }

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(409);
        assertThat(ex.getResponseBodyAsString()).contains("ALREADY_RESOLVED");
    }

    @Test
    void token_for_unknown_opportunity_404() {
        LocalDate endDate = LocalDate.now().plusDays(30);
        String token = tokenService.sign(UUID.randomUUID(), RenewalIntent.RENEW, endDate);

        HttpStatusCodeException ex = null;
        try {
            client().post().uri("/api/v1/public/renewal-intent")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("token", token))
                    .retrieve().toBodilessEntity();
        } catch (HttpStatusCodeException e) { ex = e; }

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(404);
        assertThat(ex.getResponseBodyAsString()).contains("NOT_FOUND");
    }
}
