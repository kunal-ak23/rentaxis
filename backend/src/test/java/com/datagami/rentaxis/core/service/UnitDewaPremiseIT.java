package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.CrossTenantHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.CrossTenantHttp.ref;
import static org.assertj.core.api.Assertions.assertThat;

/** DEWA premise number on units: create, set endpoint, rules; works with RENTAL_INDEX off (the default). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnitDewaPremiseIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired PropertyService propertyService;
    @Autowired JdbcTemplate jdbc;

    private CrossTenantHttp http;
    private UUID propertyId;
    private User admin;

    @BeforeEach
    void setUp() {
        http = new CrossTenantHttp(port, orgRepo, userRepo, accountService, propertyAccountService, propertyService);
        UUID tenant = http.tenant("Dewa-");
        propertyId = http.property("Dewa Tower").getId();
        admin = http.admin(tenant);
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private ResponseEntity<Map> create(User caller, UUID property, String dewa) {
        Map<String, Object> b = new HashMap<>();
        b.put("unitNumber", "U-" + UUID.randomUUID().toString().substring(0, 6));
        b.put("type", "BHK1");
        b.put("property", ref(property));
        if (dewa != null) b.put("dewaPremiseNumber", dewa);
        return http.call(caller, HttpMethod.POST, "/api/v1/units", b);
    }

    private ResponseEntity<Map> setDewa(User caller, Object unitId, String dewa) {
        Map<String, Object> b = new HashMap<>();
        b.put("dewaPremiseNumber", dewa);
        return http.call(caller, HttpMethod.PUT, "/api/v1/units/" + unitId + "/dewa-premise-number", b);
    }

    @Test
    void createWithDewaStoresDigitsOnly() {
        var res = create(admin, propertyId, "681 204 553");
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("dewaPremiseNumber")).isEqualTo("681204553");
    }

    @Test
    void setDewaOnExistingUnit() {
        Object id = create(admin, propertyId, null).getBody().get("id");
        var res = setDewa(admin, id, "123456");
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("dewaPremiseNumber")).isEqualTo("123456");
    }

    @Test
    void blankClearsIt() {
        Object id = create(admin, propertyId, "123456789").getBody().get("id");
        var res = setDewa(admin, id, "");
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("dewaPremiseNumber")).isNull();
        assertThat(jdbc.queryForObject("select dewa_premise_number from units where id = ?::uuid", String.class, id.toString()))
                .isNull();
    }

    @Test
    void rejectsNonDigits() {
        Object id = create(admin, propertyId, null).getBody().get("id");
        var res = setDewa(admin, id, "12AB");
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().get("code")).isEqualTo("unit.dewaInvalid");
        assertThat(create(admin, propertyId, "12AB").getBody().get("code")).isEqualTo("unit.dewaInvalid");
    }

    @Test
    void rejectsTooShortOrLong() {
        Object id = create(admin, propertyId, null).getBody().get("id");
        for (String bad : new String[]{"12345", "1234567890123"}) {
            var res = setDewa(admin, id, bad);
            assertThat(res.getStatusCode().value()).isEqualTo(400);
            assertThat(res.getBody().get("code")).isEqualTo("unit.dewaInvalid");
        }
    }

    @Test
    void duplicateInSameTenantRejected() {
        create(admin, propertyId, "555000111");
        var res = create(admin, propertyId, "555 000 111");
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().get("code")).isEqualTo("unit.dewaTaken");
        Object other = create(admin, propertyId, null).getBody().get("id");
        assertThat(setDewa(admin, other, "555000111").getBody().get("code")).isEqualTo("unit.dewaTaken");
    }

    @Test
    void settingTheSameNumberOnTheSameUnitIsFine() {
        Object id = create(admin, propertyId, "777000111").getBody().get("id");
        assertThat(setDewa(admin, id, "777000111").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void sameNumberInOtherTenantAllowed() {
        create(admin, propertyId, "888000111");
        UUID tenantB = http.tenant("Dewa-B-");
        UUID propB = http.property("Tower B").getId();
        User adminB = http.admin(tenantB);
        TenantContextHolder.clear();
        var res = create(adminB, propB, "888000111");
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("dewaPremiseNumber")).isEqualTo("888000111");
    }

    @Test
    void worksWithRentalIndexFlagOff() {
        // RENTAL_INDEX is off by default for a new tenant; the DEWA number does not depend on it.
        assertThat(create(admin, propertyId, "999000111").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void otherTenantsUnitIs404() {
        Object id = create(admin, propertyId, null).getBody().get("id");
        UUID tenantB = http.tenant("Dewa-C-");
        User adminB = http.admin(tenantB);
        TenantContextHolder.clear();
        assertThat(setDewa(adminB, id, "123456789").getStatusCode().value()).isEqualTo(404);
    }
}
