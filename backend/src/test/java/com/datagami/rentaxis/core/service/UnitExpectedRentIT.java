package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.CrossTenantHttp.ref;
import static org.assertj.core.api.Assertions.assertThat;

/** Expected rent: edit one unit, and set it per unit type across projects in one go. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnitExpectedRentIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired PropertyService propertyService;
    @Autowired JdbcTemplate jdbc;

    private CrossTenantHttp http;
    private UUID tenantId;
    private UUID towerA;
    private UUID towerB;
    private UUID towerC;
    private User admin;

    @BeforeEach
    void setUp() {
        http = new CrossTenantHttp(port, orgRepo, userRepo, accountService, propertyAccountService, propertyService);
        tenantId = http.tenant("Rent-");
        towerA = http.property("Rent Tower A").getId();
        towerB = http.property("Rent Tower B").getId();
        towerC = http.property("Rent Tower C").getId();
        admin = http.admin(tenantId);
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private String unit(UUID property, String type, String rent) {
        Map<String, Object> b = new HashMap<>();
        b.put("unitNumber", "U-" + UUID.randomUUID().toString().substring(0, 6));
        b.put("type", type);
        b.put("expectedRent", new BigDecimal(rent));
        b.put("property", ref(property));
        ResponseEntity<Map> res = http.call(admin, HttpMethod.POST, "/api/v1/units", b);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return res.getBody().get("id").toString();
    }

    private BigDecimal rentOf(String unitId) {
        return jdbc.queryForObject("select expected_rent from units where id = ?::uuid", BigDecimal.class, unitId);
    }

    private ResponseEntity<Map> setRent(User caller, String unitId, Object rent) {
        Map<String, Object> b = new HashMap<>();
        b.put("expectedRent", rent);
        return http.call(caller, HttpMethod.PUT, "/api/v1/units/" + unitId + "/expected-rent", b);
    }

    private ResponseEntity<Map> bulk(User caller, List<UUID> properties, Map<String, Object> rents) {
        Map<String, Object> b = new HashMap<>();
        b.put("propertyIds", properties);
        b.put("rents", rents);
        return http.call(caller, HttpMethod.PUT, "/api/v1/units/expected-rent/bulk", b);
    }

    @Test
    void editsOneUnitsExpectedRent() {
        String id = unit(towerA, "BHK2", "25000");
        ResponseEntity<Map> res = setRent(admin, id, 31500);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(rentOf(id)).isEqualByComparingTo("31500");
    }

    @Test
    void rejectsAMissingOrNegativeRent() {
        String id = unit(towerA, "BHK2", "25000");
        assertThat(setRent(admin, id, null).getStatusCode().value()).isEqualTo(400);
        assertThat(setRent(admin, id, -5).getStatusCode().value()).isEqualTo(400);
        assertThat(rentOf(id)).isEqualByComparingTo("25000");
    }

    @Test
    void setsRentPerTypeAcrossTheChosenProjectsOnly() {
        String a2 = unit(towerA, "BHK2", "25000");
        String a3 = unit(towerA, "BHK3", "35000");
        String aStudio = unit(towerA, "STUDIO", "18000");
        String b2 = unit(towerB, "BHK2", "26000");
        String c2 = unit(towerC, "BHK2", "27000");

        ResponseEntity<Map> res = bulk(admin, List.of(towerA, towerB), Map.of("BHK2", 30000, "BHK3", 40000));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("total")).isEqualTo(3);
        assertThat(((Map<?, ?>) res.getBody().get("updated")).get("BHK2")).isEqualTo(2);
        assertThat(((Map<?, ?>) res.getBody().get("updated")).get("BHK3")).isEqualTo(1);

        assertThat(rentOf(a2)).isEqualByComparingTo("30000");
        assertThat(rentOf(b2)).isEqualByComparingTo("30000");
        assertThat(rentOf(a3)).isEqualByComparingTo("40000");
        assertThat(rentOf(aStudio)).isEqualByComparingTo("18000");
        assertThat(rentOf(c2)).isEqualByComparingTo("27000");
    }

    @Test
    void anInvalidRentChangesNothing() {
        String a2 = unit(towerA, "BHK2", "25000");
        String a3 = unit(towerA, "BHK3", "35000");
        Map<String, Object> rents = new HashMap<>();
        rents.put("BHK2", 30000);
        rents.put("BHK3", -1);
        assertThat(bulk(admin, List.of(towerA), rents).getStatusCode().value()).isEqualTo(400);
        assertThat(rentOf(a2)).isEqualByComparingTo("25000");
        assertThat(rentOf(a3)).isEqualByComparingTo("35000");
    }

    @Test
    void needsAProjectAndARent() {
        assertThat(bulk(admin, List.of(), Map.of("BHK2", 30000)).getStatusCode().value()).isEqualTo(400);
        assertThat(bulk(admin, List.of(towerA), Map.of()).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void anotherTenantsProjectIs404AndUntouched() {
        String a2 = unit(towerA, "BHK2", "25000");
        UUID tenantB = http.tenant("Rent-B-");
        User adminB = http.admin(tenantB);
        TenantContextHolder.clear();
        assertThat(bulk(adminB, List.of(towerA), Map.of("BHK2", 1)).getStatusCode().value()).isEqualTo(404);
        assertThat(setRent(adminB, a2, 1).getStatusCode().value()).isEqualTo(404);
        assertThat(rentOf(a2)).isEqualByComparingTo("25000");
    }

    @Test
    void onlyAdminsMayChangeRent() {
        String a2 = unit(towerA, "BHK2", "25000");
        User accountant = http.user(tenantId, UserRole.ACCOUNTANT);
        User renter = http.user(tenantId, UserRole.RENTER);
        assertThat(setRent(accountant, a2, 1).getStatusCode().value()).isEqualTo(403);
        assertThat(bulk(renter, List.of(towerA), Map.of("BHK2", 1)).getStatusCode().value()).isEqualTo(403);
        assertThat(rentOf(a2)).isEqualByComparingTo("25000");
    }
}
