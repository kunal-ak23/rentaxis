package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Client request 2026-10-29: a tenant must have both an email and a mobile, on create
 * and on edit, whichever client sends it. The mobile follows the lenient rule the
 * profile already uses ({@code PhoneNumbers.LENIENT_PATTERN}): digits, spaces, "+",
 * "-" and parentheses, 7-15 digits, Arabic-Indic digits accepted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RenterContactDetailsRequiredIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;

    private LeaseTestFixtures fixtures;
    private User admin;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap();
        User u = new User();
        u.setEmail("admin-" + UUID.randomUUID() + "@t.io");
        u.setName("Admin");
        u.setRole(UserRole.TENANT_ADMIN);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(fixtures.tenantId());
        admin = userRepo.save(u);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> send(HttpMethod method, String path, Map<String, Object> body) {
        return RestClient.builder().baseUrl("http://localhost:" + port).build()
                .method(method).uri(path)
                .header("X-User-Id", admin.getId().toString())
                .header("X-User-Role", admin.getRole().name())
                .header("X-Tenant-Id", admin.getTenantId().toString())
                .header("X-User-Tenant-Id", admin.getTenantId().toString())
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().onStatus(status -> true, (request, response) -> { })
                .toEntity(Map.class);
    }

    private static Map<String, Object> tenant(String email, String phone) {
        Map<String, Object> body = new HashMap<>();
        body.put("nameEn", "Layla Haddad");
        body.put("email", email);
        body.put("phone", phone);
        body.put("createPortalAccount", false);
        return body;
    }

    private static String unique() {
        return "layla-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void creatingATenantNeedsBothAnEmailAndAMobile() {
        ResponseEntity<Map> noMobile = send(HttpMethod.POST, "/api/v1/renters", tenant(unique(), null));
        assertThat(noMobile.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) noMobile.getBody().get("message")).contains("phone: Mobile is required");

        ResponseEntity<Map> blankMobile = send(HttpMethod.POST, "/api/v1/renters", tenant(unique(), "  "));
        assertThat(blankMobile.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> noEmail = send(HttpMethod.POST, "/api/v1/renters", tenant(null, "+971 50 123 4567"));
        assertThat(noEmail.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) noEmail.getBody().get("message")).contains("email: Email is required");

        ResponseEntity<Map> notAPhone = send(HttpMethod.POST, "/api/v1/renters", tenant(unique(), "N/A"));
        assertThat(notAPhone.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) notAPhone.getBody().get("message")).contains("phone: Mobile may contain only digits");

        ResponseEntity<Map> ok = send(HttpMethod.POST, "/api/v1/renters", tenant(unique(), "050 123 4567"));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aMobileTypedInArabicDigitsOrPastedWithNonBreakingSpacesIsAccepted() {
        ResponseEntity<Map> arabic = send(HttpMethod.POST, "/api/v1/renters", tenant(unique(), "٠٥٠ ١٢٣ ٤٥٦٧"));
        assertThat(arabic.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> pasted = send(HttpMethod.POST, "/api/v1/renters", tenant(unique(), "+971 50 123 4567"));
        assertThat(pasted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void editingATenantKeepsBothRequired() {
        ResponseEntity<Map> created = send(HttpMethod.POST, "/api/v1/renters", tenant(unique(), "+971501234567"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String path = "/api/v1/renters/" + created.getBody().get("id");
        String email = (String) created.getBody().get("email");

        ResponseEntity<Map> clearedMobile = send(HttpMethod.PUT, path, tenant(email, ""));
        assertThat(clearedMobile.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) clearedMobile.getBody().get("message")).contains("phone: Mobile is required");

        ResponseEntity<Map> clearedEmail = send(HttpMethod.PUT, path, tenant(null, "+971501234567"));
        assertThat(clearedEmail.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> ok = send(HttpMethod.PUT, path, tenant(email, "04 123 9911"));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
