package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Break round 1, F7: a SUPER_ADMIN with no organisation selected (Global View, no
 * X-Tenant-Id) reached org-scoped endpoints with the tenant filter off, so they
 * aggregated every organisation's data, or threw (fiscal settings, a 500). Now any
 * endpoint not on the cross-organisation allow-list answers 400 "Select an
 * organisation first"; with an organisation selected nothing changes, and the
 * cross-organisation administration endpoints keep working without one.
 */
class OrganisationRequiredIT extends AbstractCallerIdentityIT {

    private static final List<String> ORG_SCOPED_READS = List.of(
            "/api/v1/finance/fiscal-settings",
            "/api/v1/cheques/summary",
            "/api/v1/dashboard/summary",
            "/api/v1/finance/journals",
            "/api/v1/properties",
            "/api/v1/renters",
            "/api/v1/leases/paged",
            "/api/v1/tickets",
            "/api/v1/settings/org");

    @Autowired PropertyRepository propertyRepo;

    private UUID org;
    private UUID otherOrg;
    private User superAdmin;
    private User admin;
    private Property otherProperty;

    @BeforeEach
    void setUp() {
        org = newTenant("ORGREQ");
        otherOrg = newTenant("ORGREQ-OTHER");
        superAdmin = user(null, UserRole.SUPER_ADMIN, "x");
        TenantContextHolder.setTenantId(org);
        admin = user(org, UserRole.TENANT_ADMIN, "x");
        TenantContextHolder.setTenantId(otherOrg);
        Property p = new Property();
        p.setTenantId(otherOrg);
        p.setNameEn("OrgReq Tower " + UUID.randomUUID().toString().substring(0, 6));
        p.setEmirate(Emirate.DUBAI);
        p.setCode("OR" + UUID.randomUUID().toString().substring(0, 6));
        otherProperty = propertyRepo.save(p);
        TenantContextHolder.clear();
    }

    private RestClient.RequestHeadersSpec<?> as(User caller, String method, String uri, UUID tenant) {
        RestClient.RequestHeadersSpec<?> spec = "POST".equals(method)
                ? client().post().uri(uri).contentType(MediaType.APPLICATION_JSON).body("{\"nameEn\":\"X\"}")
                : client().get().uri(uri);
        spec = spec.header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name());
        if (tenant != null) {
            spec = spec.header("X-Tenant-Id", tenant.toString()).header("X-User-Tenant-Id", tenant.toString());
        }
        return spec;
    }

    private ResponseEntity<String> call(User caller, String method, String uri, UUID tenant) {
        return as(caller, method, uri, tenant).retrieve().onStatus(s -> true, (rq, rs) -> { })
                .toEntity(String.class);
    }

    @Test
    void orgScopedEndpointsRefuseASuperAdminWithNoOrganisation() {
        for (String uri : ORG_SCOPED_READS) {
            ResponseEntity<String> res = call(superAdmin, "GET", uri, null);
            assertThat(res.getStatusCode().value()).as(uri).isEqualTo(400);
            assertThat(res.getBody()).as(uri).contains("Select an organisation first");
        }
        assertThat(call(superAdmin, "POST", "/api/v1/renters", null).getStatusCode().value())
                .as("a write with no organisation").isEqualTo(400);
    }

    @Test
    void withAnOrganisationSelectedNothingChanges() {
        for (String uri : ORG_SCOPED_READS) {
            assertThat(call(superAdmin, "GET", uri, org).getStatusCode().value()).as("SA " + uri).isEqualTo(200);
            assertThat(call(admin, "GET", uri, org).getStatusCode().value()).as("TA " + uri).isEqualTo(200);
        }
    }

    @Test
    void crossOrganisationEndpointsStillWorkWithNoOrganisation() {
        for (String uri : List.of("/api/admin/users", "/api/admin/tenants", "/api/auth/me", "/api/auth/me/tenants",
                "/api/v1/notifications", "/api/v1/tenant/features", "/api/v1/tenant/info",
                "/api/v1/admin/app-versions", "/api/v1/admin/email/outbox",
                "/api/admin/users/" + admin.getId() + "/properties")) {
            assertThat(call(superAdmin, "GET", uri, null).getStatusCode().value()).as(uri).isEqualTo(200);
        }
    }

    @Test
    void theUserScreenPropertyPickerReadsTheNamedOrganisation() {
        String uri = "/api/admin/tenants/" + otherOrg + "/properties";
        assertThat(call(superAdmin, "GET", uri, null).getBody()).contains(otherProperty.getId().toString());
        // With a different organisation selected, the named one still answers.
        assertThat(call(superAdmin, "GET", uri, org).getBody()).contains(otherProperty.getId().toString());
        assertThat(call(superAdmin, "GET", "/api/admin/tenants/" + org + "/properties", null).getBody())
                .doesNotContain(otherProperty.getId().toString());
        assertThat(call(admin, "GET", uri, org).getStatusCode().value()).as("SUPER_ADMIN only").isEqualTo(403);
    }
}
