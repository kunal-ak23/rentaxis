package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateTicketDTO;
import com.datagami.rentaxis.core.security.AuthTokenService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ticket API takes the caller's identity from the verified principal, not
 * from the X-User-* headers (PR #342). On the bearer path ApiSecurityFilter
 * ignores those headers, so a controller that read them let a renter with a
 * valid token file a ticket as someone else or list with a staff role.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.token-secret=ticket-caller-identity-it-secret-32-bytes-plus")
class TicketCallerIdentityIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired AuthTokenService tokens;
    @Autowired MaintenanceTicketService ticketService;
    @Autowired PropertyRepository propertyRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UserRepository userRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired JdbcTemplate jdbc;

    private UUID tenantId;
    private User renterA;
    private User victim;
    private UUID propertyId;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("TCI-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);

        renterA = user(UserRole.RENTER);
        victim = user(UserRole.RENTER);
        for (User u : List.of(renterA, victim)) {
            Renter r = new Renter();
            r.setNameEn("Renter " + u.getId());
            r.setUserId(u.getId());
            renterRepo.save(r);
        }

        Property p = new Property();
        p.setNameEn("Tower " + UUID.randomUUID());
        p.setEmirate(Emirate.DUBAI);
        propertyId = propertyRepo.save(p).getId();
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private User user(UserRole role) {
        User u = new User();
        u.setEmail("tci-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        return userRepo.save(u);
    }

    /** Renter A's valid token, with headers claiming to be the victim, as a tenant admin. */
    private RestClient.RequestBodySpec forged(org.springframework.http.HttpMethod method, String path) {
        String token = tokens.issue(renterA.getId(), UserRole.RENTER, tenantId, List.of(tenantId));
        return RestClient.builder().baseUrl("http://localhost:" + port).build()
                .method(method).uri("/api/v1/tickets" + path)
                .header("Authorization", "Bearer " + token)
                .header("X-User-Id", victim.getId().toString())
                .header("X-User-Role", "TENANT_ADMIN")
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Tenant-Id", tenantId.toString());
    }

    @Test
    void aRenterWithAForgedUserIdHeaderStillReportsAsThemselves() {
        @SuppressWarnings("rawtypes")
        Map created = forged(org.springframework.http.HttpMethod.POST, "")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("propertyId", propertyId.toString(), "title", "Leaking tap"))
                .retrieve().body(Map.class);

        assertThat(created.get("reportedBy")).isEqualTo(renterA.getId().toString());
        assertThat(jdbc.queryForObject("SELECT reported_by FROM maintenance_tickets WHERE id = ?",
                UUID.class, UUID.fromString((String) created.get("id")))).isEqualTo(renterA.getId());

        // A reply is written as the caller too, not as the header's user.
        forged(org.springframework.http.HttpMethod.POST, "/" + created.get("id") + "/replies")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", "still leaking"))
                .retrieve().toBodilessEntity();
        assertThat(jdbc.queryForObject("SELECT user_id FROM ticket_replies WHERE ticket_id = ?",
                UUID.class, UUID.fromString((String) created.get("id")))).isEqualTo(renterA.getId());
    }

    @Test
    void aForgedRoleHeaderDoesNotWidenTheList() {
        // The victim's ticket, which a tenant admin would see.
        TenantContextHolder.setTenantId(tenantId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                victim.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_RENTER"))));
        CreateTicketDTO dto = new CreateTicketDTO();
        dto.setPropertyId(propertyId);
        dto.setTitle("Victim ticket");
        UUID victimsTicket = ticketService.createTicket(dto, victim.getId()).getId();
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
        assertThat(victimsTicket).isNotNull();

        @SuppressWarnings("rawtypes")
        List listed = forged(org.springframework.http.HttpMethod.GET, "").retrieve().body(List.class);

        assertThat(listed).isEmpty();
    }
}
