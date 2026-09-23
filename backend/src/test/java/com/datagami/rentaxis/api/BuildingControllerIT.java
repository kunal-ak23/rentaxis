package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.CrossTenantHttp.nestedId;
import static com.datagami.rentaxis.testsupport.CrossTenantHttp.ref;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #340 review C2: {@code POST /api/v1/buildings} bound the {@code Building}
 * entity, so {@code property: {id}} naming another tenant's property was written
 * unchecked. The id is now resolved in the caller's tenant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BuildingControllerIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired PropertyService propertyService;
    @Autowired JdbcTemplate jdbc;

    private CrossTenantHttp http;
    private UUID tenantA;
    private User admin;
    private UUID propertyId;
    private UUID otherPropertyId;

    @BeforeEach
    void setUp() {
        http = new CrossTenantHttp(port, orgRepo, userRepo, accountService, propertyAccountService, propertyService);
        http.tenant("Bldg-other-");
        otherPropertyId = http.property("Other Tower").getId();
        tenantA = http.tenant("Bldg-");
        propertyId = http.property("Marina Heights").getId();
        admin = http.admin(tenantA);
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private long buildingsOfA() {
        return jdbc.queryForObject("select count(*) from buildings where tenant_id = ?", Long.class, tenantA);
    }

    private static Map<String, Object> body(UUID property) {
        Map<String, Object> b = new HashMap<>();
        b.put("nameEn", "Tower A");
        b.put("nameAr", "برج أ");
        b.put("floors", 12);
        if (property != null) b.put("property", ref(property));
        return b;
    }

    @Test
    void createOnOwnPropertyLinksIt() {
        var res = http.call(admin, HttpMethod.POST, "/api/v1/buildings", body(propertyId));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(nestedId(res, "property")).isEqualTo(propertyId.toString());
        assertThat(res.getBody().get("floors")).isEqualTo(12);
        assertThat(buildingsOfA()).isEqualTo(1);
    }

    @Test
    void createOnAnotherTenantsPropertyIs404AndSavesNothing() {
        var res = http.call(admin, HttpMethod.POST, "/api/v1/buildings", body(otherPropertyId));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(buildingsOfA()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from buildings where property_id = ?", Long.class, otherPropertyId))
                .isZero();
    }

    @Test
    void createOnAMissingPropertyIs404() {
        var res = http.call(admin, HttpMethod.POST, "/api/v1/buildings", body(UUID.randomUUID()));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(buildingsOfA()).isZero();
    }

    @Test
    void createWithoutAPropertyIs400() {
        var res = http.call(admin, HttpMethod.POST, "/api/v1/buildings", body(null));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(buildingsOfA()).isZero();
    }
}
