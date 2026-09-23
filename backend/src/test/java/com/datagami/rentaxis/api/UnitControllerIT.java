package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.CrossTenantHttp.nestedId;
import static com.datagami.rentaxis.testsupport.CrossTenantHttp.ref;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #340 review C1: {@code POST /api/v1/units} bound the {@code Unit} entity, and
 * {@code Property.id} is writable to Jackson, so {@code property: {id}} naming
 * another tenant's property was written as a foreign key unchecked. The CSV import
 * built the same stubs from its {@code propertyId}/{@code buildingId} params.
 * Both now resolve the ids in the caller's tenant: foreign or missing is a 404,
 * a building off the property is a 400, and nothing is saved either way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnitControllerIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired PropertyService propertyService;
    @Autowired BuildingRepository buildingRepo;
    @Autowired JdbcTemplate jdbc;

    private CrossTenantHttp http;
    private UUID tenantA;
    private User admin;
    private UUID propertyId;
    private UUID buildingId;
    private UUID siblingBuildingId;
    private UUID otherPropertyId;
    private UUID otherBuildingId;

    @BeforeEach
    void setUp() {
        http = new CrossTenantHttp(port, orgRepo, userRepo, accountService, propertyAccountService, propertyService);

        http.tenant("Unit-other-");
        Property op = http.property("Other Tower");
        otherPropertyId = op.getId();
        otherBuildingId = building(op, "Other B1");

        tenantA = http.tenant("Unit-");
        Property p = http.property("Marina Heights");
        propertyId = p.getId();
        buildingId = building(p, "Tower A");
        siblingBuildingId = building(http.property("Sibling"), "Sibling B1");
        admin = http.admin(tenantA);
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private UUID building(Property p, String name) {
        Building b = new Building();
        b.setProperty(p);
        b.setNameEn(name);
        return buildingRepo.save(b).getId();
    }

    private long unitsOfA() {
        return jdbc.queryForObject("select count(*) from units where tenant_id = ?", Long.class, tenantA);
    }

    private static Map<String, Object> body(UUID property, UUID building) {
        Map<String, Object> b = new HashMap<>();
        b.put("unitNumber", "U-" + UUID.randomUUID().toString().substring(0, 6));
        b.put("type", "BHK1");
        b.put("sizeSqft", 850);
        b.put("expectedRent", 55000);
        b.put("status", "VACANT");
        if (property != null) b.put("property", ref(property));
        if (building != null) b.put("building", ref(building));
        return b;
    }

    // ---- POST /units ----

    @Test
    void createWithOwnPropertyAndBuildingLinksBoth() {
        var res = http.call(admin, HttpMethod.POST, "/api/v1/units", body(propertyId, buildingId));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(nestedId(res, "property")).isEqualTo(propertyId.toString());
        assertThat(nestedId(res, "building")).isEqualTo(buildingId.toString());
        assertThat(res.getBody().get("expectedRent")).isEqualTo(55000);
        assertThat(unitsOfA()).isEqualTo(1);
    }

    @Test
    void createWithAnotherTenantsPropertyIs404AndSavesNothing() {
        var res = http.call(admin, HttpMethod.POST, "/api/v1/units", body(otherPropertyId, null));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(unitsOfA()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from units where property_id = ?", Long.class, otherPropertyId))
                .isZero();
    }

    @Test
    void createWithAnotherTenantsBuildingIs404AndSavesNothing() {
        var res = http.call(admin, HttpMethod.POST, "/api/v1/units", body(propertyId, otherBuildingId));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(unitsOfA()).isZero();
    }

    @Test
    void createWithAMissingPropertyIs404() {
        var res = http.call(admin, HttpMethod.POST, "/api/v1/units", body(UUID.randomUUID(), null));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(unitsOfA()).isZero();
    }

    @Test
    void createWithABuildingOfAnotherOwnPropertyIs400() {
        var res = http.call(admin, HttpMethod.POST, "/api/v1/units", body(propertyId, siblingBuildingId));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(unitsOfA()).isZero();
    }

    @Test
    void createWithoutAPropertyIs400() {
        var res = http.call(admin, HttpMethod.POST, "/api/v1/units", body(null, null));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(unitsOfA()).isZero();
    }

    // ---- POST /units/bulk ----

    private ResponseEntity<String> bulk(UUID property, UUID building) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource("unitNumber,type\n101,BHK1\n102,STUDIO\n"
                .getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "units.csv"; }
        });
        form.add("propertyId", property.toString());
        if (building != null) form.add("buildingId", building.toString());
        return http.request(admin, HttpMethod.POST, "/api/v1/units/bulk")
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(String.class);
    }

    @Test
    void bulkIntoOwnPropertyAndBuildingSavesEveryRow() {
        var res = bulk(propertyId, buildingId);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "select count(*) from units where tenant_id = ? and property_id = ? and building_id = ?",
                Long.class, tenantA, propertyId, buildingId)).isEqualTo(2);
    }

    @Test
    void bulkIntoAnotherTenantsPropertyIs404AndSavesNothing() {
        var res = bulk(otherPropertyId, null);
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(unitsOfA()).isZero();
    }

    @Test
    void bulkWithAnotherTenantsBuildingIs404AndSavesNothing() {
        var res = bulk(propertyId, otherBuildingId);
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(unitsOfA()).isZero();
    }

    @Test
    void bulkWithABuildingOfAnotherOwnPropertyIs400() {
        var res = bulk(propertyId, siblingBuildingId);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(unitsOfA()).isZero();
    }
}
