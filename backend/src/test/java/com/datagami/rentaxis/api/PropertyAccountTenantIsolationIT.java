package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F14-12: another organisation's property id is 404 on every property-accounts
 * door, not a 200 with every role unmapped (nor a write to it).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class PropertyAccountTenantIsolationIT extends AbstractPostgresIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private UUID tenantA;
    private UUID tenantB;
    private UUID propertyA;
    private UUID propertyB;

    @BeforeEach
    void setUp() {
        TenantContextHolder.clear();
        tenantA = tenant();
        tenantB = tenant();
        propertyA = property(tenantA);
        propertyB = property(tenantB);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO landlord_org (id, name, slug) VALUES (?, ?, ?)", id, "T-" + id, "t-" + id);
        return id;
    }

    private UUID property(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO properties (id, tenant_id, name_en, emirate, code) VALUES (?,?,?,?,?)",
                id, tenant, "P-" + id, "DUBAI", "P" + id.toString().substring(0, 6));
        return id;
    }

    private MockHttpServletRequestBuilder asAdminOfA(MockHttpServletRequestBuilder r) {
        return r.header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Role", "TENANT_ADMIN")
                .header("X-User-Tenant-Id", tenantA.toString())
                .header("X-Tenant-Id", tenantA.toString());
    }

    @Test
    void anotherTenantsPropertyIsNotFoundOnEveryDoor() throws Exception {
        mvc.perform(asAdminOfA(get("/api/v1/properties/" + propertyB + "/accounts"))).andExpect(status().isNotFound());
        mvc.perform(asAdminOfA(post("/api/v1/properties/" + propertyB + "/accounts/generate")))
                .andExpect(status().isNotFound());
        mvc.perform(asAdminOfA(put("/api/v1/properties/" + propertyB + "/accounts/BANK")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":\"" + UUID.randomUUID() + "\"}")))
                .andExpect(status().isNotFound());
        mvc.perform(asAdminOfA(delete("/api/v1/properties/" + propertyB + "/accounts/BANK")))
                .andExpect(status().isNotFound());
        mvc.perform(asAdminOfA(get("/api/v1/properties/" + UUID.randomUUID() + "/accounts")))
                .andExpect(status().isNotFound());
    }

    @Test
    void theCallersOwnPropertyStillAnswers() throws Exception {
        mvc.perform(asAdminOfA(get("/api/v1/properties/" + propertyA + "/accounts"))).andExpect(status().isOk());
    }
}
