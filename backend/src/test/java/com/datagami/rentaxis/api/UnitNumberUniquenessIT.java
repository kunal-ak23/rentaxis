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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.datagami.rentaxis.testsupport.CrossTenantHttp.ref;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Break-it round 3 (ops3) F1/F2. A unit number is unique per building (per property
 * for a unit in no building), compared trimmed, whitespace-collapsed, case-blind and
 * with Arabic-Indic digits read as ASCII — through Add Unit, the CSV upload (against
 * the building and within the file) and, as a backstop, changeset 157's unique
 * indexes. The CSV rows meet Add Unit's field rules.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnitNumberUniquenessIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired PropertyService propertyService;
    @Autowired BuildingRepository buildingRepo;
    @Autowired JdbcTemplate jdbc;

    private CrossTenantHttp http;
    private UUID tenant;
    private User admin;
    private UUID propertyId;
    private UUID towerA;
    private UUID towerB;

    @BeforeEach
    void setUp() {
        http = new CrossTenantHttp(port, orgRepo, userRepo, accountService, propertyAccountService, propertyService);
        tenant = http.tenant("UnitNo-");
        Property p = http.property("Dup Heights");
        propertyId = p.getId();
        towerA = building(p, "Tower A");
        towerB = building(p, "Tower B");
        admin = http.admin(tenant);
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

    private ResponseEntity<Map> add(String number, UUID building) {
        Map<String, Object> b = new HashMap<>();
        b.put("unitNumber", number);
        b.put("type", "BHK1");
        b.put("property", ref(propertyId));
        if (building != null) b.put("building", ref(building));
        return http.call(admin, HttpMethod.POST, "/api/v1/units", b);
    }

    private long units() {
        return jdbc.queryForObject("select count(*) from units where tenant_id = ?", Long.class, tenant);
    }

    private ResponseEntity<Object> bulk(String csv, UUID building) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "units.csv"; }
        });
        form.add("propertyId", propertyId.toString());
        if (building != null) form.add("buildingId", building.toString());
        return http.request(admin, HttpMethod.POST, "/api/v1/units/bulk")
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(Object.class);
    }

    @Test
    void theSameNumberTwiceInOneBuildingIsRefusedNamingTheClash() {
        assertThat(add("OPS-DUP-1", towerA).getStatusCode().value()).isEqualTo(200);

        var second = add("OPS-DUP-1", towerA);

        assertThat(second.getStatusCode().value()).isEqualTo(400);
        assertThat(second.getBody().get("code")).isEqualTo("unit.numberTaken");
        assertThat((String) second.getBody().get("message")).isEqualTo("Unit OPS-DUP-1 already exists in Tower A");
        assertThat(units()).isEqualTo(1);
    }

    @Test
    void caseSpacesAndArabicIndicDigitsAreTheSameNumber() {
        assertThat(add("OPS-B101", towerA).getStatusCode().value()).isEqualTo(200);
        assertThat(add(" ops-b101 ", towerA).getStatusCode().value()).isEqualTo(400);
        assertThat(add("OPS-B١٠١", towerA).getStatusCode().value()).isEqualTo(400);

        assertThat(add("Flat  7", null).getStatusCode().value()).isEqualTo(200);
        assertThat(add("flat 7", null).getStatusCode().value()).isEqualTo(400);
        assertThat(add("FLAT ۷", null).getStatusCode().value()).isEqualTo(400);
        assertThat(units()).isEqualTo(2);
        // Stored with the inner whitespace collapsed.
        assertThat(jdbc.queryForObject("select count(*) from units where tenant_id = ? and unit_number = 'Flat 7'",
                Long.class, tenant)).isEqualTo(1);
    }

    @Test
    void theSameNumberInAnotherBuildingOrWithoutABuildingIsFine() {
        assertThat(add("101", towerA).getStatusCode().value()).isEqualTo(200);
        assertThat(add("101", towerB).getStatusCode().value()).isEqualTo(200);
        assertThat(add("101", null).getStatusCode().value()).isEqualTo(200);
        assertThat(units()).isEqualTo(3);
    }

    @Test
    void twoTabsAddingTheSameNumberAtOnceSaveOne() throws Exception {
        int tabs = 6;
        ExecutorService pool = Executors.newFixedThreadPool(tabs);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < tabs; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return add("RACE-1", towerA).getStatusCode().value();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) statuses.add(f.get());
            assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
            assertThat(statuses).filteredOn(s -> s != 200).allMatch(s -> s == 400);
        } finally {
            pool.shutdownNow();
        }
        assertThat(units()).isEqualTo(1);
    }

    @Test
    void theDatabaseRefusesADuplicateThatSkipsTheService() {
        assertThat(add("IDX-1", towerA).getStatusCode().value()).isEqualTo(200);
        assertThatThrownBy(() -> jdbc.update(
                "insert into units (id, tenant_id, property_id, building_id, unit_number) values (gen_random_uuid(), ?, ?, ?, ?)",
                tenant, propertyId, towerA, " idx-١ "))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_units_number_building");
        assertThatThrownBy(() -> {
            jdbc.update("insert into units (id, tenant_id, property_id, unit_number) values (gen_random_uuid(), ?, ?, ?)",
                    tenant, propertyId, "LOOSE-1");
            jdbc.update("insert into units (id, tenant_id, property_id, unit_number) values (gen_random_uuid(), ?, ?, ?)",
                    tenant, propertyId, "loose-1");
        }).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_units_number_property");
    }

    @Test
    @SuppressWarnings("unchecked")
    void csvRowsRepeatingEachOtherOrAnExistingUnitAreRowErrorsAndNothingIsSaved() {
        assertThat(add("OPS-B101", towerA).getStatusCode().value()).isEqualTo(200);

        var res = bulk("""
                unitNumber,type,sizeSqft,expectedRent,status
                OPS-DUP-9,BHK1,700,50000,VACANT
                ops-dup-9 ,BHK1,700,50000,VACANT
                OPS-B101,BHK1,700,50000,VACANT
                OPS-NEW-1,BHK1,700,50000,VACANT
                """, towerA);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat((List<String>) ((Map<String, Object>) res.getBody()).get("errors")).containsExactly(
                "Row 3: Unit ops-dup-9 is listed twice in this file (also row 2)",
                "Row 4: Unit OPS-B101 already exists in Tower A");
        assertThat(units()).isEqualTo(1);

        // The same file into Tower B is fine apart from its own repeat.
        var towerBRes = bulk("""
                unitNumber,type
                OPS-B101,BHK1
                OPS-NEW-1,BHK1
                """, towerB);
        assertThat(towerBRes.getStatusCode().value()).isEqualTo(200);
        assertThat(units()).isEqualTo(3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void csvNegativeSizeNegativeRentAndThirdDecimalAreRowErrors() {
        var res = bulk("""
                unitNumber,type,sizeSqft,expectedRent,status
                OPS-B201,BHK1,-50,-9000,VACANT
                OPS-B202,BHK1,850,1000.555,VACANT
                """, towerA);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat((List<String>) ((Map<String, Object>) res.getBody()).get("errors")).containsExactly(
                "Row 2: Size must be greater than 0",
                "Row 2: Expected rent: Amounts cannot be negative",
                "Row 3: Expected rent: Amounts can have at most 2 decimal places");
        assertThat(units()).isZero();
    }

    @Test
    void addUnitRefusesTheSameFieldValues() {
        Map<String, Object> b = new HashMap<>();
        b.put("unitNumber", "SZ-1");
        b.put("sizeSqft", -50);
        b.put("property", ref(propertyId));
        var res = http.call(admin, HttpMethod.POST, "/api/v1/units", b);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().get("message")).isEqualTo("Size must be greater than 0");
        assertThat(units()).isZero();
    }
}
