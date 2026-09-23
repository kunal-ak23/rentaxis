package com.datagami.rentaxis.testsupport;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * Two landlord orgs and a real HTTP client, for the cross-tenant reference tests
 * of the endpoints that used to bind JPA entities (PR #340 review C1-C3, I1).
 *
 * <p>Full HTTP on purpose: the bugs lived in request binding, which a
 * service-level test handing in managed entities never exercises.</p>
 */
public final class CrossTenantHttp {

    private final int port;
    private final LandlordOrgRepository orgRepo;
    private final UserRepository userRepo;
    private final AccountService accountService;
    private final PropertyAccountService propertyAccountService;
    private final PropertyService propertyService;

    public CrossTenantHttp(int port, LandlordOrgRepository orgRepo, UserRepository userRepo,
                           AccountService accountService, PropertyAccountService propertyAccountService,
                           PropertyService propertyService) {
        this.port = port;
        this.orgRepo = orgRepo;
        this.userRepo = userRepo;
        this.accountService = accountService;
        this.propertyAccountService = propertyAccountService;
        this.propertyService = propertyService;
    }

    /** A new org with a seeded chart of accounts, made current. */
    public UUID tenant(String prefix) {
        LandlordOrg org = new LandlordOrg();
        org.setName(prefix + UUID.randomUUID());
        UUID id = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(id);
        accountService.seedDefaultAccounts();
        propertyAccountService.seedDefaultTemplateAndDefaults();
        return id;
    }

    /** A property in the current tenant. */
    public Property property(String name) {
        Property p = new Property();
        p.setNameEn(name + " " + UUID.randomUUID());
        p.setEmirate(Emirate.DUBAI);
        return propertyService.createProperty(p);
    }

    public User admin(UUID tenantId) {
        User u = new User();
        u.setEmail("xt-admin-" + UUID.randomUUID() + "@t.io");
        u.setName("Admin");
        u.setRole(UserRole.TENANT_ADMIN);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        return userRepo.save(u);
    }

    public RestClient.RequestBodySpec request(User caller, HttpMethod method, String path) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port).build()
                .method(method).uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", caller.getTenantId().toString())
                .header("X-User-Tenant-Id", caller.getTenantId().toString());
    }

    @SuppressWarnings("rawtypes")
    public ResponseEntity<Map> call(User caller, HttpMethod method, String path, Object body) {
        RestClient.RequestBodySpec spec = request(caller, method, path);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(Map.class);
    }

    public static Map<String, Object> ref(UUID id) {
        return Map.of("id", id.toString());
    }

    /** The {@code id} of a nested object in a response body, or null. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static String nestedId(ResponseEntity<Map> res, String field) {
        Map<String, Object> m = (Map<String, Object>) res.getBody().get(field);
        return m == null ? null : (String) m.get("id");
    }
}
