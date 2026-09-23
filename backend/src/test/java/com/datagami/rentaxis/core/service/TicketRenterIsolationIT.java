package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateTicketDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.MaintenanceTicket;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.TicketAttachment;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.MaintenanceTicketRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.TicketAttachmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renter isolation on the ticket API (PR #342 follow-up). Two renters in the
 * same tenant: each reaches only the tickets they reported or that staff logged
 * on their behalf (#19). Someone else's ticket answers 404, exactly like a
 * ticket that does not exist, on every ticket route a renter can call.
 *
 * <p>Full HTTP on purpose: the tenant filter lets both renters through, so the
 * only thing standing between them is the ownership check, and it has to hold
 * at the API surface.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TicketRenterIsolationIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired MaintenanceTicketService tickets;
    @Autowired MaintenanceTicketRepository ticketRepo;
    @Autowired TicketAttachmentRepository attachmentRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UserRepository userRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired JdbcTemplate jdbc;

    private UUID tenantId;
    private User staff;
    private User userA;
    private User userB;
    private Renter renterA;
    private Renter renterB;
    private UUID propertyId;

    /** Reported by renter A / renter B from their portals. */
    private UUID reportedByA;
    private UUID reportedByB;
    /** Logged by staff on renter A's / renter B's behalf. */
    private UUID forA;
    private UUID forB;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("RISO-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);

        staff = user(UserRole.PROPERTY_MANAGER);
        userA = user(UserRole.RENTER);
        userB = user(UserRole.RENTER);
        renterA = renter("Renter A", userA);
        renterB = renter("Renter B", userB);

        Property p = new Property();
        p.setNameEn("Tower " + UUID.randomUUID());
        p.setEmirate(Emirate.DUBAI);
        propertyId = propertyRepo.save(p).getId();

        as(userA, "RENTER");
        reportedByA = tickets.createTicket(dto(null), userA.getId()).getId();
        as(userB, "RENTER");
        reportedByB = tickets.createTicket(dto(null), userB.getId()).getId();
        as(staff, "PROPERTY_MANAGER");
        forA = tickets.createTicket(dto(renterA.getId()), staff.getId()).getId();
        forB = tickets.createTicket(dto(renterB.getId()), staff.getId()).getId();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private User user(UserRole role) {
        User u = new User();
        u.setEmail("riso-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        return userRepo.save(u);
    }

    private Renter renter(String name, User u) {
        Renter r = new Renter();
        r.setNameEn(name);
        r.setUserId(u.getId());
        return renterRepo.save(r);
    }

    private static void as(User u, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                u.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private CreateTicketDTO dto(UUID onBehalfOfRenterId) {
        CreateTicketDTO dto = new CreateTicketDTO();
        dto.setPropertyId(propertyId);
        dto.setTitle("Leaking tap");
        dto.setOnBehalfOfRenterId(onBehalfOfRenterId);
        return dto;
    }

    /** Staff take a ticket to RESOLVED, so it can be rated or OTP-closed. */
    private void resolve(UUID id) {
        as(staff, "PROPERTY_MANAGER");
        tickets.assignTicket(id, staff.getId(), staff.getId());
        tickets.updateStatus(id, "IN_PROGRESS", staff.getId());
        tickets.updateStatus(id, "RESOLVED", staff.getId());
        SecurityContextHolder.clearContext();
    }

    private UUID attachmentOn(UUID ticketId, UUID uploadedBy) {
        MaintenanceTicket t = ticketRepo.findById(ticketId).orElseThrow();
        TicketAttachment a = new TicketAttachment();
        a.setTicket(t);
        a.setUploadedBy(uploadedBy);
        a.setFileUrl("/api/v1/assets/serve/ticket-attachments/none-" + UUID.randomUUID() + ".png");
        a.setFileType("image/png");
        a.setFileSize(1L);
        a.setUploadedAt(Instant.now());
        return attachmentRepo.save(a).getId();
    }

    private String storedOtp(UUID ticketId) {
        return jdbc.queryForObject("SELECT closure_otp FROM maintenance_tickets WHERE id = ?", String.class, ticketId);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> call(User caller, HttpMethod method, String path, Object body) {
        return spec(caller, method, path, body).retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(Map.class);
    }

    private RestClient.RequestBodySpec spec(User caller, HttpMethod method, String path, Object body) {
        RestClient.RequestBodySpec spec = RestClient.builder()
                .baseUrl("http://localhost:" + port).build()
                .method(method).uri("/api/v1/tickets" + path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Tenant-Id", tenantId.toString());
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec;
    }

    private int status(User caller, HttpMethod method, String path, Object body) {
        return spec(caller, method, path, body).retrieve().onStatus(s -> true, (req, res) -> { })
                .toBodilessEntity().getStatusCode().value();
    }

    @Test
    void aRenterCannotReachAnotherRentersTicketOnAnyRoute() {
        resolve(reportedByB);
        String b = "/" + reportedByB;
        String bForB = "/" + forB;

        assertThat(status(userA, HttpMethod.GET, b, null)).isEqualTo(404);
        assertThat(status(userA, HttpMethod.GET, bForB, null)).isEqualTo(404);
        assertThat(status(userA, HttpMethod.GET, b + "/replies", null)).isEqualTo(404);
        assertThat(status(userA, HttpMethod.GET, b + "/history", null)).isEqualTo(404);
        assertThat(status(userA, HttpMethod.GET, b + "/attachments", null)).isEqualTo(404);
        assertThat(status(userA, HttpMethod.POST, b + "/replies", Map.of("message", "hello"))).isEqualTo(404);
        assertThat(status(userA, HttpMethod.PUT, b + "/rate", Map.of("rating", 1))).isEqualTo(404);

        UUID attachment = attachmentOn(reportedByB, userB.getId());
        assertThat(status(userA, HttpMethod.GET, "/attachments/" + attachment + "/download", null)).isEqualTo(404);
        assertThat(status(userA, HttpMethod.DELETE, "/attachments/" + attachment, null)).isEqualTo(404);
        assertThat(attachmentRepo.findById(attachment)).isPresent();

        // Closing is a staff route: a renter is refused before the ticket is looked up.
        assertThat(status(userA, HttpMethod.PUT, b + "/close", Map.of("otp", storedOtp(reportedByB)))).isEqualTo(403);
        assertThat(status(userA, HttpMethod.PUT, b + "/status", Map.of("status", "CLOSED"))).isEqualTo(403);

        // Nothing written: no reply, no rating, still resolved.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_replies WHERE ticket_id = ?", Integer.class,
                reportedByB)).isZero();
        MaintenanceTicket after = ticketRepo.findById(reportedByB).orElseThrow();
        assertThat(after.getSatisfactionRating()).isNull();
        assertThat(after.getStatus().name()).isEqualTo("RESOLVED");
    }

    @Test
    void anotherRentersTicketLooksExactlyLikeOneThatDoesNotExist() {
        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> foreign = call(userA, HttpMethod.GET, "/" + reportedByB, null);
        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> missing = call(userA, HttpMethod.GET, "/" + UUID.randomUUID(), null);

        assertThat(foreign.getStatusCode()).isEqualTo(missing.getStatusCode());
        assertThat(foreign.getBody()).isEqualTo(missing.getBody());
    }

    @Test
    void aRenterListsOnlyTheirOwnAndOnBehalfTickets() {
        @SuppressWarnings("rawtypes")
        ResponseEntity<List> res = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri("/api/v1/tickets")
                .header("X-User-Id", userA.getId().toString())
                .header("X-User-Role", "RENTER")
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Tenant-Id", tenantId.toString())
                .retrieve().toEntity(List.class);

        List<String> ids = ((List<?>) res.getBody()).stream()
                .map(m -> (String) ((Map<?, ?>) m).get("id")).toList();
        assertThat(ids).containsExactlyInAnyOrder(reportedByA.toString(), forA.toString());
    }

    @Test
    void aRenterReachesAndActsOnTheirOwnAndOnBehalfTickets() {
        for (UUID id : List.of(reportedByA, forA)) {
            String t = "/" + id;
            assertThat(status(userA, HttpMethod.GET, t, null)).isEqualTo(200);
            assertThat(status(userA, HttpMethod.GET, t + "/replies", null)).isEqualTo(200);
            assertThat(status(userA, HttpMethod.GET, t + "/history", null)).isEqualTo(200);
            assertThat(status(userA, HttpMethod.GET, t + "/attachments", null)).isEqualTo(200);
            assertThat(status(userA, HttpMethod.POST, t + "/replies", Map.of("message", "still leaking")))
                    .isEqualTo(200);

            UUID attachment = attachmentOn(id, userA.getId());
            assertThat(status(userA, HttpMethod.DELETE, "/attachments/" + attachment, null)).isEqualTo(200);
            // r3 M8: staff's evidence on the renter's own ticket is not theirs to delete.
            UUID staffPhoto = attachmentOn(id, staff.getId());
            assertThat(status(userA, HttpMethod.DELETE, "/attachments/" + staffPhoto, null)).isEqualTo(403);
            assertThat(attachmentRepo.existsById(staffPhoto)).isTrue();
            assertThat(status(staff, HttpMethod.DELETE, "/attachments/" + staffPhoto, null)).isEqualTo(200);

            resolve(id);
            // The renter sees the OTP they hold and staff close the ticket with it.
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> seen = call(userA, HttpMethod.GET, t, null);
            assertThat(seen.getBody().get("closureOtp")).isEqualTo(storedOtp(id));
            assertThat(status(staff, HttpMethod.PUT, t + "/close", Map.of("otp", storedOtp(id)))).isEqualTo(200);
            assertThat(status(userA, HttpMethod.PUT, t + "/rate", Map.of("rating", 5))).isEqualTo(200);
            assertThat(ticketRepo.findById(id).orElseThrow().getSatisfactionRating()).isEqualTo(5);
        }
    }

    @Test
    void staffStillReachEveryTicketInTheirTenant() {
        User admin = user(UserRole.TENANT_ADMIN);
        for (UUID id : List.of(reportedByA, reportedByB, forA, forB)) {
            assertThat(status(admin, HttpMethod.GET, "/" + id, null)).isEqualTo(200);
            assertThat(status(admin, HttpMethod.GET, "/" + id + "/replies", null)).isEqualTo(200);
        }
    }
}
