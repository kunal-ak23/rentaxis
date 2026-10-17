package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The renewal season's endpoints over real HTTP with open-session-in-view off (scale #16,
 * #17): the queue's filters on {@code GET /leases/paged}, {@code POST /leases/renew-batch},
 * {@code POST /leases/terminate-batch/preview} and {@code POST /leases/terminate-batch}. Each
 * call is checked for a lazy load outside a transaction and for 5xx, the role gates are the
 * single acts' (a manager drafts and previews but does not terminate), and a request too big
 * or empty is a 400.
 */
@org.springframework.context.annotation.Import(com.datagami.rentaxis.testsupport.LaterBusinessDayConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class LeaseBatchEndpointsIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService generation;
    @Autowired LeasePostingService posting;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> failures = new ArrayList<>();
    private LeaseTestFixtures f;
    private User admin;
    private User pm;
    private UUID renewMe;
    private UUID endMe;

    private static final LocalDate START = LocalDate.of(2026, 11, 1);
    private static final LocalDate END = LocalDate.of(2027, 10, 31);

    @BeforeEach
    void seed() {
        f = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo, propertyService, accountService,
                propertyAccountService, chargeTypeService).bootstrap().withLeaseServices(leaseService, generation, posting);
        renewMe = f.postedLease(LocalDate.of(2026, 10, 15), START, END, List.of(line("RENT", "60000")), 4, null).lease().getId();
        endMe = f.postedLease(f.createUnit(f.property(), "07-02"), f.createRenter("Leaving"), LocalDate.of(2026, 10, 15),
                START, END, List.of(line("RENT", "48000")), 4, null).lease().getId();
        admin = user(UserRole.TENANT_ADMIN);
        pm = user(UserRole.PROPERTY_MANAGER);
        UserPropertyAssignment a = new UserPropertyAssignment();
        a.setUserId(pm.getId());
        a.setPropertyId(f.property().getId());
        assignmentRepo.save(a);
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @Test
    void theRenewalSeasonRoutesWorkWithOsivOffUnderTheSingleActsRoleGates(CapturedOutput out) throws Exception {
        String queue = "/api/v1/leases/paged?endDateFrom=2027-10-01&endDateTo=2027-10-31&successor=NONE"
                + "&excludeStatus=DRAFT,PENDING_SIGNATURE,TERMINATED,CLOSED&sort=endDate,asc&size=50";
        for (User caller : List.of(admin, pm)) {
            JsonNode page = expect(out, 200, caller, HttpMethod.GET, queue, null);
            assertThat(page.get("totalElements").asInt()).as(caller.getRole().name()).isEqualTo(2);
        }
        JsonNode floor = expect(out, 200, pm, HttpMethod.GET, "/api/v1/leases/paged?floor=07&endDateFrom=2027-10-01", null);
        assertThat(floor.get("totalElements").asInt()).isEqualTo(1);
        assertThat(floor.get("content").get(0).get("id").asText()).isEqualTo(endMe.toString());

        JsonNode renewed = expect(out, 200, pm, HttpMethod.POST, "/api/v1/leases/renew-batch", Map.of(
                "leaseIds", List.of(renewMe), "rule", Map.of("mode", "PERCENT", "percent", 5),
                "contractDate", "2027-10-15", "carryDepositForward", true, "generateGrid", true));
        assertThat(renewed.get(0).get("ok").asBoolean()).as(renewed.toString()).isTrue();
        assertThat(renewed.get(0).get("newRent").decimalValue()).isEqualByComparingTo("63000");
        // The draft shows on the row of the predecessor, and leaves the "to do" stage.
        JsonNode drafted = expect(out, 200, admin, HttpMethod.GET, queue.replace("successor=NONE", "successor=DRAFTED"), null);
        assertThat(drafted.get("content").get(0).get("renewedToStatus").asText()).isEqualTo("DRAFT");
        assertThat(expect(out, 200, admin, HttpMethod.GET, queue, null).get("totalElements").asInt()).isEqualTo(1);

        Map<String, Object> end = Map.of("leaseIds", List.of(endMe), "notes", "Season move-out");
        JsonNode preview = expect(out, 200, pm, HttpMethod.POST, "/api/v1/leases/terminate-batch/preview", end);
        assertThat(preview.get(0).get("ok").asBoolean()).as(preview.toString()).isTrue();
        assertThat(preview.get(0).get("terminationDate").asText()).isEqualTo(END.toString());
        assertThat(preview.get(0).get("preview").get("chequesToKeep")).isNotNull();

        expect(out, 403, pm, HttpMethod.POST, "/api/v1/leases/terminate-batch", end);
        JsonNode ended = expect(out, 200, admin, HttpMethod.POST, "/api/v1/leases/terminate-batch", end);
        assertThat(ended.get(0).get("ok").asBoolean()).as(ended.toString()).isTrue();
        assertThat(ended.get(0).get("status").asText()).isEqualTo("TERMINATED");

        assertThat(failures).as("lazy loads outside a transaction / 5xx / unexpected statuses").isEmpty();
    }

    @Test
    void aBatchMustNameBetweenOneAndAHundredContracts(CapturedOutput out) throws Exception {
        List<String> tooMany = IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID().toString()).toList();
        expect(out, 400, admin, HttpMethod.POST, "/api/v1/leases/renew-batch",
                Map.of("leaseIds", tooMany, "rule", Map.of("mode", "NONE")));
        expect(out, 400, admin, HttpMethod.POST, "/api/v1/leases/renew-batch",
                Map.of("leaseIds", List.of(), "rule", Map.of("mode", "NONE")));
        expect(out, 400, admin, HttpMethod.POST, "/api/v1/leases/renew-batch",
                Map.of("leaseIds", List.of(renewMe), "rule", Map.of("mode", "PERCENT")));
        expect(out, 400, admin, HttpMethod.POST, "/api/v1/leases/terminate-batch", Map.of("leaseIds", tooMany));
        assertThat(failures).isEmpty();
    }

    // -------------------------------------------------------------- helpers

    private JsonNode expect(CapturedOutput out, int status, User caller, HttpMethod method, String url, Object body)
            throws Exception {
        int from = out.length();
        RestClient.RequestBodySpec spec = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .method(method).uri(url)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", f.tenantId().toString())
                .header("X-User-Tenant-Id", f.tenantId().toString());
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        ResponseEntity<String> res = spec.retrieve().onStatus(s -> true, (req, r) -> { }).toEntity(String.class);
        String log = out.toString().substring(Math.min(from, out.length()));
        String route = caller.getRole() + " " + method + " " + url;
        if (log.contains("LazyInitializationException") || log.contains("could not initialize proxy")) {
            failures.add("lazy: " + route);
        }
        if (res.getStatusCode().value() != status) {
            failures.add(route + " -> " + res.getStatusCode().value() + " " + res.getBody());
        }
        assertThat(failures).isEmpty();
        return res.getBody() == null || res.getBody().isEmpty() ? json.nullNode() : json.readTree(res.getBody());
    }

    private User user(UserRole role) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(f.tenantId());
        return userRepo.save(u);
    }
}
